package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.*;

class MockCenterPatentCatalogDm8SchemaTest {
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

    @Test void initializesPublicPilotCatalogConstraintsAndRollsBackTwice() throws Exception {
        try (Connection connection = connection()) {
            run(connection, "db/dm8/V007__mock_center_public_patent_catalog.sql");
            insertImport(connection, "batch-1", "v1", "a".repeat(64));
            insertRecord(connection, "record-1", "batch-1", "source-1", "source-record-1", "v1", "PUBLIC_PILOT", "NON_CENTER_PUBLIC_SOURCE", "SOURCE_NOT_PROVIDED");
            assertEquals(1, count(connection, "mock_center_patent_record"));
            assertThrows(SQLException.class, () -> insertImport(connection, "batch-2", "v1", "a".repeat(64)));
            assertThrows(SQLException.class, () -> insertRecord(connection, "record-2", "batch-1", "source-1", "source-record-1", "v1", "PUBLIC_PILOT", "NON_CENTER_PUBLIC_SOURCE", "AVAILABLE"));
            assertThrows(SQLException.class, () -> insertRecord(connection, "record-3", "missing-batch", "source-2", "source-record-2", "v1", "PUBLIC_PILOT", "NON_CENTER_PUBLIC_SOURCE", "AVAILABLE"));
            assertThrows(SQLException.class, () -> insertRecord(connection, "record-4", "batch-1", "source-2", "source-record-2", "v1", "SYNTHETIC_V0", "NON_CENTER_PUBLIC_SOURCE", "AVAILABLE"));
            assertThrows(SQLException.class, () -> insertRecord(connection, "record-5", "batch-1", "source-3", "source-record-3", "v1", "PUBLIC_PILOT", "CENTER_SOURCE", "AVAILABLE"));
            run(connection, "db/dm8/U007__mock_center_public_patent_catalog.sql");
            run(connection, "db/dm8/V007__mock_center_public_patent_catalog.sql");
            run(connection, "db/dm8/U007__mock_center_public_patent_catalog.sql");
        }
    }

    private static void insertImport(Connection connection, String batchId, String version, String digest) throws SQLException {
        insert(connection, "INSERT INTO mock_center_catalog_import VALUES (?, ?, ?, ?, ?)", batchId, version, digest, 1, timestamp());
    }

    private static void insertRecord(Connection connection, String id, String batchId, String sourceId, String sourceRecordId,
                                     String sourceVersion, String dataMode, String sourceClass, String abstractStatus) throws SQLException {
        insert(connection, "INSERT INTO mock_center_patent_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, sourceId, sourceRecordId, sourceVersion, "CN123456789A", null, "公开试验专利", "[\"申请人\"]", "[]",
                Date.valueOf("2025-12-01"), Date.valueOf("2026-01-01"), "[\"G06N\"]", "中文摘要", abstractStatus,
                "https://example.invalid/record", "2026-09-17T00:00:00Z", "b".repeat(64), "mock-center-public-patent-v1", batchId, dataMode, sourceClass);
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private static Timestamp timestamp() { return Timestamp.from(Instant.parse("2026-09-17T00:00:00Z")); }
    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
    private static void run(Connection connection, String path) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(path)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void rollbackIgnoringMissing(Connection connection) throws Exception {
        try { run(connection, "db/dm8/U007__mock_center_public_patent_catalog.sql"); } catch (SQLException ignored) { }
    }
}
