package cn.xinji.agentplatform.enforcement.legalreference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;
import static org.junit.jupiter.api.Assertions.*;

class LegalReferenceProductTest {
    @TempDir Path temp;
    private ObjectMapper json;
    private JdbcTemplate db;
    private DriverManagerDataSource dataSource;
    private Path catalog;
    private LegalReferenceJdbcStore store;
    private AtomicBoolean granted;
    private AtomicReference<String> targetDigest;

    @BeforeEach void setUp() throws Exception {
        json = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:legal-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(migration("V075__enforcement_v2_legal_reference.sql")));
        }
        db = new JdbcTemplate(dataSource);
        store = new LegalReferenceJdbcStore(db,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), json, Clock.systemUTC());
        catalog = temp.resolve("legal-catalog.json");
        granted = new AtomicBoolean(true);
        targetDigest = new AtomicReference<>("target-v1");
        writeCatalog(List.of(entry("v1", candidate("v1", "原文第一版", List.of()))));
    }

    @Test void sourceCandidateBindingReviewUpdateAndUnbindRemainVersioned() throws Exception {
        var service = service();
        Receipt search = service.search("analyst", "search-key", request("原文"));
        assertEquals(search, service.search("analyst", "search-key", request("原文")));
        SearchResult result = service.readSearch("analyst", search.resultId(), 1);
        assertEquals(SearchState.COMPLETED, result.status());
        assertEquals("REVIEW_REQUIRED", result.members().getFirst().reviewStatus());
        assertEquals("原文第一版", result.members().getFirst().verbatimText());
        assertNotEquals(result.members().getFirst().verbatimText(), result.members().getFirst().explanation());

        Selection selection = new Selection(search.resultId(), 1, "candidate-v1",
                "CONDUCT_REPORT", "report", 1, "analysis.findings[0].basis");
        Receipt bound = service.bind("analyst", "bind-key", selection);
        ReferenceRevision first = service.read("analyst", bound.resultId(), 1);
        assertEquals(ReviewState.PENDING, first.reviewState());
        assertEquals(BindingState.ACTIVE, first.bindingState());
        assertEquals("原文第一版", first.verbatimText());

        Receipt reviewed = service.review("reviewer", "review-key", bound.resultId(),
                new LegalReferenceService.Review(1, "CONFIRMED", "named legal review"));
        assertEquals(2, reviewed.version());
        assertEquals(ReviewState.CONFIRMED, service.read("reviewer", bound.resultId(), 2).reviewState());

        writeCatalog(List.of(entry("v1", candidate("v1", "原文第一版", List.of())),
                entry("v2", candidate("v2", "原文第二版", List.of()))));
        Receipt rechecked = service.recheck("reviewer", "recheck-key", bound.resultId(),
                new LegalReferenceService.Change(2, "periodic source check"));
        ReferenceRevision updated = service.read("reviewer", bound.resultId(), rechecked.version());
        assertEquals(BindingState.NEEDS_REVIEW, updated.bindingState());
        assertEquals(ReviewState.PENDING, updated.reviewState());
        assertEquals("原文第二版", updated.verbatimText());
        assertEquals("原文第一版", service.read("reviewer", bound.resultId(), 1).verbatimText());

        Receipt unbound = service.unbind("reviewer", "unbind-key", bound.resultId(),
                new LegalReferenceService.Change(3, "target no longer needs citation"));
        assertEquals(BindingState.UNBOUND, service.read("reviewer", bound.resultId(), 4).bindingState());
        assertEquals(4, service.history("reviewer", bound.resultId()).size());
        assertEquals(5, count("enf9_legal_receipt"));
        assertEquals(5, count("enf9_legal_event"));
        assertEquals(4, count("enf9_legal_reference"));
        assertEquals(4, unbound.version());
    }

    @Test void targetDriftGrantLossConflictAndUnknownTransitionFailWithoutBusinessWrite() {
        var service = service();
        Receipt search = service.search("analyst", "search-key", request("原文"));
        targetDigest.set("target-v2");
        Selection selection = new Selection(search.resultId(), 1, "candidate-v1",
                "CONDUCT_REPORT", "report", 1, "analysis.findings[0].basis");
        assertThrows(IllegalArgumentException.class,
                () -> service.bind("analyst", "drift-bind", selection));
        assertEquals(0, count("enf9_legal_reference"));
        assertEquals(1, count("enf9_legal_receipt"));

        targetDigest.set("target-v1");
        Receipt bound = service.bind("analyst", "bind-key", selection);
        granted.set(false);
        assertThrows(SecurityException.class, () -> service.review("reviewer", "denied-review",
                bound.resultId(), new LegalReferenceService.Review(1, "CONFIRMED", "review")));
        assertEquals(1, count("enf9_legal_reference"));
        assertEquals(2, count("enf9_legal_receipt"));
        granted.set(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.bind("analyst", "bind-key", new Selection(search.resultId(), 1,
                        "different", "CONDUCT_REPORT", "report", 1, "analysis.findings[0].basis")));
        assertEquals(2, count("enf9_legal_receipt"));
    }

    @Test void noMatchAndSourceFailureAreDistinctAndNeverCreateReference() throws Exception {
        var service = service();
        Receipt empty = service.search("analyst", "empty-key", request("不存在"));
        SearchResult noMatch = service.readSearch("analyst", empty.resultId(), 1);
        assertEquals(SearchState.COMPLETED, noMatch.status());
        assertTrue(noMatch.members().isEmpty());
        assertTrue(noMatch.failedSources().isEmpty());
        writeCatalog(List.of(new FileLegalKnowledgeSource.Entry("source", 2, "v2", "UNAVAILABLE",
                "UPSTREAM_503", List.of())));
        Receipt failed = service.search("analyst", "failed-key", request("原文"));
        SearchResult unavailable = service.readSearch("analyst", failed.resultId(), 1);
        assertEquals(SearchState.FAILED, unavailable.status());
        assertEquals(List.of("source:UPSTREAM_503"), unavailable.failedSources());
        assertEquals(0, count("enf9_legal_reference"));
    }

    @Test void migrationRoundTripsWithoutLeavingLegalTables() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(migration("U075__enforcement_v2_legal_reference.sql")));
        }
        Integer remaining = db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME LIKE 'ENF9_LEGAL_%'", Integer.class);
        assertEquals(0, remaining);
    }

    private LegalReferenceService service() {
        return new LegalReferenceService(store, new FileLegalKnowledgeSource(catalog, json),
                (actor, org, type, id, version, action, purpose) -> {
                    if (!granted.get()) throw new SecurityException("Current grant lost");
                }, (actor, org, type, id, version, anchor, purpose) -> {
                    if (!granted.get()) throw new SecurityException("Current target grant lost");
                    return targetDigest.get();
                });
    }

    private SearchRequest request(String query) {
        return new SearchRequest("org", "CONDUCT_REPORT", "report", 1,
                "analysis.findings[0].basis", "case", "PROJECT_DOCUMENT_REVIEW", "PROJECT_TEST",
                Instant.parse("2026-09-29T01:00:00Z"), query, List.of("source"));
    }

    private Candidate candidate(String version, String text, List<String> gaps) {
        long revision = Long.parseLong(version.substring(1));
        return new Candidate("candidate-" + version, "source", revision, version, "article-1", "项目法规",
                "第十条", text, LegalReferenceService.hash(text), "VERIFIED", "CLEAR",
                "项目测试解释，不是逐字原文", gaps, "REVIEW_REQUIRED");
    }

    private FileLegalKnowledgeSource.Entry entry(String version, Candidate candidate) {
        return new FileLegalKnowledgeSource.Entry("source", candidate.sourceRevision(), version,
                "AVAILABLE", null, List.of(candidate));
    }

    private void writeCatalog(List<FileLegalKnowledgeSource.Entry> entries) throws Exception {
        json.writeValue(catalog.toFile(), new FileLegalKnowledgeSource.Catalog(entries));
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static Path migration(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("db/dm8").resolve(name)))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Migration unavailable: " + name);
        return root.resolve("db/dm8").resolve(name);
    }
}
