package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8BrowserDatabaseProbe {
    @Test void executeRequestedPhase() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            switch (System.getProperty("dm8.browser.phase", "")) {
                case "setup" -> {
                    executeIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                    executeScript(connection, "db/dm8/V001__consultation_mvp_v0.sql");
                }
                case "verify" -> {
                    try {
                        assertEquals(1, count(connection, "con_consultation"));
                        assertEquals(2, count(connection, "con_user_input"));
                        assertEquals(2, count(connection, "con_assisted_answer"));
                        assertEquals(2, count(connection, "con_idempotency_record"));
                        assertEquals(2, count(connection, "con_retrieval_candidate"));
                        assertEquals(2, count(connection, "con_answer_candidate"));
                        assertEquals(2, count(connection, "con_answer_source_link"));
                        assertEquals(1, countWhere(connection, "con_assisted_answer", "outcome_type = 'NEEDS_CLARIFICATION'"));
                        assertEquals(1, countWhere(connection, "con_assisted_answer", "outcome_type = 'ANSWER_READY'"));
                        assertEquals(0, countWhere(connection, "con_information_issue", "resolved_by_source_id IS NULL"));
                        assertEquals(2, countDistinct(connection, "con_idempotency_record", "answer_id"));
                        assertTrue(singleText(connection, "SELECT content FROM con_assisted_answer WHERE outcome_type = 'ANSWER_READY'").contains("不构成"));
                    } finally {
                        executeScript(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                    }
                }
                case "cleanup" -> executeIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
                default -> throw new IllegalArgumentException("Unsupported probe phase");
            }
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        return countWhere(connection, table, "1 = 1");
    }

    private static int countWhere(Connection connection, String table, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static int countDistinct(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(DISTINCT " + column + ") FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String singleText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
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
