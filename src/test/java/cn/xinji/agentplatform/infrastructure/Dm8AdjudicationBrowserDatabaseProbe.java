package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8AdjudicationBrowserDatabaseProbe {
    @Test
    void executeRequestedPhase() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            switch (System.getProperty("adjudication.browser.phase", "")) {
                case "setup" -> {
                    executeIgnoring(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
                    execute(connection, "db/dm8/V003__adjudication_mvp_v0.sql");
                }
                case "verify" -> verify(connection, requiredWorkId());
                case "cleanup" -> executeIgnoring(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
                default -> throw new IllegalArgumentException("Unsupported probe phase");
            }
        }
    }

    private static void verify(Connection connection, String workId) throws Exception {
        String id = quote(workId);
        try {
            assertEquals(1, count(connection, "adj_work_item", "work_item_id=" + id + " AND row_version=7 AND scope_code='SYNTHETIC_V0'"));
            assertEquals(1, count(connection, "adj_work_input", "work_item_id=" + id));
            assertEquals(1, count(connection, "adj_material_inspection", "work_item_id=" + id));
            assertEquals(2, count(connection, "adj_inspection_item", "inspection_id IN (SELECT inspection_id FROM adj_material_inspection WHERE work_item_id=" + id + ")"));
            assertEquals(1, count(connection, "adj_legal_candidate_set", "work_item_id=" + id));
            assertEquals(1, count(connection, "adj_legal_candidate", "candidate_set_id IN (SELECT candidate_set_id FROM adj_legal_candidate_set WHERE work_item_id=" + id + ")"));
            assertEquals(1, count(connection, "adj_citation", "work_item_id=" + id));
            assertEquals(2, count(connection, "adj_draft", "work_item_id=" + id + " AND is_draft='Y'"));
            assertEquals(2, count(connection, "adj_draft_citation", "draft_id IN (SELECT draft_id FROM adj_draft WHERE work_item_id=" + id + ")"));
            assertEquals(4, count(connection, "adj_draft_section", "draft_id IN (SELECT draft_id FROM adj_draft WHERE work_item_id=" + id + ")"));
            assertEquals(4, count(connection, "adj_draft_section_source", "section_id IN (SELECT section_id FROM adj_draft_section WHERE draft_id IN (SELECT draft_id FROM adj_draft WHERE work_item_id=" + id + "))"));
            assertEquals(1, count(connection, "adj_test_review", "work_item_id=" + id + " AND scope_code='SYNTHETIC_PROCESS_TEST' AND not_official_decision='Y'"));
            assertEquals(7, count(connection, "adj_idempotency_record", "work_item_id=" + id));
        } finally {
            execute(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
        }
    }

    private static String requiredWorkId() {
        String value = System.getProperty("adjudication.browser.workId");
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("A valid work item id is required");
        return value;
    }

    private static int count(Connection connection, String table, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next(); return result.getInt(1);
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static void execute(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void executeIgnoring(Connection connection, String file) throws Exception {
        try { execute(connection, file); } catch (SQLException ignored) { }
    }
}
