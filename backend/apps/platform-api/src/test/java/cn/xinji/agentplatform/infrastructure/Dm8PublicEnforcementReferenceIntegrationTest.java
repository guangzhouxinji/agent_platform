package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.*;
import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dm8-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8PublicEnforcementReferenceIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    @Autowired PublicEnforcementReferenceCatalogPort catalog;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("enforcement.storage", () -> "dm8");
        registry.add("enforcement.source", () -> "database");
        registry.add("internal-workbench.development-sources-enabled", () -> "true");
        registry.add("consultation.storage", () -> "memory");
        registry.add("consultation.search.provider", () -> "task");
        registry.add("patent-search.catalog", () -> "public-local");
        registry.add("knowledge-base.provider", () -> "dm8");
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "dm.jdbc.driver.DmDriver");
    }

    @BeforeAll void initializeSchema() throws Exception {
        Assumptions.assumeTrue(URL != null && USER != null && PASSWORD != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U009__enforcement_local_source_v1.sql");
            runIgnoringMissing(c, "db/dm8/U011__enforcement_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U002__enforcement_mvp_v0.sql");
            run(c, "db/dm8/V002__enforcement_mvp_v0.sql");
            run(c, "db/dm8/V009__enforcement_local_source_v1.sql");
            run(c, "db/dm8/V011__enforcement_public_reference_catalog_v1.sql");
            run(c, "db/dm8/V016__enforcement_public_historical_case_category_v1.sql");
        }
        new EnforcementLocalSourceImporter(dataSource(), json).importSyntheticBaseline();
    }

    @AfterAll void rollbackSchema() throws Exception {
        if (URL == null || USER == null || PASSWORD == null) return;
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U009__enforcement_local_source_v1.sql");
            runIgnoringMissing(c, "db/dm8/U016__enforcement_public_historical_case_category_v1.sql");
            runIgnoringMissing(c, "db/dm8/U011__enforcement_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U002__enforcement_mvp_v0.sql");
        }
    }

    @Test @Order(1)
    void rejectsCallerDeclaredApprovalAndProtectsTheReadEndpoint() throws Exception {
        var importer = importer();
        var falseApproval = new PublicEnforcementReferenceImporter.SourceRegistration(
                "CALLER_DECLARED", "Unapproved", "https://example.invalid", "PUBLIC_WEB_API", "test", "test-owner",
                PublicEnforcementReferenceImporter.REQUIRED_ALLOWED_USES, PublicEnforcementReferenceImporter.REQUIRED_FORBIDDEN_USES,
                false, "APPROVED", Instant.parse("2026-09-19T00:00:00Z"), PublicEnforcementReferenceImporter.APPROVAL_ID,
                PublicEnforcementReferenceImporter.APPROVAL_SHA256, PublicEnforcementReferenceImporter.RETENTION_PERIOD,
                PublicEnforcementReferenceImporter.REQUIRED_ATTRIBUTION);
        var batch = new PublicEnforcementReferenceImporter.Batch("false-approval", "test", "test", "0".repeat(64),
                "controlled://test", Instant.parse("2026-09-19T00:00:00Z"));
        var reference = new PublicEnforcementReferenceCatalogPort.Reference("ref", "PUBLIC_LEGAL_REFERENCE",
                new SourceLocator("CALLER_DECLARED", "law", "v1", "article:1"), "Law", "2026-01-01", null,
                List.of("authoritativeStatus"), "AVAILABLE", "INTERNAL_DEVELOPMENT_PILOT_ONLY");
        assertEquals("SOURCE_NOT_ALLOWED", assertThrows(IllegalStateException.class,
                () -> importer.importApprovedBatch(falseApproval, batch, List.of(reference))).getMessage());
        http.perform(get("/api/v0/development-sources/enforcement/public-references")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v0/development-sources/enforcement/public-references").header("X-V0-Actor", "unknown"))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v0/development-sources/enforcement/public-references").header("X-V0-Actor", "v0-enforcement-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test @Order(2)
    void importsTheApprovedSetAndPreservesTraceThroughDm8AndHttp() throws Exception {
        var records = loadRecords();
        var manifest = json.readTree(Files.readString(Path.of("data/enforcement/public-reference-pilot-v1/manifest.json")));
        var batch = new PublicEnforcementReferenceImporter.Batch("ENF-SURECHEMBL-PUBLIC-REFERENCE-V1", "public-reference-pilot-v1",
                "400 approved public patent publication metadata records", manifest.path("recordsSha256").asText(),
                "controlled://data/enforcement/public-reference-pilot-v1/records.json", Instant.parse("2026-09-19T00:00:00Z"));
        assertEquals(new PublicEnforcementReferenceImporter.ImportResult(400, true), importer().importApprovedBatch(approvedSource(), batch, records));
        assertEquals(new PublicEnforcementReferenceImporter.ImportResult(0, false), importer().importApprovedBatch(approvedSource(), batch, records));
        var historicalRecords = loadHistoricalRecords();
        var historicalManifest = json.readTree(Files.readString(Path.of("data/enforcement/public-historical-cases-cnipa-v1/manifest.json")));
        var historicalBatch = new PublicEnforcementReferenceImporter.Batch("ENF-CNIPA-PUBLIC-HISTORICAL-CASES-V1",
                "cnipa-public-historical-cases-v1", "33 approved CNIPA public historical case samples",
                historicalManifest.path("recordsSha256").asText(),
                "controlled://data/enforcement/public-historical-cases-cnipa-v1/records.json", Instant.parse("2026-09-20T00:00:00Z"));
        assertEquals(new PublicEnforcementReferenceImporter.ImportResult(33, true),
                importer().importApprovedBatch(approvedCnipaSource(), historicalBatch, historicalRecords));
        assertEquals(new PublicEnforcementReferenceImporter.ImportResult(0, false),
                importer().importApprovedBatch(approvedCnipaSource(), historicalBatch, historicalRecords));
        assertEquals(433, catalog.list().size());
        assertEquals(33, catalog.list().stream().filter(item -> "PUBLIC_HISTORICAL_CASE_SAMPLE".equals(item.category())).count());
        assertTrue(catalog.list().stream().filter(item -> "PUBLIC_HISTORICAL_CASE_SAMPLE".equals(item.category()))
                .allMatch(item -> !item.businessWorkflowInputAllowed() && item.sourceStatusLabel() == null
                        && item.historicalCaseSummary().startsWith("国家知识产权局公开历史案例记载：")));
        try (Connection c = dataSource().getConnection()) {
            assertEquals(433, scalarInt(c, "SELECT COUNT(*) FROM enf_public_reference_snapshot"));
            assertEquals(records.getFirst().sourceTrace().sourceSnapshots().getLast().rawResponseSha256(),
                    scalarText(c, "SELECT raw_response_sha256 FROM enf_public_reference_snapshot WHERE reference_id='" + records.getFirst().referenceId() + "'"));
        }
        http.perform(get("/api/v0/development-sources/enforcement/public-references").header("X-V0-Actor", "v0-enforcement-reviewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(433))
                .andExpect(jsonPath("$.items[0].sourceTrace.sourceSnapshots[0].rawResponseSha256").isNotEmpty())
                .andExpect(jsonPath("$.items[0].businessWorkflowInputAllowed").value(false));
    }

    @Test @Order(3)
    void enforcesConflictReplayWithdrawalRevocationIntegrityAndIsolation() throws Exception {
        var importer = importer();
        var records = new ArrayList<>(catalog.list().stream()
                .filter(item -> PublicEnforcementReferenceImporter.APPROVED_SOURCE_ID.equals(item.source().sourceSystem()))
                .toList());
        var first = records.getFirst();
        var replay = new PublicEnforcementReferenceImporter.Batch("ENF-SURECHEMBL-REPLAY", "v1", "same records",
                "0".repeat(64), "controlled://replay", Instant.parse("2026-09-19T01:00:00Z"));
        assertEquals(new PublicEnforcementReferenceImporter.ImportResult(0, false),
                importer.importApprovedBatch(approvedSource(), replay, records));
        records.set(0, withTitle(first, first.title() + " changed"));
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class,
                () -> importer.importApprovedBatch(approvedSource(), replay, records)).getMessage());

        var businessTables = List.of("enf_local_clue", "enf_local_evidence", "enf_local_rule_snapshot", "enf_local_right_snapshot", "enf_work_item");
        var before = new ArrayList<Integer>();
        try (Connection c = dataSource().getConnection()) { for (String table : businessTables) before.add(count(c, table)); }
        String payload = scalarText(dataSource(), "SELECT payload_json FROM enf_public_reference_snapshot WHERE reference_id='" + first.referenceId() + "'");
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE enf_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, "f".repeat(64)); s.setString(2, first.referenceId()); s.executeUpdate();
        }
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE enf_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, sha256(payload)); s.setString(2, first.referenceId()); s.executeUpdate();
        }
        DataSource unavailable = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("unavailable"); }
            @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLException("unavailable"); }
        };
        assertEquals("UPSTREAM_FAILURE", assertThrows(IllegalStateException.class,
                () -> new Dm8PublicEnforcementReferenceCatalogAdapter(unavailable, json).list()).getMessage());
        importer.withdraw(first.referenceId(), first.source().version(), "source withdrawn", Instant.parse("2026-09-19T02:00:00Z"));
        assertEquals(432, catalog.list().size());
        try (Connection c = dataSource().getConnection()) {
            assertEquals(433, count(c, "enf_public_reference_snapshot"));
            for (int i = 0; i < businessTables.size(); i++) assertEquals(before.get(i), count(c, businessTables.get(i)));
        }
        importer.revokeSource(PublicEnforcementReferenceImporter.APPROVED_SOURCE_ID, "authorization withdrawn", Instant.parse("2026-09-19T03:00:00Z"));
        assertEquals("SOURCE_NOT_ALLOWED", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        http.perform(get("/api/v0/development-sources/enforcement/public-references").header("X-V0-Actor", "v0-enforcement-user"))
                .andExpect(status().isForbidden());
    }

    private List<PublicEnforcementReferenceCatalogPort.Reference> loadRecords() throws Exception {
        JsonNode root = json.readTree(Files.readString(Path.of("data/enforcement/public-reference-pilot-v1/records.json")));
        var result = new ArrayList<PublicEnforcementReferenceCatalogPort.Reference>();
        for (JsonNode node : root.path("records")) result.add(referenceFrom(node));
        return result;
    }

    private List<PublicEnforcementReferenceCatalogPort.Reference> loadHistoricalRecords() throws Exception {
        JsonNode root = json.readTree(Files.readString(Path.of("data/enforcement/public-historical-cases-cnipa-v1/records.json")));
        var result = new ArrayList<PublicEnforcementReferenceCatalogPort.Reference>();
        for (JsonNode node : root.path("records")) {
            JsonNode trace = node.path("sourceTrace");
            result.add(new PublicEnforcementReferenceCatalogPort.Reference(node.path("sourceSnapshotId").asText(), node.path("category").asText(),
                    new SourceLocator(node.path("sourceSystem").asText(), node.path("stableSourceId").asText(), node.path("sourceVersion").asText(), node.path("sourceLocator").asText()),
                    node.path("title").asText(), node.path("publicationDate").asText(), null, strings(node.path("missingFields")),
                    node.path("availability").asText(), node.path("usageLimitation").asText(), node.path("sourceUrl").asText(), node.path("capturedAt").asText(),
                    node.path("dataMode").asText(), node.path("authorityMarker").asText(), node.path("professionalUse").asText(), false,
                    node.path("importDecision").asText(), new PublicEnforcementReferenceCatalogPort.SourceTrace(trace.path("sourceDatasetId").asText(),
                    trace.path("sourceRecordsSha256").asText(), trace.path("sourceRecordSha256").asText(), strings(trace.path("sourceSnapshotRefs")),
                    json.convertValue(trace.path("sourceSnapshots"), json.getTypeFactory().constructCollectionType(List.class, PublicEnforcementReferenceCatalogPort.SourceSnapshotTrace.class))),
                    node.path("payloadSha256").asText(), node.path("approvalId").asText(), node.path("approvalSha256").asText(),
                    node.path("retentionPeriod").asText(), node.path("attribution").asText(), node.path("businessCategory").asText(),
                    node.path("sourcePageStart").asInt(), node.path("sourcePageEnd").asInt(), node.path("historicalCaseSummary").asText(),
                    node.path("titleExtractionNote").isNull() ? null : node.path("titleExtractionNote").asText(), false));
        }
        return result;
    }

    private PublicEnforcementReferenceCatalogPort.Reference referenceFrom(JsonNode node) {
        JsonNode trace = node.path("sourceTrace");
        return new PublicEnforcementReferenceCatalogPort.Reference(node.path("sourceSnapshotId").asText(), node.path("category").asText(),
                new SourceLocator(node.path("sourceSystem").asText(), node.path("stableSourceId").asText(), node.path("sourceVersion").asText(), node.path("sourceLocator").asText()),
                node.path("title").asText(), node.path("publicationDate").asText(), null, strings(node.path("missingFields")),
                node.path("availability").asText(), node.path("usageLimitation").asText(), node.path("sourceUrl").asText(), node.path("capturedAt").asText(),
                node.path("dataMode").asText(), node.path("authorityMarker").asText(), node.path("professionalUse").asText(), false,
                node.path("importDecision").asText(), new PublicEnforcementReferenceCatalogPort.SourceTrace(trace.path("sourceDatasetId").asText(),
                trace.path("sourceRecordsSha256").asText(), trace.path("sourceRecordSha256").asText(), strings(trace.path("sourceSnapshotRefs")),
                json.convertValue(trace.path("sourceSnapshots"), json.getTypeFactory().constructCollectionType(List.class, PublicEnforcementReferenceCatalogPort.SourceSnapshotTrace.class))),
                node.path("payloadSha256").asText(), PublicEnforcementReferenceImporter.APPROVAL_ID, PublicEnforcementReferenceImporter.APPROVAL_SHA256,
                PublicEnforcementReferenceImporter.RETENTION_PERIOD, PublicEnforcementReferenceImporter.REQUIRED_ATTRIBUTION);
    }

    private List<String> strings(JsonNode node) { return json.convertValue(node, json.getTypeFactory().constructCollectionType(List.class, String.class)); }
    private PublicEnforcementReferenceImporter importer() { return new PublicEnforcementReferenceImporter(dataSource(), json); }
    private static DriverManagerDataSource dataSource() { return new DriverManagerDataSource(URL, USER, PASSWORD); }
    private static PublicEnforcementReferenceImporter.SourceRegistration approvedSource() {
        return new PublicEnforcementReferenceImporter.SourceRegistration(PublicEnforcementReferenceImporter.APPROVED_SOURCE_ID, "EMBL-EBI SureChEMBL",
                "https://surechembl.org/api", "PUBLIC_WEB_API", "Internal development only; retention until development pilot end; CC BY 4.0 attribution required",
                "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY", PublicEnforcementReferenceImporter.REQUIRED_ALLOWED_USES,
                PublicEnforcementReferenceImporter.REQUIRED_FORBIDDEN_USES, false, "APPROVED", Instant.parse("2026-09-19T00:00:00Z"),
                PublicEnforcementReferenceImporter.APPROVAL_ID, PublicEnforcementReferenceImporter.APPROVAL_SHA256,
                PublicEnforcementReferenceImporter.RETENTION_PERIOD, PublicEnforcementReferenceImporter.REQUIRED_ATTRIBUTION);
    }
    private static PublicEnforcementReferenceImporter.SourceRegistration approvedCnipaSource() {
        return new PublicEnforcementReferenceImporter.SourceRegistration(PublicEnforcementReferenceImporter.CNIPA_SOURCE_ID,
                "国家知识产权局公开行政保护案例", "https://www.cnipa.gov.cn", "APPROVED_LOCAL_SNAPSHOT",
                "Internal development only; historical reference; no workflow input", "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY",
                PublicEnforcementReferenceImporter.REQUIRED_ALLOWED_USES, PublicEnforcementReferenceImporter.REQUIRED_FORBIDDEN_USES,
                false, "APPROVED", Instant.parse("2026-09-20T00:00:00Z"), PublicEnforcementReferenceImporter.CNIPA_APPROVAL_ID,
                PublicEnforcementReferenceImporter.CNIPA_APPROVAL_SHA256, PublicEnforcementReferenceImporter.RETENTION_PERIOD,
                PublicEnforcementReferenceImporter.CNIPA_ATTRIBUTION);
    }
    private static PublicEnforcementReferenceCatalogPort.Reference withTitle(PublicEnforcementReferenceCatalogPort.Reference v, String title) {
        return new PublicEnforcementReferenceCatalogPort.Reference(v.referenceId(), v.category(), v.source(), title, v.publicationDate(), v.sourceStatusLabel(),
                v.missingFields(), v.availability(), v.usageLimitation(), v.sourceUrl(), v.capturedAt(), v.dataMode(), v.authorityMarker(), v.professionalUse(),
                v.externalModelAllowed(), v.importDecision(), v.sourceTrace(), v.sourcePayloadSha256(), v.approvalId(), v.approvalSha256(), v.retentionPeriod(), v.attribution());
    }
    private static void run(Connection c, String file) throws Exception { try (Statement s = c.createStatement()) { for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) s.execute(sql); } }
    private static void runIgnoringMissing(Connection c, String file) throws Exception { try { run(c, file); } catch (Exception ignored) { } }
    private static int count(Connection c, String table) throws Exception { return scalarInt(c, "SELECT COUNT(*) FROM " + table); }
    private static int scalarInt(Connection c, String sql) throws Exception { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getInt(1); } }
    private static String scalarText(Connection c, String sql) throws Exception { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); } }
    private static String scalarText(DataSource ds, String sql) throws Exception { try (Connection c = ds.getConnection()) { return scalarText(c, sql); } }
    private static String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}
