package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationReferenceImporter;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dm8-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8ApprovedPublicAdjudicationReferenceIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    @Autowired PublicAdjudicationReferenceCatalogPort catalog;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

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

    @BeforeAll void initialize() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U012__adjudication_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U010__adjudication_local_source_v1.sql");
            run(c, "db/dm8/V010__adjudication_local_source_v1.sql");
            run(c, "db/dm8/V012__adjudication_public_reference_catalog_v1.sql");
        }
    }

    @AfterAll void rollback() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            runIgnoringMissing(c, "db/dm8/U012__adjudication_public_reference_catalog_v1.sql");
            runIgnoringMissing(c, "db/dm8/U010__adjudication_local_source_v1.sql");
        }
    }

    @Test @Order(1)
    void importsApprovedSetReplaysAndReadsThroughHttp() throws Exception {
        int[] businessBefore = businessCounts();
        List<Reference> records = loadRecords();
        JsonNode manifest = json.readTree(Files.readString(Path.of("data/adjudication/public-reference-pilot-v1/manifest.json")));
        var batch = new PublicAdjudicationReferenceImporter.Batch("ADJ-SPC-IP-COURT-PUBLIC-REFERENCE-V1",
                "public-reference-pilot-v1", "100 approved public judgment locator records",
                manifest.path("recordsSha256").asText(),
                "controlled://data/adjudication/public-reference-pilot-v1/records.json",
                Instant.parse("2026-09-20T00:00:00Z"));
        var importer = importer();
        assertEquals(new PublicAdjudicationReferenceImporter.ImportResult(100, true),
                importer.importApprovedBatch(approvedSource(), batch, records));
        assertEquals(new PublicAdjudicationReferenceImporter.ImportResult(0, false),
                importer.importApprovedBatch(approvedSource(), batch, records));
        assertEquals(100, catalog.list().size());
        assertArrayEquals(businessBefore, businessCounts());
        try (Connection c = dataSource().getConnection()) {
            assertEquals(100, count(c, "adj_public_reference_snapshot"));
            assertEquals(records.getFirst().sourceTrace().sourceSnapshots().getLast().rawResponseSha256(),
                    scalarText(c, "SELECT raw_response_sha256 FROM adj_public_reference_snapshot WHERE reference_id='"
                            + records.getFirst().referenceId() + "'"));
        }
        http.perform(get("/api/v0/development-sources/adjudication/public-references")
                        .header("X-V0-Actor", "v0-adjudication-reviewer"))
                .andExpect(status().isNotFound());
    }

    @Test @Order(2)
    void rejectsUnregisteredSourceConflictAndCorruption() throws Exception {
        var approved = approvedSource();
        var falseSource = new PublicAdjudicationReferenceImporter.SourceRegistration(
                "CALLER_DECLARED", approved.name(), approved.url(), approved.accessMode(), approved.restrictions(),
                approved.ownerRef(), approved.allowedUses(), approved.allowedCategories(), approved.forbiddenUses(),
                false, approved.reviewStatus(), approved.reviewedAt(), approved.approvalId(), approved.approvalSha256(),
                approved.retentionPeriod(), approved.attribution());
        List<Reference> records = new ArrayList<>(catalog.list());
        assertEquals("SOURCE_NOT_ALLOWED", assertThrows(IllegalStateException.class, () -> importer().importApprovedBatch(
                falseSource, replayBatch("FALSE-SOURCE"), records)).getMessage());
        Reference first = records.getFirst();
        records.set(0, withTitle(first, first.title() + " changed"));
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class, () -> importer().importApprovedBatch(
                approved, replayBatch("CONFLICT"), records)).getMessage());
        String payload = scalarText(dataSource(), "SELECT payload_json FROM adj_public_reference_snapshot WHERE reference_id='"
                + first.referenceId() + "'");
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE adj_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, "f".repeat(64)); s.setString(2, first.referenceId()); s.executeUpdate();
        }
        assertEquals("SOURCE_INTEGRITY_FAILURE", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        try (Connection c = dataSource().getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE adj_public_reference_snapshot SET payload_sha256=? WHERE reference_id=?")) {
            s.setString(1, sha256(payload)); s.setString(2, first.referenceId()); s.executeUpdate();
        }
    }

    @Test @Order(3)
    void withdrawsAndRevokesWithoutBusinessWrites() throws Exception {
        int[] before = businessCounts();
        List<Reference> available = catalog.list();
        Reference first = available.getFirst();
        assertEquals("SOURCE_NOT_FOUND", assertThrows(IllegalStateException.class, () -> importer().withdraw(
                "missing", "missing", "source withdrawn", Instant.parse("2026-09-20T00:59:00Z"))).getMessage());
        importer().withdraw(first.referenceId(), first.source().version(), "source withdrawn",
                Instant.parse("2026-09-20T01:00:00Z"));
        assertEquals(99, catalog.list().size());
        for (Reference reference : available.subList(1, available.size())) importer().withdraw(
                reference.referenceId(), reference.source().version(), "source withdrawn", Instant.parse("2026-09-20T01:00:00Z"));
        assertEquals("SOURCE_UNAVAILABLE", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        assertArrayEquals(before, businessCounts());
        importer().revokeSource(PublicAdjudicationReferenceImporter.APPROVED_SOURCE_ID, "authorization withdrawn",
                Instant.parse("2026-09-20T02:00:00Z"));
        assertEquals("SOURCE_NOT_ALLOWED", assertThrows(IllegalStateException.class, catalog::list).getMessage());
        http.perform(get("/api/v0/development-sources/adjudication/public-references")
                        .header("X-V0-Actor", "v0-adjudication-user"))
                .andExpect(status().isNotFound());
        assertArrayEquals(before, businessCounts());
    }

    @Test @Order(4)
    void reportsStorageFailureWithoutReturningAnEmptyCatalog() {
        DataSource unavailable = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException("unavailable"); }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("unavailable"); }
        };
        var reader = new cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8PublicAdjudicationReferenceCatalogAdapter(
                unavailable, json,
                cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationSourceApprovalRegistry.approvedPublicJudgmentPilot());
        assertEquals("UPSTREAM_FAILURE", assertThrows(IllegalStateException.class, reader::list).getMessage());
    }

    private List<Reference> loadRecords() throws Exception {
        JsonNode root = json.readTree(Files.readString(Path.of("data/adjudication/public-reference-pilot-v1/records.json")));
        List<Reference> records = new ArrayList<>();
        for (JsonNode node : root.path("records")) {
            JsonNode trace = node.path("sourceTrace");
            records.add(new Reference(node.path("sourceSnapshotId").asText(), node.path("category").asText(),
                    new SourceLocator(node.path("sourceSystem").asText(), node.path("stableSourceId").asText(),
                            node.path("sourceVersion").asText(), node.path("sourceLocator").asText()),
                    node.path("title").asText(), node.path("publicationDate").asText(), node.path("institution").asText(),
                    node.path("documentType").asText(), strings(node.path("missingFields")), node.path("availability").asText(),
                    node.path("usageLimitation").asText(), node.path("sourceUrl").asText(), node.path("capturedAt").asText(),
                    node.path("dataMode").asText(), node.path("authorityMarker").asText(), node.path("professionalUse").asText(),
                    false, node.path("importDecision").asText(), new SourceTrace(trace.path("sourceDatasetId").asText(),
                    trace.path("sourceRecordsSha256").asText(), trace.path("sourceRecordSha256").asText(),
                    strings(trace.path("sourceSnapshotRefs")), json.convertValue(trace.path("sourceSnapshots"),
                    json.getTypeFactory().constructCollectionType(List.class, SourceSnapshotTrace.class))),
                    node.path("sourcePayloadSha256").asText(), node.path("approvalId").asText(),
                    node.path("approvalSha256").asText(), node.path("retentionPeriod").asText(), node.path("attribution").asText()));
        }
        return records;
    }

    private Reference withTitle(Reference v, String title) {
        return new Reference(v.referenceId(), v.category(), v.source(), title, v.publicationDate(), v.institution(),
                v.documentType(), v.missingFields(), v.availability(), v.usageLimitation(), v.sourceUrl(), v.capturedAt(),
                v.dataMode(), v.authorityMarker(), v.professionalUse(), v.externalModelAllowed(), v.importDecision(),
                v.sourceTrace(), v.sourcePayloadSha256(), v.approvalId(), v.approvalSha256(), v.retentionPeriod(), v.attribution());
    }

    private PublicAdjudicationReferenceImporter importer() {
        return new PublicAdjudicationReferenceImporter(dataSource(), json,
                cn.xinji.agentplatform.adjudication.adapter.out.dm8.PublicAdjudicationSourceApprovalRegistry.approvedPublicJudgmentPilot());
    }
    private static PublicAdjudicationReferenceImporter.SourceRegistration approvedSource() {
        return PublicAdjudicationReferenceImporter.approvedPublicJudgmentPilot();
    }
    private static PublicAdjudicationReferenceImporter.Batch replayBatch(String id) {
        return new PublicAdjudicationReferenceImporter.Batch(id, "public-reference-pilot-v1", "same approved records",
                "0".repeat(64), "controlled://replay", Instant.parse("2026-09-20T00:30:00Z"));
    }
    private List<String> strings(JsonNode node) {
        return json.convertValue(node, json.getTypeFactory().constructCollectionType(List.class, String.class));
    }
    private static DriverManagerDataSource dataSource() { return new DriverManagerDataSource(URL, USER, PASSWORD); }
    private static int[] businessCounts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            return new int[]{count(c, "adj_local_case"), count(c, "adj_local_material"),
                    count(c, "adj_local_legal_snapshot"), count(c, "adj_local_template_snapshot")};
        }
    }
    private static int count(Connection c, String table) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(r.next()); return r.getInt(1);
        }
    }
    private static String scalarText(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); }
    }
    private static String scalarText(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection()) { return scalarText(c, sql); }
    }
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void run(Connection c, String file) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) s.execute(sql);
        }
    }
    private static void runIgnoringMissing(Connection c, String file) throws Exception {
        try { run(c, file); } catch (Exception ignored) { }
    }
}
