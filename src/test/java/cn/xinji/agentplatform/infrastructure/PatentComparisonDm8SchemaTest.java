package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.*;

class PatentComparisonDm8SchemaTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void configure() throws Exception {
        url = System.getenv("DM8_JDBC_URL"); user = System.getenv("DM8_JDBC_USER"); password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) { rollbackIgnoringMissing(connection); }
    }

    @AfterEach void cleanup() throws Exception {
        if (url != null) try (Connection connection = connection()) { rollbackIgnoringMissing(connection); }
    }

    @Test void initializesConstraintsAndRollsBackTwice() throws Exception {
        try (Connection connection = connection()) {
            run(connection, "db/dm8/V005__patent_comparison_v0.sql");
            insertTask(connection, "task-1", "key-1", "SUCCEEDED", "NOT_CALIBRATED");
            insertCandidate(connection, "task-1", 0, "COMPLETED");
            insertFeature(connection, "task-1", 0, "F1");
            insertMapping(connection, "task-1", 0, 0, "CORRESPONDS");
            assertEquals(1, count(connection, "con_patent_compare_mapping"));
            assertThrows(SQLException.class, () -> insertTask(connection, "task-2", "key-1", "SUCCEEDED", "NOT_CALIBRATED"));
            assertThrows(SQLException.class, () -> insertCandidate(connection, "task-1", 1, "UNKNOWN"));
            assertThrows(SQLException.class, () -> insertMapping(connection, "task-1", 0, 99, "NOT_FOUND"));
            run(connection, "db/dm8/U005__patent_comparison_v0.sql");
            run(connection, "db/dm8/V005__patent_comparison_v0.sql");
            run(connection, "db/dm8/U005__patent_comparison_v0.sql");
        }
    }

    private static void insertTask(Connection connection, String taskId, String key, String status, String score) throws SQLException {
        insert(connection, "INSERT INTO con_patent_compare_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, "v0-authorized-user", key, "fingerprint", "CLAIM_COMPARISON", "SYN-PAT-001", "synthetic-v1",
                status, "SYNTHETIC_V0", "catalog-v1", "SYNTHETIC_CLAIM_COMPARE_V1", score, "UNREVIEWED", "", "trace", now());
    }

    private static void insertCandidate(Connection connection, String taskId, int order, String status) throws SQLException {
        insert(connection, "INSERT INTO con_patent_compare_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, order, "SYN-PAT-003", "synthetic-v1", status, "", "", "", "辅助意见");
    }

    private static void insertFeature(Connection connection, String taskId, int order, String id) throws SQLException {
        insert(connection, "INSERT INTO con_patent_compare_feature VALUES (?, ?, ?, ?, ?)", taskId, order, id, "双区烘干", "目标权利要求1");
    }

    private static void insertMapping(Connection connection, String taskId, int candidate, int feature, String relation) throws SQLException {
        insert(connection, "INSERT INTO con_patent_compare_mapping VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, candidate, feature, relation, "候选证据", "候选权利要求1", "差异", "逐字核对");
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private static Timestamp now() { return Timestamp.from(Instant.parse("2026-09-16T08:00:00Z")); }
    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { rows.next(); return rows.getInt(1); }
    }
    private static void run(Connection connection, String path) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void rollbackIgnoringMissing(Connection connection) throws Exception {
        try { run(connection, "db/dm8/U005__patent_comparison_v0.sql"); } catch (SQLException ignored) { }
    }
}
