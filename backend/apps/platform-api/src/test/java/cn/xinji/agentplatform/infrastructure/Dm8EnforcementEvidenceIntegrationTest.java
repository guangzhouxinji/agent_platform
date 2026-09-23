package cn.xinji.agentplatform.infrastructure;

import static cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService.*;
import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementEvidenceStore;
import cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DM8_JDBC_URL", matches = ".+")
class Dm8EnforcementEvidenceIntegrationTest {
    private static final String URL = System.getenv("DM8_JDBC_URL");
    private static final String USER = System.getenv("DM8_JDBC_USER");
    private static final String PASSWORD = System.getenv("DM8_JDBC_PASSWORD");
    private String organization;
    private String leadId;

    @BeforeEach void initialize() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            runIgnoringMissing(connection, "db/dm8/U022__enforcement_evidence_management_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U020__enforcement_clue_discovery_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U019__enforcement_discovery_rule_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U018__enforcement_source_management_v1.sql");
            run(connection, "db/dm8/V018__enforcement_source_management_v1.sql");
            run(connection, "db/dm8/V019__enforcement_discovery_rule_v1.sql");
            run(connection, "db/dm8/V020__enforcement_clue_discovery_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U022__enforcement_evidence_management_v1.sql");
            run(connection, "db/dm8/V022__enforcement_evidence_management_v1.sql");
            organization = "evidence-test-org";
            leadId = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("INSERT INTO enf_discovered_lead (lead_id,organization_ref,source_id,source_record_id,source_version,snapshot_id,snapshot_sha256,source_availability,source_excerpt,suspected_types_json,processing_status,review_required,usage_scope,conclusion_scope,first_found_at,version_no,created_by,created_at,updated_by,updated_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, leadId); statement.setString(2, organization); statement.setString(3, "evidence-test-source");
                statement.setString(4, "evidence-test-record"); statement.setString(5, "v1"); statement.setString(6, UUID.randomUUID().toString());
                statement.setString(7, "0".repeat(64)); statement.setString(8, "AVAILABLE"); statement.setString(9, "development test lead");
                statement.setString(10, "[\"PATENT_INFRINGEMENT\"]"); statement.setString(11, "PENDING_VERIFICATION"); statement.setInt(12, 1);
                statement.setString(13, "DEVELOPMENT_ONLY"); statement.setString(14, "NOT_AN_INFRINGEMENT_FINDING");
                statement.setTimestamp(15, java.sql.Timestamp.from(Instant.now())); statement.setInt(16, 1); statement.setString(17, "integration-test");
                statement.setTimestamp(18, java.sql.Timestamp.from(Instant.now())); statement.setString(19, "integration-test");
                statement.setTimestamp(20, java.sql.Timestamp.from(Instant.now())); statement.setString(21, "integration-test-request"); statement.executeUpdate();
            }
        }
    }

    @AfterEach void cleanup() throws Exception {
        try (Connection connection = connection()) {
            runIgnoringMissing(connection, "db/dm8/U022__enforcement_evidence_management_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U020__enforcement_clue_discovery_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U019__enforcement_discovery_rule_v1.sql");
            runIgnoringMissing(connection, "db/dm8/U018__enforcement_source_management_v1.sql");
        }
    }

    @Test void storesImmutableBytesDigestStatusHistoryAccessLogAndOrganizationBoundary() throws Exception {
        Instant now = Instant.parse("2026-09-21T01:00:00Z");
        EnforcementEvidenceService service = new EnforcementEvidenceService(new Dm8EnforcementEvidenceStore(new DriverManagerDataSource()),
                Clock.fixed(now, ZoneOffset.UTC), new Policy(Set.of("text/plain"), Set.of("text/plain"), 4096));
        String encoded = Base64.getEncoder().encodeToString("real dm8 evidence bytes".getBytes(StandardCharsets.UTF_8));
        EvidenceDetail created = service.create("integration-actor", organization,
                new CreateCommand("DM8 现场记录", "DOCUMENT", leadId, null, null, null, "授权包未提供收集信息",
                        null, null, "dm8-evidence.txt", "text/plain", encoded, null), "dm8-request-create");

        assertThat(created.versions()).singleElement().satisfies(version -> {
            assertThat(version.derivedKind()).isEqualTo("ORIGINAL");
            assertThat(version.digest()).hasSize(64);
        });
        ContentResource content = service.content("integration-actor", organization, created.evidenceId(), 1, "DOWNLOAD", "dm8-request-download");
        assertThat(new String(content.bytes(), StandardCharsets.UTF_8)).isEqualTo("real dm8 evidence bytes");
        EvidenceDetail verified = service.changeStatus("integration-reviewer", organization, created.evidenceId(),
                new StatusCommand(1, "VERIFIED", "仅核对登记信息，不形成证据采信"), "dm8-request-review");
        assertThat(verified.status()).isEqualTo("VERIFIED");
        assertThat(verified.statusHistory()).singleElement().satisfies(event -> assertThat(event.reason()).contains("不形成证据采信"));
        assertThat(service.list("integration-actor", organization, leadId, null, null, 1, 20).total()).isEqualTo(1);
        assertThatThrownByDetail(service, created.evidenceId());
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM enf_evidence_access_log WHERE evidence_id='" + created.evidenceId() + "'")) {
            result.next(); assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private static void assertThatThrownByDetail(EnforcementEvidenceService service, String evidenceId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.detail("integration-actor", "other-organization", evidenceId))
                .isInstanceOf(EvidenceException.class).hasMessage("EVIDENCE_NOT_FOUND");
    }
    private Connection connection() throws Exception { return DriverManager.getConnection(URL, USER, PASSWORD); }
    private static void run(Connection connection, String path) throws Exception { executeScript(connection, Files.readString(Path.of(path))); }
    private static void runIgnoringMissing(Connection connection, String path) throws Exception { try { run(connection, path); } catch (Exception ignored) { } }
    private static void executeScript(Connection connection, String script) throws Exception {
        for (String statement : script.split(";")) if (!statement.isBlank()) try (Statement sql = connection.createStatement()) { sql.execute(statement.trim()); }
    }
    private static final class DriverManagerDataSource implements javax.sql.DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { return DriverManager.getConnection(URL, USER, PASSWORD); }
        @Override public Connection getConnection(String username, String password) throws java.sql.SQLException { return DriverManager.getConnection(URL, username, password); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
