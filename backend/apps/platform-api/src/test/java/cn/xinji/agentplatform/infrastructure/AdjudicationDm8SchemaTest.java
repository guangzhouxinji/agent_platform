package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AdjudicationDm8SchemaTest {
    @Test void initializesPersistsSyntheticHistoryEnforcesBoundariesAndRollsBack() throws Exception {
        String url = System.getenv("DM8_JDBC_URL"), user = System.getenv("DM8_JDBC_USER"), password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeIgnoringMissing(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
            executeScript(connection, "db/dm8/V003__adjudication_mvp_v0.sql");
            insertNormalScenario(connection);
            insertConflictScenario(connection);

            assertEquals(2, count(connection, "adj_work_item"));
            assertEquals(2, count(connection, "adj_material_inspection"));
            assertEquals(3, count(connection, "adj_inspection_item"));
            assertEquals(1, count(connection, "adj_legal_candidate"));
            assertEquals(1, count(connection, "adj_draft"));
            assertEquals(1, count(connection, "adj_test_review"));
            assertEquals("v0-adjudication-user", text(connection, "SELECT owner_ref FROM adj_work_item WHERE work_item_id = 'adj-work-normal'"));

            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO adj_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "v0-adjudication-user", "OPEN", "adj-normal-key", "different", "adj-work-normal", "WORK", "adj-work-normal", 1, "{}", now()));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO adj_draft VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "adj-draft-invalid", "adj-work-normal", 2, "adj-inspection-normal", "adj-template-source", null, null, null, "N", now()));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO adj_test_review VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "adj-review-invalid", "adj-work-normal", "adj-draft-normal", 1, "CONFIRM", "x", "reviewer", now(), "SYNTHETIC_PROCESS_TEST", "N"));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO adj_work_input VALUES (?, ?, ?)", "missing-work", 1, "adj-material-source"));

            executeScript(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
            executeScript(connection, "db/dm8/V003__adjudication_mvp_v0.sql");
            executeScript(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
        }
    }

    private static void insertNormalScenario(Connection c) throws SQLException {
        source(c, "adj-case-source", "CASE", "V0-SYN-ADJ-CASE-001", "case");
        source(c, "adj-material-source", "MATERIAL", "V0-SYN-ADJ-MAT-001", "material");
        source(c, "adj-legal-source", "LEGAL", "V0-SYN-ADJ-LAW-001", "article:10");
        source(c, "adj-template-source", "TEMPLATE", "V0-SYN-ADJ-TPL-001", "structure");
        insert(c, "INSERT INTO adj_work_item VALUES (?, ?, ?, ?, ?, ?, ?)", "adj-work-normal", "adj-case-source", "org-v0", "v0-adjudication-user", "SYNTHETIC_V0", now(), 1);
        insert(c, "INSERT INTO adj_work_input VALUES (?, ?, ?)", "adj-work-normal", 1, "adj-material-source");
        insert(c, "INSERT INTO adj_material_inspection VALUES (?, ?, ?, ?, ?)", "adj-inspection-normal", "adj-work-normal", 1, null, now());
        insert(c, "INSERT INTO adj_inspection_item VALUES (?, ?, ?, ?, ?, ?)", "adj-item-statement", "adj-inspection-normal", 1, "PARTY_STATEMENT", "合成当事人主张", "adj-material-source");
        insert(c, "INSERT INTO adj_legal_candidate_set VALUES (?, ?, ?, ?, ?, ?, ?)", "adj-candidates-normal", "adj-work-normal", 1, "adj-inspection-normal", null, null, now());
        insert(c, "INSERT INTO adj_legal_candidate VALUES (?, ?, ?, ?, ?, ?, ?)", "adj-candidate-normal", "adj-candidates-normal", 1, "V0-SYN-ADJ-LAW-001", "adj-legal-source", "article:10", "仅合成候选");
        insert(c, "INSERT INTO adj_citation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "adj-citation-normal", "adj-work-normal", 1, "adj-legal-source", "article:10", "SELECT", "测试选择", null, now());
        insert(c, "INSERT INTO adj_draft VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "adj-draft-normal", "adj-work-normal", 1, "adj-inspection-normal", "adj-template-source", null, null, null, "Y", now());
        insert(c, "INSERT INTO adj_draft_citation VALUES (?, ?, ?)", "adj-draft-normal", "adj-citation-normal", 1);
        insert(c, "INSERT INTO adj_draft_section VALUES (?, ?, ?, ?, ?)", "adj-section-normal", "adj-draft-normal", 1, "facts", "合成草稿内容");
        insert(c, "INSERT INTO adj_draft_section_source VALUES (?, ?, ?)", "adj-section-normal", "adj-material-source", 1);
        insert(c, "INSERT INTO adj_test_review VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "adj-review-normal", "adj-work-normal", "adj-draft-normal", 1, "CONFIRM", "仅测试复核", "v0-adjudication-reviewer", now(), "SYNTHETIC_PROCESS_TEST", "Y");
        insert(c, "INSERT INTO adj_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "v0-adjudication-user", "OPEN", "adj-normal-key", "fingerprint", "adj-work-normal", "WORK", "adj-work-normal", 1, "{}", now());
    }

    private static void insertConflictScenario(Connection c) throws SQLException {
        source(c, "adj-case-conflict-source", "CASE", "V0-SYN-ADJ-CASE-002", "case");
        source(c, "adj-material-conflict-source", "MATERIAL", "V0-SYN-ADJ-MAT-002", "material");
        insert(c, "INSERT INTO adj_work_item VALUES (?, ?, ?, ?, ?, ?, ?)", "adj-work-conflict", "adj-case-conflict-source", "org-v0", "v0-adjudication-user", "SYNTHETIC_V0", now(), 1);
        insert(c, "INSERT INTO adj_work_input VALUES (?, ?, ?)", "adj-work-conflict", 1, "adj-material-conflict-source");
        insert(c, "INSERT INTO adj_material_inspection VALUES (?, ?, ?, ?, ?)", "adj-inspection-conflict", "adj-work-conflict", 1, null, now());
        insert(c, "INSERT INTO adj_inspection_item VALUES (?, ?, ?, ?, ?, ?)", "adj-item-missing", "adj-inspection-conflict", 1, "MISSING", "缺少专利比对材料", "adj-material-conflict-source");
        insert(c, "INSERT INTO adj_inspection_item VALUES (?, ?, ?, ?, ?, ?)", "adj-item-conflict", "adj-inspection-conflict", 1, "CONFLICT", "发生日期存在两种记载", "adj-material-conflict-source");
    }

    private static void source(Connection c, String id, String kind, String objectId, String location) throws SQLException {
        insert(c, "INSERT INTO adj_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)", id, kind, "v0-synthetic-adjudication", objectId, "v1", location, now());
    }
    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) { for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); statement.executeUpdate(); }
    }
    private static Timestamp now() { return Timestamp.from(Instant.parse("2026-09-16T06:00:00Z")); }
    private static int count(Connection c, String table) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); } }
    private static String text(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getString(1); } }
    private static void executeScript(Connection c, String path) throws Exception { String script = Files.readString(Path.of(path)); try (Statement s = c.createStatement()) { for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) s.execute(sql); } }
    private static void executeIgnoringMissing(Connection c, String path) throws Exception { try { executeScript(c, path); } catch (SQLException ignored) { } }
}
