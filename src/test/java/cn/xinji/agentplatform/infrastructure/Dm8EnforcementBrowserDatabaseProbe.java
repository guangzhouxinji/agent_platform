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

class Dm8EnforcementBrowserDatabaseProbe {
    @Test void executeRequestedPhase() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            switch (System.getProperty("enforcement.browser.phase", "")) {
                case "setup" -> {
                    executeIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
                    executeScript(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
                }
                case "verify" -> verify(connection, requiredWorkId());
                case "cleanup" -> executeIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
                default -> throw new IllegalArgumentException("Unsupported probe phase");
            }
        }
    }

    private static void verify(Connection connection, String workId) throws Exception {
        String id = quote(workId);
        try {
            assertEquals(1, countWhere(connection, "enf_work_item", "work_item_id = " + id));
            assertEquals(2, countWhere(connection, "enf_clue_analysis", "work_item_id = " + id));
            assertEquals(2, countWhere(connection, "enf_evidence_ref", "work_item_id = " + id));
            assertEquals(1, countWhere(connection, "enf_relation_set", "work_item_id = " + id));
            assertEquals(1, countWhere(connection, "enf_aux_analysis", "work_item_id = " + id));
            assertEquals(1, countWhere(connection, "enf_review_record", "work_item_id = " + id));
            assertEquals(4, countWhere(connection, "enf_idempotency_record", "work_item_id = " + id));
            assertEquals(0, countWhere(connection, "enf_aux_analysis", "work_item_id = " + id + " AND not_official_decision <> 'Y'"));
            assertEquals(0, countWhere(connection, "enf_review_record", "work_item_id = " + id + " AND (scope_code <> 'SYNTHETIC_PROCESS_TEST' OR not_official_decision <> 'Y')"));
            assertTrue(countWhere(connection, "enf_clue_field", "analysis_id IN (SELECT analysis_id FROM enf_clue_analysis WHERE work_item_id = " + id + ")") >= 16);
            assertTrue(countWhere(connection, "enf_timeline_entry", "relation_set_id IN (SELECT relation_set_id FROM enf_relation_set WHERE work_item_id = " + id + ")") >= 6);
            assertTrue(countWhere(connection, "enf_analysis_item", "aux_analysis_id IN (SELECT aux_analysis_id FROM enf_aux_analysis WHERE work_item_id = " + id + ")") >= 4);
        } finally {
            executeScript(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
        }
    }

    private static String requiredWorkId() {
        String value = System.getProperty("enforcement.browser.workId");
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("A valid work item id is required");
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
