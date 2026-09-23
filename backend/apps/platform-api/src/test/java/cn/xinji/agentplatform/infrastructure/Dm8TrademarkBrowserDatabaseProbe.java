package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8TrademarkBrowserDatabaseProbe {
    @Test void executeRequestedPhase() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            switch (System.getProperty("trademark.browser.phase", "")) {
                case "setup" -> {
                    executeIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                    executeScript(connection, "db/dm8/V001__consultation_mvp_v0.sql");
                }
                case "verify" -> verify(connection, requiredTaskId());
                case "cleanup" -> executeIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                default -> throw new IllegalArgumentException("Unsupported probe phase");
            }
        }
    }

    private static void verify(Connection connection, String taskId) throws Exception {
        String id = quote(taskId);
        try {
            assertEquals(1, countWhere(connection, "con_trademark_search_task", "task_id = " + id));
            assertEquals(1, countWhere(connection, "con_trademark_search_task", "task_id = " + id + " AND status = 'SUCCEEDED' AND total_candidates = 5"));
            assertEquals(5, countWhere(connection, "con_trademark_search_candidate", "task_id = " + id));
            assertEquals(15, countWhere(connection, "con_trademark_search_dimension", "task_id = " + id));
            assertEquals(10, countWhere(connection, "con_trademark_search_dimension", "task_id = " + id + " AND dimension_state = 'EXECUTED'"));
            assertEquals(5, countWhere(connection, "con_trademark_search_dimension", "task_id = " + id + " AND dimension_name = 'SEMANTIC' AND dimension_state = 'NOT_ANALYZED'"));
            assertEquals(1, countWhere(connection, "con_trademark_search_task", "task_id = " + id + " AND requester_ref = 'v0-authorized-user' AND idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL"));
        } finally {
            executeScript(connection, "db/dm8/U001__consultation_mvp_v0.sql");
        }
    }

    private static String requiredTaskId() {
        String value = System.getProperty("trademark.browser.taskId");
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("A valid trademark task id is required");
        return value;
    }

    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }

    private static int countWhere(Connection connection, String table, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void executeScript(Connection connection, String relativePath) throws Exception {
        String script = Files.readString(Path.of(relativePath));
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static void executeIgnoringMissing(Connection connection, String relativePath) throws Exception {
        try { executeScript(connection, relativePath); }
        catch (SQLException ignored) { }
    }
}
