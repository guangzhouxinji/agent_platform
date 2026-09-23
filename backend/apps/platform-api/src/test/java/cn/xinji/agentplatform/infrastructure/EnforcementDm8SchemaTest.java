package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EnforcementDm8SchemaTest {
    @Test
    void initializesAndRollsBackTheIsolatedPublicReferenceCatalog() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeIgnoringMissing(connection, "db/dm8/U011__enforcement_public_reference_catalog_v1.sql");
            executeScript(connection, "db/dm8/V011__enforcement_public_reference_catalog_v1.sql");
            executeScript(connection, "db/dm8/V016__enforcement_public_historical_case_category_v1.sql");
            insert(connection, "INSERT INTO enf_public_source_registration VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "test-public-source", "Test public source", "https://example.invalid/source", "PUBLIC_WEB_API",
                    "test-only", "test-owner", "INTERNAL_DEVELOPMENT_ONLY", "NO_MODEL", "N", "REVIEW_REQUIRED", null,
                    "test-approval", "0".repeat(64), "TEST_ONLY", "test attribution", null, null);
            assertEquals(1, count(connection, "enf_public_source_registration"));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO enf_public_reference_snapshot VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "reference", "v1", "missing-batch", "PUBLIC_LEGAL_REFERENCE", "AVAILABLE", "{}", "digest",
                    "0".repeat(64), "controlled://raw", null, null));
            executeScript(connection, "db/dm8/U016__enforcement_public_historical_case_category_v1.sql");
            executeScript(connection, "db/dm8/U011__enforcement_public_reference_catalog_v1.sql");
        }
    }

    @Test
    void initializesPersistsV0HistoryEnforcesBoundariesAndRollsBack() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeIgnoringMissing(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
            executeScript(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
            insertNormalScenario(connection);
            insertConflictAndUnavailableEvidence(connection);

            assertEquals(2, count(connection, "enf_work_item"));
            assertEquals(2, count(connection, "enf_clue_analysis"));
            assertEquals(2, count(connection, "enf_clue_conflict_candidate"));
            assertEquals(3, count(connection, "enf_evidence_ref"));
            assertEquals(2, count(connection, "enf_relation_set"));
            assertEquals(2, count(connection, "enf_aux_analysis"));
            assertEquals(1, count(connection, "enf_review_record"));
            assertEquals(1, count(connection, "enf_review_impact"));
            assertEquals("v0-user", text(connection, "SELECT owner_ref FROM enf_work_item WHERE work_item_id = 'work-normal'"));
            assertEquals("2026-08-03", text(connection, "SELECT value_text FROM enf_clue_conflict_candidate WHERE issue_id = 'clue-conflict' AND candidate_order = 1"));
            assertEquals("2026-08-04", text(connection, "SELECT value_text FROM enf_clue_conflict_candidate WHERE issue_id = 'clue-conflict' AND candidate_order = 2"));

            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO enf_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "v0-user", "OPEN", "normal-key", "different", "work-normal", "WORK_ITEM", "work-normal", 1, "{}", now()));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO enf_aux_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "aux-invalid", "work-normal", 2, "analysis-normal", "relations-normal", "rule-normal", null, null,
                    "SUSPECTED_SUPPORT", "N", "synthetic", now()));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO enf_evidence_relation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "relation-invalid", "relations-normal", 2, "item", "missing-evidence", "SUPPORT", "x", "evidence-source-1", "Y"));

            executeScript(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
            executeScript(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
            executeScript(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
        }
    }

    private static void insertNormalScenario(Connection c) throws SQLException {
        source(c, "clue-source-1", "CLUE", "V0-SYN-CLUE-001", "body");
        source(c, "evidence-source-1", "EVIDENCE", "V0-SYN-EVD-001", "field:content");
        source(c, "rule-normal", "RULE", "V0-SYN-ENF-RULE-001", "checklist");
        source(c, "right-normal", "RIGHT_STATUS", "V0-SYN-RIGHT-001", "status@2026-08-18");
        insert(c, "INSERT INTO enf_work_item VALUES (?, ?, ?, ?, ?, ?, ?)", "work-normal", "clue-source-1", "org-v0", "v0-user", "SYNTHETIC_V0", now(), 1);
        insert(c, "INSERT INTO enf_clue_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "analysis-normal", "work-normal", 1, "clue-source-1", null, "MACHINE", "synthetic-analysis", "TO_BE_EVALUATED", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_clue_field VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "field-subject", "analysis-normal", "SUBJECT", 1, "经营者", "合成经营者乙", null, null, "clue-source-1");
        insert(c, "INSERT INTO enf_evidence_ref VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "evidence-normal", "work-normal", 1, 1, "evidence-source-1", "clue-source-1", "controlled://V0-SYN-EVD-001", "合成收集人", null, "2026-08-18", "2026-08-19", "2026-08-20", "AVAILABLE", null, null, now());
        insert(c, "INSERT INTO enf_relation_set VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "relations-normal", "work-normal", 1, "analysis-normal", "rule-normal", null, "PENDING_HUMAN_REVIEW", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_relation_input VALUES (?, ?, ?)", "relations-normal", "evidence-normal", 1);
        insert(c, "INSERT INTO enf_evidence_relation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "relation-normal", "relations-normal", 1, "合成事项A", "evidence-normal", "SUPPORT", "带定位的候选关系", "evidence-source-1", "Y");
        insert(c, "INSERT INTO enf_timeline_entry VALUES (?, ?, ?, ?, ?, ?, ?)", "timeline-normal", "relations-normal", 1, "OCCURRENCE", "2026-08-18", "2026-08-18", "evidence-source-1");
        insert(c, "INSERT INTO enf_aux_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "aux-normal", "work-normal", 1, "analysis-normal", "relations-normal", "rule-normal", "right-normal", null, "SUSPECTED_SUPPORT", "Y", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_analysis_input VALUES (?, ?, ?)", "aux-normal", "evidence-normal", 1);
        insert(c, "INSERT INTO enf_analysis_item VALUES (?, ?, ?, ?, ?, ?, ?)", "item-normal", "aux-normal", 1, "MACHINE_INFERENCE", "合成候选分析", "evidence-source-1", "非专业判断");
        insert(c, "INSERT INTO enf_review_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "review-normal", "work-normal", 1, "AUXILIARY_ANALYSIS", "aux-normal", 1, "CONFIRM", null, null, "仅验证流程", "v0-reviewer", now(), "SYNTHETIC_PROCESS_TEST", "Y");
        insert(c, "INSERT INTO enf_idempotency_record VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "v0-user", "OPEN", "normal-key", "normal-fingerprint", "work-normal", "WORK_ITEM", "work-normal", 1, "{}", now());
    }

    private static void insertConflictAndUnavailableEvidence(Connection c) throws SQLException {
        source(c, "clue-source-2", "CLUE", "V0-SYN-CLUE-002", "body");
        source(c, "evidence-source-3", "EVIDENCE", "V0-SYN-EVD-003", "field:eventDate");
        source(c, "evidence-source-7", "EVIDENCE", "V0-SYN-EVD-007", "field:content");
        insert(c, "INSERT INTO enf_work_item VALUES (?, ?, ?, ?, ?, ?, ?)", "work-conflict", "clue-source-2", "org-v0", "v0-user", "SYNTHETIC_V0", now(), 1);
        insert(c, "INSERT INTO enf_clue_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "analysis-conflict", "work-conflict", 1, "clue-source-2", null, "MACHINE", "synthetic-analysis", "TO_BE_EVALUATED", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_clue_issue VALUES (?, ?, ?, ?, ?, ?, ?)", "clue-conflict", "analysis-conflict", 1, "CONFLICT", "occurrenceTime", "两个来源时间冲突", "evidence-source-3,evidence-source-7");
        insert(c, "INSERT INTO enf_clue_conflict_candidate VALUES (?, ?, ?, ?, ?, ?, ?)", "candidate-conflict-1", "clue-conflict", 1, "2026-08-03", "2026-08-03", "OCCURRENCE", "evidence-source-3");
        insert(c, "INSERT INTO enf_clue_conflict_candidate VALUES (?, ?, ?, ?, ?, ?, ?)", "candidate-conflict-2", "clue-conflict", 2, "2026-08-04", "2026-08-04", "OCCURRENCE", "evidence-source-7");
        insert(c, "INSERT INTO enf_evidence_ref VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "evidence-conflict", "work-conflict", 1, 1, "evidence-source-3", "clue-source-2", "controlled://V0-SYN-EVD-003", null, "NOT_PROVIDED", "2026-08-03", null, "2026-08-20", "AVAILABLE", null, null, now());
        insert(c, "INSERT INTO enf_evidence_ref VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "evidence-unavailable", "work-conflict", 2, 1, "evidence-source-7", "clue-source-2", "controlled://V0-SYN-EVD-007", null, "NOT_PROVIDED", null, null, "2026-08-20", "UNSUPPORTED_CONTENT", null, null, now());
        insert(c, "INSERT INTO enf_relation_set VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", "relations-conflict", "work-conflict", 1, "analysis-conflict", "rule-normal", null, "HAS_CONFLICTS", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_relation_input VALUES (?, ?, ?)", "relations-conflict", "evidence-conflict", 1);
        insert(c, "INSERT INTO enf_relation_input VALUES (?, ?, ?)", "relations-conflict", "evidence-unavailable", 2);
        insert(c, "INSERT INTO enf_relation_issue VALUES (?, ?, ?, ?, ?, ?, ?)", "issue-unprocessed", "relations-conflict", 1, "UNPROCESSED", "evidence-unavailable", "格式不可处理", "evidence-source-7");
        insert(c, "INSERT INTO enf_aux_analysis VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "aux-conflict", "work-conflict", 1, "analysis-conflict", "relations-conflict", "rule-normal", null, null, "MATERIALS_INSUFFICIENT", "Y", "synthetic-v1", now());
        insert(c, "INSERT INTO enf_analysis_issue VALUES (?, ?, ?, ?, ?, ?)", "analysis-gap", "aux-conflict", 1, "HISTORICAL_STATUS_MISSING", "历史权利状态待核实", "right-status");
        insert(c, "INSERT INTO enf_review_impact VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", "impact-1", "work-conflict", 1, "SOURCE_UNAVAILABLE", "evidence-source-7", "v1", null, "AUXILIARY_ANALYSIS", "aux-conflict", 1, "来源失效需复核", now());
    }

    private static void source(Connection c, String id, String kind, String objectId, String location) throws SQLException {
        insert(c, "INSERT INTO enf_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)", id, kind, "v0-synthetic-enforcement", objectId, "v1", location, now());
    }
    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
    private static Timestamp now() { return Timestamp.from(Instant.parse("2026-09-15T08:00:00Z")); }
    private static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); }
    }
    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }
    private static void executeScript(Connection c, String relativePath) throws Exception {
        String script = Files.readString(Path.of(relativePath));
        try (Statement s = c.createStatement()) {
            for (String sql : script.split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) s.execute(sql);
        }
    }
    private static void executeIgnoringMissing(Connection c, String relativePath) throws Exception {
        try { executeScript(c, relativePath); } catch (SQLException ignored) { }
    }
}
