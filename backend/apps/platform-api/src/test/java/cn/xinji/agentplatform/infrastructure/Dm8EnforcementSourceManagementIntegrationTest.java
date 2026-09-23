package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceManagementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.DevelopmentEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SaveCommand;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceManagementException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8EnforcementSourceManagementIntegrationTest {
    @Test
    void persistsConfigurationsAuditsAndIdempotentSnapshotsThenRollsBack() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            runIgnoringMissing(connection, "db/dm8/U018__enforcement_source_management_v1.sql");
            run(connection, "db/dm8/V018__enforcement_source_management_v1.sql");
            var store = new Dm8EnforcementSourceManagementStore(dataSource(url, user, password), new ObjectMapper());
            var service = new EnforcementSourceManagementService(store, new DevelopmentEnforcementSourceAdapter(), Clock.systemUTC());
            SaveCommand create = command(null, true);
            var created = service.save("actor-a", "org-a", create);
            assertThat(created.version()).isEqualTo(1);
            assertThat(service.list("actor-a", "org-b")).isEmpty();
            assertThat(service.testConnection("actor-a", "org-a", created.sourceId(), "test-request").result())
                    .isEqualTo("SUCCEEDED");

            var first = service.synchronize("actor-a", "org-a", created.sourceId(), "sync-1", "MANUAL", "request-1");
            var replay = service.synchronize("actor-a", "org-a", created.sourceId(), "sync-1", "MANUAL", "request-2");
            var second = service.synchronize("actor-a", "org-a", created.sourceId(), "sync-2", "MANUAL", "request-3");
            assertThat(first.status()).isEqualTo("succeeded");
            assertThat(replay.taskId()).isEqualTo(first.taskId());
            assertThat(second.importedCount()).isZero();
            assertThat(count(connection, "enf_source_record_snapshot")).isEqualTo(3);
            assertThat(count(connection, "enf_source_sync_task")).isEqualTo(2);
            assertThat(count(connection, "enf_source_configuration_history")).isEqualTo(1);

            assertThatThrownBy(() -> service.save("actor-a", "org-a", command(99, true)))
                    .isInstanceOf(SourceManagementException.class).hasMessage("VERSION_CONFLICT");
            var stopped = service.setEnabled("actor-a", "org-a", created.sourceId(), created.version(), false);
            assertThat(stopped.enabled()).isFalse();
            assertThatThrownBy(() -> service.synchronize("actor-a", "org-a", created.sourceId(),
                    "sync-disabled", "MANUAL", "request-4"))
                    .isInstanceOf(SourceManagementException.class).hasMessage("SOURCE_DISABLED");
            run(connection, "db/dm8/U018__enforcement_source_management_v1.sql");
        }
    }

    private static SaveCommand command(Integer version, boolean enabled) {
        return new SaveCommand("source-1", "开发执法来源", "COMPLAINT_REPORT",
                EnforcementSourceManagementService.ADAPTER_TYPE, EnforcementSourceManagementService.CONTROLLED_ENDPOINT,
                "NONE_REQUIRED", Map.of("sourceRecordId", "id", "sourceVersion", "version",
                "sourceUpdatedAt", "updatedAt", "content", "content"), "owner", "MANUAL", null, null,
                enabled, version);
    }
    private static javax.sql.DataSource dataSource(String url, String user, String password) {
        return new javax.sql.DataSource() {
            public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
            public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
            public java.io.PrintWriter getLogWriter() { return null; }
            public void setLogWriter(java.io.PrintWriter out) { }
            public void setLoginTimeout(int seconds) { }
            public int getLoginTimeout() { return 0; }
            public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unsupported"); }
            public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next(); return result.getInt(1);
        }
    }
    private static void run(Connection connection, String file) throws Exception {
        String script = Files.readString(Path.of(file));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void runIgnoringMissing(Connection connection, String file) throws Exception {
        try { run(connection, file); } catch (SQLException ignored) { }
    }
}
