package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8PublicAdjudicationReferenceCatalogAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationReferenceImporter;
import cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationSourceApprovalRegistry;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.Reference;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.SourceLocator;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.SourceSnapshotTrace;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.SourceTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dm8-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Dm8PublicAdjudicationReferenceIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private static final String RETENTION = "UNTIL_DEVELOPMENT_PILOT_END";
    private static final String FORBIDDEN = "EXTERNAL_MODEL_PROCESSING,PRODUCTION_USE,FORMAL_ADJUDICATION_INPUT";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PublicAdjudicationReferenceImporter.SourceRegistration> APPROVED = Set.of(
            source("ADJ-LAW-SOURCE", "PUBLIC_WEB_API", "PUBLIC_LEGAL_REFERENCE", "ADJ-LAW-APPROVAL", "1".repeat(64)),
            source("ADJ-JUDGMENT-SOURCE", "PUBLIC_WEB_PAGE", "PUBLIC_JUDGMENT_REFERENCE", "ADJ-JUDGMENT-APPROVAL", "2".repeat(64)),
            source("ADJ-TEMPLATE-SOURCE", "AUTHORIZED_LOCAL_FILE", "AUTHORIZED_TEMPLATE_REFERENCE", "ADJ-TEMPLATE-APPROVAL", "3".repeat(64)));

    @Autowired MockMvc http;
    @Autowired PublicAdjudicationReferenceCatalogPort catalog;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
        registry.add("internal-workbench.development-sources-enabled", () -> "true");
        registry.add("adjudication.source", () -> "database");
        registry.add("adjudication.storage", () -> "memory");
        registry.add("enforcement.source", () -> "database");
        registry.add("enforcement.storage", () -> "dm8");
        registry.add("consultation.storage", () -> "memory");
        registry.add("consultation.search.provider", () -> "task");
        registry.add("patent-search.catalog", () -> "public-local");
        registry.add("knowledge-base.provider", () -> "dm8");
    }

    @TestConfiguration
    static class ApprovalConfiguration {
        @Bean @Primary
        PublicAdjudicationSourceApprovalRegistry testApprovals() { return APPROVED::contains; }
    }

    @BeforeAll
    static void initialize() throws Exception {
        Assumptions.assumeTrue(URL != null && USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U012__adjudication_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U010__adjudication_local_source_v1.sql");
            run(c, "db/dm8/V010__adjudication_local_source_v1.sql");
            run(c, "db/dm8/V012__adjudication_public_reference_catalog_v1.sql");
        }
    }

    @AfterAll
    static void rollback() throws Exception {
        if (URL == null || USER == null || PASSWORD == null) return;
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U012__adjudication_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U010__adjudication_local_source_v1.sql");
        }
    }

    @Test @Order(1)
    void defaultsToDenyAndProtectsTheInternalEndpoint() throws Exception {
        var rejected = source("CALLER-DECLARED", "PUBLIC_WEB_API", "PUBLIC_LEGAL_REFERENCE", "CALLER", "4".repeat(64));
        var failure = assertThrows(IllegalStateException.class, () -> importer().importApprovedBatch(
                rejected, batch("rejected", "a".repeat(64)), List.of(reference(rejected, "REF-X", "PUBLIC_LEGAL_REFERENCE"))));
        assertEquals("SOURCE_NOT_ALLOWED", failure.getMessage());
        http.perform(get("/api/v0/development-sources/adjudication/public-references")).andExpect(status().isNotFound());
        http.perform(get("/api/v0/development-sources/adjudication/public-references").header("X-V0-Actor", "unknown"))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v0/development-sources/adjudication/public-references").header("X-V0-Actor", "v0-adjudication-user"))
                .andExpect(status().isNotFound());
    }

    @Test @Order(2)
    void importsThreeIsolatedCategoriesWithTraceAndCrossBatchIdempotency() throws Exception {
        int[] before = businessCounts();
        int imported = 0;
        for (var source : APPROVED) {
            String category = source.allowedCategories();
            Reference record = reference(source, "REF-" + category, category);
            imported += importer().importApprovedBatch(source, batch("BATCH-" + category, categorySha(category)), List.of(record)).importedRecords();
        }
        assertEquals(3, imported);
        var legal = APPROVED.stream().filter(v -> v.allowedCategories().equals("PUBLIC_LEGAL_REFERENCE")).findFirst().orElseThrow();
        assertEquals(new PublicAdjudicationReferenceImporter.ImportResult(0, false), importer().importApprovedBatch(
                legal, batch("LEGAL-REPLAY", "b".repeat(64)), List.of(reference(legal, "REF-PUBLIC_LEGAL_REFERENCE", "PUBLIC_LEGAL_REFERENCE"))));
        assertEquals(3, catalog.list().size());
        assertArrayEquals(before, businessCounts());
        http.perform(get("/api/v0/development-sources/adjudication/public-references")
                        .header("X-V0-Actor", "v0-adjudication-reviewer"))
                .andExpect(status().isNotFound());
    }

    @Test @Order(3)
    void detectsConflictAndCorruptionThenAppliesWithdrawalAndRevocation() throws Exception {
        var legal = APPROVED.stream().filter(v -> v.allowedCategories().equals("PUBLIC_LEGAL_REFERENCE")).findFirst().orElseThrow();
        Reference original = reference(legal, "REF-PUBLIC_LEGAL_REFERENCE", "PUBLIC_LEGAL_REFERENCE");
        Reference changed = new Reference(original.referenceId(), original.category(), original.source(), "changed", original.publicationDate(),
                original.institution(), original.documentType(), original.missingFields(), original.availability(), original.usageLimitation(),
                original.sourceUrl(), original.capturedAt(), original.dataMode(), original.authorityMarker(), original.professionalUse(),
                original.externalModelAllowed(), original.importDecision(), original.sourceTrace(), original.sourcePayloadSha256(),
                original.approvalId(), original.approvalSha256(), original.retentionPeriod(), original.attribution());
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class, () -> importer().importApprovedBatch(
                legal, batch("LEGAL-CONFLICT", "c".repeat(64)), List.of(changed))).getMessage());

        importer().withdraw("REF-PUBLIC_JUDGMENT_REFERENCE", "v1", "source withdrawn", Instant.parse("2026-09-19T03:00:00Z"));
        assertEquals(2, catalog.list().size());
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE adj_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, "f".repeat(64)); s.setString(2, original.referenceId()); s.executeUpdate();
        }
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE adj_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, sha256(JSON.writeValueAsString(original))); s.setString(2, original.referenceId()); s.executeUpdate();
        }
        importer().revokeSource(legal.sourceId(), "approval withdrawn", Instant.parse("2026-09-19T04:00:00Z"));
        assertEquals("SOURCE_NOT_ALLOWED", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        http.perform(get("/api/v0/development-sources/adjudication/public-references")
                        .header("X-V0-Actor", "v0-adjudication-user"))
                .andExpect(status().isNotFound());
    }

    private static Reference reference(PublicAdjudicationReferenceImporter.SourceRegistration source, String id, String category) {
        String raw = categorySha(category);
        var trace = new SourceTrace("fixture-dataset", "d".repeat(64), "e".repeat(64), List.of("raw-1"),
                List.of(new SourceSnapshotTrace("raw-1", raw, "controlled://fixtures/" + category + ".json")));
        return new Reference(id, category, new SourceLocator(source.sourceId(), id, "v1", "record:" + id),
                "Development fixture " + category, "2026-09-19", "fixture institution", "fixture document", List.of(),
                "AVAILABLE", "INTERNAL_DEVELOPMENT_ONLY", source.url(), "2026-09-19T00:00:00Z", "PUBLIC_PILOT",
                "NOT_CENTER_SOURCE", "NOT_PROFESSIONAL_EVIDENCE", false, "APPROVED_FOR_INTERNAL_DEVELOPMENT", trace,
                "9".repeat(64), source.approvalId(), source.approvalSha256(), source.retentionPeriod(), source.attribution());
    }

    private static PublicAdjudicationReferenceImporter.SourceRegistration source(
            String id, String access, String category, String approvalId, String approvalSha) {
        return new PublicAdjudicationReferenceImporter.SourceRegistration(id, "Development fixture " + id,
                "controlled://fixtures/" + id, access, "No network; test fixture only", "TEST_DATA_RESPONSIBLE_PARTY",
                "INTERNAL_DEVELOPMENT_PILOT,LOCAL_DM8_STORAGE", category, FORBIDDEN, false, "APPROVED",
                Instant.parse("2026-09-19T00:00:00Z"), approvalId, approvalSha, RETENTION, "Development test fixture; no external attribution");
    }
    private static PublicAdjudicationReferenceImporter.Batch batch(String id, String rawSha) {
        return new PublicAdjudicationReferenceImporter.Batch(id, "test-v1", "controlled test fixture", rawSha,
                "controlled://fixtures/" + id, Instant.parse("2026-09-19T01:00:00Z"));
    }
    private PublicAdjudicationReferenceImporter importer() { return new PublicAdjudicationReferenceImporter(dataSource(), JSON, APPROVED::contains); }
    private static DriverManagerDataSource dataSource() { return new DriverManagerDataSource(URL, USER, PASSWORD); }
    private static int[] businessCounts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            return new int[]{count(c, "adj_local_case"), count(c, "adj_local_material"),
                    count(c, "adj_local_legal_snapshot"), count(c, "adj_local_template_snapshot")};
        }
    }
    private static String categorySha(String category) { try { return sha256(category); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static int count(Connection c, String table) throws Exception { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(r.next()); return r.getInt(1); } }
    private static void run(Connection c, String file) throws Exception { try (Statement s = c.createStatement()) { for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) s.execute(sql); } }
    private static void runIgnoringMissing(Connection c, String file) throws Exception { try { run(c, file); } catch (Exception ignored) { } }
}
