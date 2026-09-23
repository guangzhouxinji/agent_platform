package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.AuthorizedEnforcementPackage;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.AuthorizedEnforcementPackageImporter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.AuthorizedEnforcementPackageValidator;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.ClueData;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8AuthorizedEnforcementPackageIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    @TempDir Path packageDirectory;

    @Test void importsReplaysRunsAndWithdrawsAuthorizedLocalPackage() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            cleanupExisting(connection);
            run(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
            run(connection, "db/dm8/V009__enforcement_local_source_v1.sql");
            run(connection, "db/dm8/V013__enforcement_authorized_local_v1.sql");
            run(connection, "db/dm8/V014__enforcement_retention_policy_v1.sql");
            run(connection, "db/dm8/V015__enforcement_rule_effectivity_v1.sql");
        }
        try {
            writePackage();
            var dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
            var importer = new AuthorizedEnforcementPackageImporter(dataSource, mapper, packageDirectory);
            assertEquals(new AuthorizedEnforcementPackageImporter.ImportResult("ENF-AUTH-BATCH-1", 4, true, false),
                    importer.importPackage());
            assertEquals(new AuthorizedEnforcementPackageImporter.ImportResult("ENF-AUTH-BATCH-1", 4, false, true),
                    importer.importPackage());

            var source = new Dm8EnforcementSourceAdapter(dataSource, mapper);
            assertEquals("AUTHORIZED_LOCAL", source.dataMode());
            assertEquals("v1", source.catalogVersion());
            assertEquals("RULE-1", source.activeRule().sourceId());
            assertTrue(source.limitation().contains("人工复核"));
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT effective_from,effectivity_status,effectivity_basis "
                         + "FROM enf_local_rule_release WHERE rule_id='RULE-1'")) {
                result.next();
                assertEquals(null, result.getTimestamp(1));
                assertEquals("NO_EXPLICIT_EFFECTIVE_DATE", result.getString(2));
                assertEquals("official source does not state an effective date", result.getString(3));
            }

            var store = new Dm8EnforcementStore(() -> {
                try { return dataSource.getConnection(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            var service = new EnforcementApplicationService(store, new V0EnforcementAuthorizationAdapter(), source);
            var opened = service.openWork("v0-enforcement-user", "org-v0", "CLUE-1", "v1", "auth-open");
            var processed = service.process("v0-enforcement-user", opened.workItem().workItemId(),
                    List.of("EVIDENCE-1"), "auth-process");
            var reviewed = service.reviewLatestAnalysis("v0-enforcement-reviewer", opened.workItem().workItemId(),
                    1, "CONFIRM", "authorized package review", "auth-review");
            assertEquals("AUTHORIZED_LOCAL", processed.workItem().scopeLabel());
            assertEquals("AUTHORIZED_LOCAL", processed.auxiliaryAnalyses().getFirst().scopeLabel());
            assertEquals("AUTHORIZED_LOCAL_REVIEW", reviewed.reviews().getFirst().scopeLabel());
            assertTrue(reviewed.reviews().getFirst().notOfficialDecision());

            importer.withdrawBatch("ENF-AUTH-BATCH-1", "authorization withdrawn", Instant.parse("2026-09-20T01:00:00Z"));
            assertThrows(IllegalStateException.class, () -> source.findClue("CLUE-1", "v1"));
            assertThrows(IllegalStateException.class, source::activeRule);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM enf_work_item WHERE scope_code='AUTHORIZED_LOCAL'")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                rollbackAndCleanup(connection);
            }
        }
    }

    @Test void importsReplaysReviewsAndWithdrawsTheGeneratedCnipaPackage() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
            cleanupExisting(connection);
            run(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
            run(connection, "db/dm8/V009__enforcement_local_source_v1.sql");
            run(connection, "db/dm8/V013__enforcement_authorized_local_v1.sql");
            run(connection, "db/dm8/V014__enforcement_retention_policy_v1.sql");
            run(connection, "db/dm8/V015__enforcement_rule_effectivity_v1.sql");
        }
        try {
            Path cnipaPackage = Path.of("data", "enforcement", "authorized", "cnipa-official-v1");
            var packageValue = new AuthorizedEnforcementPackageValidator(mapper).load(cnipaPackage);
            var dataSource = new DriverManagerDataSource(URL, USER, PASSWORD);
            var importer = new AuthorizedEnforcementPackageImporter(dataSource, mapper, cnipaPackage);
            assertEquals(new AuthorizedEnforcementPackageImporter.ImportResult(
                    "ENF-CNIPA-OFFICIAL-AUTHORIZED-V1", 3, true, false), importer.importPackage());
            assertEquals(new AuthorizedEnforcementPackageImporter.ImportResult(
                    "ENF-CNIPA-OFFICIAL-AUTHORIZED-V1", 3, false, true), importer.importPackage());

            var source = new Dm8EnforcementSourceAdapter(dataSource, mapper);
            ClueData clue = packageValue.clues().getFirst();
            EvidenceData material = packageValue.evidence().getFirst();
            assertEquals(clue, source.findClue(clue.source().sourceId(), clue.source().version()).orElseThrow());
            assertEquals(material, source.findEvidence(material.source().sourceId()).orElseThrow());
            assertEquals(packageValue.rules().getFirst().source(), source.activeRule());
            assertFalse(source.historicalRightStatus(clue.source().sourceId()).isPresent());

            var store = new Dm8EnforcementStore(() -> {
                try { return dataSource.getConnection(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            var service = new EnforcementApplicationService(store, new V0EnforcementAuthorizationAdapter(), source);
            var opened = service.openWork("v0-enforcement-user", "org-v0", clue.source().sourceId(),
                    clue.source().version(), "cnipa-open");
            var processed = service.process("v0-enforcement-user", opened.workItem().workItemId(),
                    List.of(material.source().sourceId()), "cnipa-process");
            var reviewed = service.reviewLatestAnalysis("v0-enforcement-reviewer", opened.workItem().workItemId(),
                    1, "CONFIRM", "historical public sample reviewed", "cnipa-review");
            assertEquals("AUTHORIZED_LOCAL", processed.workItem().scopeLabel());
            assertEquals("AUTHORIZED_LOCAL_REVIEW", reviewed.reviews().getFirst().scopeLabel());
            assertTrue(reviewed.reviews().getFirst().notOfficialDecision());

            importer.withdrawBatch("ENF-CNIPA-OFFICIAL-AUTHORIZED-V1", "source withdrawn",
                    Instant.parse("2026-09-20T02:00:00Z"));
            assertThrows(IllegalStateException.class,
                    () -> source.findClue(clue.source().sourceId(), clue.source().version()));
            assertThrows(IllegalStateException.class, source::activeRule);
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD)) {
                rollbackAndCleanup(connection);
            }
        }
    }

    private void writePackage() throws Exception {
        SourceLocator clueSource = new SourceLocator("authorized-clue-system", "CLUE-1", "v1", "record:1");
        ClueData clue = new ClueData(clueSource, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        EvidenceData evidence = new EvidenceData(
                new SourceLocator("authorized-evidence-system", "EVIDENCE-1", "v1", "record:1"),
                "controlled://evidence/E-1", "CLUE-1", "collector-1", null, "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z", "AVAILABLE", "identity",
                "SUPPORT", "source states a candidate relationship", null, null);
        String content = "type=REQUIRE_HUMAN_REVIEW";
        var rule = new AuthorizedEnforcementPackage.RuleRecord(
                new SourceLocator("authorized-rule-system", "RULE-1", "v1", "rule:1"), "PUBLISHED",
                "professional-owner", Instant.parse("2026-09-10T00:00:00Z"),
                null, "NO_EXPLICIT_EFFECTIVE_DATE", "official source does not state an effective date",
                "test scope", "DETERMINISTIC_CHECKLIST", content,
                sha(content.getBytes(StandardCharsets.UTF_8)));
        var right = new AuthorizedEnforcementPackage.RightStatusRecord(
                new SourceLocator("authorized-right-system", "RIGHT-1", "v1", "record:1"), "CLUE-1", "AVAILABLE");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("clues.json", mapper.writeValueAsBytes(List.of(clue)));
        files.put("evidence.json", mapper.writeValueAsBytes(List.of(evidence)));
        files.put("rules.json", mapper.writeValueAsBytes(List.of(rule)));
        files.put("right-statuses.json", mapper.writeValueAsBytes(List.of(right)));
        Map<String, AuthorizedEnforcementPackage.FileDeclaration> declarations = new LinkedHashMap<>();
        files.forEach((name, bytes) -> declarations.put(name,
                new AuthorizedEnforcementPackage.FileDeclaration(sha(bytes), 1)));
        var manifest = new AuthorizedEnforcementPackage.Manifest(
                AuthorizedEnforcementPackageValidator.SCHEMA_VERSION, "ENF-AUTH-BATCH-1", "ENF-AUTH-DATASET", "v1",
                "authorized handoff", "AUTHORIZED_LOCAL_HANDOFF", "APPROVAL-1", "project-owner",
                Instant.parse("2026-09-10T00:00:00Z"), "internal enforcement assistance", "one clue and evidence",
                "UNTIL_DEVELOPMENT_PILOT_END", null, "data-owner@example.invalid", "withdraw and disable index",
                false, declarations);
        for (Map.Entry<String, byte[]> file : files.entrySet()) Files.write(packageDirectory.resolve(file.getKey()), file.getValue());
        Files.write(packageDirectory.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));
    }

    private static String sha(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void cleanupExisting(Connection connection) throws Exception {
        executeIgnoring(connection, "DROP INDEX ix_enf_local_rule_release_status");
        executeIgnoring(connection, "DROP TABLE enf_local_rule_release");
        executeIgnoring(connection, "DROP TABLE enf_local_authorization");
        dropLocalSourceTables(connection);
        runIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
    }

    private static void rollbackAndCleanup(Connection connection) throws Exception {
        executeIgnoring(connection, "DELETE FROM enf_local_rule_release");
        executeIgnoring(connection, "DELETE FROM enf_local_authorization");
        run(connection, "db/dm8/U015__enforcement_rule_effectivity_v1.sql");
        run(connection, "db/dm8/U014__enforcement_retention_policy_v1.sql");
        runIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
        executeIgnoring(connection, "DROP INDEX ix_enf_local_rule_release_status");
        executeIgnoring(connection, "DROP TABLE enf_local_rule_release");
        executeIgnoring(connection, "DROP TABLE enf_local_authorization");
        dropLocalSourceTables(connection);
    }

    private static void dropLocalSourceTables(Connection connection) throws Exception {
        executeIgnoring(connection, "DROP TABLE enf_local_right_snapshot");
        executeIgnoring(connection, "DROP TABLE enf_local_rule_snapshot");
        executeIgnoring(connection, "DROP TABLE enf_local_evidence");
        executeIgnoring(connection, "DROP TABLE enf_local_clue");
        executeIgnoring(connection, "DROP TABLE enf_local_source_batch");
    }

    private static void run(Connection connection, String file) throws Exception {
        String script = Files.readString(Path.of(file));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static void runIgnoringMissing(Connection connection, String file) throws Exception {
        try { run(connection, file); } catch (Exception ignored) { }
    }

    private static void executeIgnoring(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        catch (Exception ignored) { }
    }
}
