package cn.xinji.agentplatform.enforcement.legalreference;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class LegalReferenceDm8IntegrationTest {
    @Test void realDm8PersistsVerbatimSearchBindingReviewAndReceipts() throws Exception {
        String adminUrl = required("AGENT_ENF_ENGINEERING_ADMIN_URL");
        String admin = required("AGENT_ENF_ENGINEERING_ADMIN_USER");
        String password = required("AGENT_ENF_ENGINEERING_ADMIN_PASSWORD");
        String schema = random("LR");
        String schemaPassword = "T9a" + schema.toLowerCase() + "K7z";
        create(adminUrl, admin, password, schema, schemaPassword);
        try {
            var source = new DriverManagerDataSource(adminUrl, schema, schemaPassword);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                        workspace().resolve("db/dm8/V075__enforcement_v2_legal_reference.sql")));
            }
            var db = new JdbcTemplate(source);
            var json = new ObjectMapper().findAndRegisterModules();
            var store = new LegalReferenceJdbcStore(db,
                    new TransactionTemplate(new DataSourceTransactionManager(source)), json, Clock.systemUTC());
            var service = new LegalReferenceService(store,
                    new FileLegalKnowledgeSource(workspace().resolve(
                            "data/enforcement/legal-reference-v2-product-input-v1/catalog.json"), json),
                    (actor, org, type, id, version, action, purpose) -> { },
                    (actor, org, type, id, version, anchor, purpose) -> "target-digest-v1");
            var request = new SearchRequest("ORG-ENF-PROJECT", "CONDUCT_REPORT", "REPORT-DM8", 1,
                    "analysis.findings[0].basis", "PROJECT-CASE", "PROJECT_DOCUMENT_REVIEW",
                    "PROJECT_TEST", Instant.parse("2026-09-29T01:00:00Z"), "第一条",
                    List.of("CNIPA_PATENT_IMPLEMENTING_RULES_2023"));
            Receipt search = service.search("tester", "dm8-legal-search", request);
            assertEquals(search, service.search("tester", "dm8-legal-search", request));
            SearchResult result = service.readSearch("tester", search.resultId(), 1);
            assertEquals(SearchState.COMPLETED, result.status());
            assertFalse(result.members().isEmpty());
            Candidate candidate = result.members().getFirst();
            assertEquals(LegalReferenceService.hash(candidate.verbatimText()), candidate.verbatimSha256());

            Receipt bound = service.bind("tester", "dm8-legal-bind", new Selection(search.resultId(), 1,
                    candidate.candidateId(), "CONDUCT_REPORT", "REPORT-DM8", 1,
                    "analysis.findings[0].basis"));
            ReferenceRevision reference = service.read("tester", bound.resultId(), 1);
            assertEquals(ReviewState.PENDING, reference.reviewState());
            assertEquals(candidate.verbatimText(), reference.verbatimText());
            assertThrows(IllegalArgumentException.class, () -> service.review("reviewer", "dm8-legal-review",
                    bound.resultId(), new LegalReferenceService.Review(1, "CONFIRMED", "仍有适用缺口")));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM enf9_legal_receipt", Integer.class));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM enf9_legal_event", Integer.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf9_legal_reference", Integer.class));
        } finally {
            remove(adminUrl, admin, password, schema);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String random(String prefix) {
        byte[] value = new byte[7]; new SecureRandom().nextBytes(value);
        return prefix + HexFormat.of().formatHex(value).toUpperCase();
    }
    private static Path workspace() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("db/dm8"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace unavailable");
        return root;
    }
    private static void create(String url, String user, String password, String schema, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }
    private static void remove(String url, String user, String password, String schema) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("DROP USER " + schema + " CASCADE");
        }
    }
}

