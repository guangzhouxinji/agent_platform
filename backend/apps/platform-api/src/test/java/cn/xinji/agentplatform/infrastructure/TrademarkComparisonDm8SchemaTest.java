package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.*;

class TrademarkComparisonDm8SchemaTest {
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
            run(connection, "db/dm8/V006__trademark_comparison_v0.sql");
            insertTask(connection, "task-1", "key-1", "PARTIAL", "EVIDENCE_TABLE_ONLY");
            insertCandidate(connection, "task-1", 0, "PARTIAL");
            insertDimension(connection, "task-1", 0, 0, "TEXT", "ANALYZED");
            assertEquals(1, count(connection, "con_trademark_compare_dimension"));
            assertThrows(SQLException.class, () -> insertTask(connection, "task-2", "key-1", "PARTIAL", "EVIDENCE_TABLE_ONLY"));
            assertThrows(SQLException.class, () -> insertCandidate(connection, "task-1", 1, "UNKNOWN"));
            assertThrows(SQLException.class, () -> insertDimension(connection, "task-1", 0, 1, "UNKNOWN", "ANALYZED"));
            assertThrows(SQLException.class, () -> insertDimension(connection, "task-1", 99, 0, "TEXT", "ANALYZED"));
            run(connection, "db/dm8/U006__trademark_comparison_v0.sql");
            run(connection, "db/dm8/V006__trademark_comparison_v0.sql");
            run(connection, "db/dm8/U006__trademark_comparison_v0.sql");
        }
    }

    private static void insertTask(Connection connection, String taskId, String key, String status, String capability) throws SQLException {
        insert(connection, "INSERT INTO con_trademark_compare_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, "v0-authorized-user", key, "fingerprint", "AUXILIARY_COMPARISON", "TM-SYN-001", "synthetic-v1",
                "Mw", "5YyW5aaG5ZOB", "VEVYVA", status, "SYNTHETIC_V0", "catalog-v1", "SYNTHETIC_TRADEMARK_OBSERVATION_V1",
                capability, "UNREVIEWED", "", "trace", Timestamp.from(Instant.parse("2026-09-16T08:00:00Z")));
    }

    private static void insertCandidate(Connection connection, String taskId, int order, String status) throws SQLException {
        insert(connection, "INSERT INTO con_trademark_compare_candidate VALUES (?, ?, ?, ?, ?, ?, ?)",
                taskId, order, "TM-SYN-002", "synthetic-v1", status, "", "仅提供证据表");
    }

    private static void insertDimension(Connection connection, String taskId, int candidate, int order, String dimension, String state) throws SQLException {
        insert(connection, "INSERT INTO con_trademark_compare_dimension VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, candidate, order, dimension, state, "观察", "", "原因");
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
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
        try { run(connection, "db/dm8/U006__trademark_comparison_v0.sql"); } catch (SQLException ignored) { }
    }
}
