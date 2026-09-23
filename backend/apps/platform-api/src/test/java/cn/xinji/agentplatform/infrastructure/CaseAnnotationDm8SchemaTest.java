package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.*;

class CaseAnnotationDm8SchemaTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void configure() throws Exception {
        url = System.getenv("DM8_JDBC_URL");
        user = System.getenv("DM8_JDBC_USER");
        password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) { rollbackIgnoringMissing(connection); }
    }

    @AfterEach void cleanup() throws Exception {
        if (url != null) try (Connection connection = connection()) { rollbackIgnoringMissing(connection); }
    }

    @Test void initializesConstraintsAndRollsBackTwice() throws Exception {
        try (Connection connection = connection()) {
            run(connection, "db/dm8/V004__case_annotation_v0.sql");
            insertTask(connection, "task-1", "ann-1", "SUCCEEDED");
            insertAnnotation(connection, "ann-1", 1, "PENDING_REVIEW");
            insertVersion(connection, "ann-1", 1, "PENDING_REVIEW");
            insertAudit(connection, "audit-1", "ann-1", 1, "EXTRACT");
            assertEquals(1, count(connection, "con_case_annotation_audit"));
            assertThrows(SQLException.class, () -> insertTask(connection, "task-2", "ann-2", "UNKNOWN"));
            assertThrows(SQLException.class, () -> insertVersion(connection, "ann-1", 1, "DRAFT"));
            assertThrows(SQLException.class, () -> insertAudit(connection, "audit-2", "ann-1", 99, "REVISE"));
            run(connection, "db/dm8/U004__case_annotation_v0.sql");
            run(connection, "db/dm8/V004__case_annotation_v0.sql");
            run(connection, "db/dm8/U004__case_annotation_v0.sql");
        }
    }

    private void insertTask(Connection connection, String taskId, String annotationId, String status) throws SQLException {
        insert(connection, "INSERT INTO con_case_extract_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, "v0-authorized-user", "CASE-SYN-001", "v1", "SYNTHETIC_EXTRACT_V1", status, 1,
                annotationId, "request-1", "trace-1", null, 0, now(), now());
    }

    private static void insertAnnotation(Connection connection, String id, int version, String state) throws SQLException {
        insert(connection, "INSERT INTO con_case_annotation VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, "CASE-SYN-001", "v1", version, state, 0, now(), now());
    }

    private static void insertVersion(Connection connection, String id, int version, String state) throws SQLException {
        insert(connection, "INSERT INTO con_case_annotation_version VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, version, state, "事实", "焦点", "要点", "第1页第1段", "v0-authorized-user", "原因", now());
    }

    private static void insertAudit(Connection connection, String auditId, String annotationId, int version, String action) throws SQLException {
        insert(connection, "INSERT INTO con_case_annotation_audit VALUES (?, ?, ?, ?, ?, ?, ?)",
                auditId, annotationId, version, action, "v0-authorized-user", "原因", now());
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
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getInt(1);
        }
    }
    private static void run(Connection connection, String path) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void rollbackIgnoringMissing(Connection connection) throws Exception {
        try { run(connection, "db/dm8/U004__case_annotation_v0.sql"); } catch (SQLException ignored) { }
    }
}
