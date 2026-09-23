package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ConsultationDm8SchemaTest {
    @Test
    void initializesAndRollsBackModelConfigurationSchema() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeIgnoringMissing(connection, "db/dm8/U017__consultation_model_configuration_v1.sql");
            executeScript(connection, "db/dm8/V017__consultation_model_configuration_v1.sql");
            assertEquals(0, count(connection, "con_model_configuration"));
            assertEquals(0, count(connection, "con_model_configuration_audit"));
            executeScript(connection, "db/dm8/U017__consultation_model_configuration_v1.sql");
            executeScript(connection, "db/dm8/V017__consultation_model_configuration_v1.sql");
            executeScript(connection, "db/dm8/U017__consultation_model_configuration_v1.sql");
        }
    }

    @Test
    void initializesPersistsV0ScenariosEnforcesKeysAndRollsBack() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String user = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            executeIgnoringMissing(connection, "db/dm8/U001__consultation_mvp_v0.sql");
            executeScript(connection, "db/dm8/V001__consultation_mvp_v0.sql");

            insertNormalScenario(connection);
            insertClarificationScenario(connection);
            insertDeniedAndUnavailableScenarios(connection);

            assertEquals(4, count(connection, "con_consultation"));
            assertEquals(1, count(connection, "con_answer_candidate"));
            assertEquals(1, count(connection, "con_answer_source_link"));
            assertEquals(0, countWhere(connection, "con_retrieval_candidate", "retrieval_request_id = 'req-denied'"));
            assertEquals(0, countWhere(connection, "con_answer_candidate", "answer_id = 'answer-unavailable'"));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO con_idempotency_record VALUES (?, ?, ?, ?, ?, ?)",
                    "v0-authorized-user", "normal-key", "different-fingerprint", "con-normal", "answer-normal", now()));
            assertThrows(SQLException.class, () -> insert(connection,
                    "INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "candidate-orphan", "missing-request", "pat-normal", "x", "x", null, "USABLE", null));

            executeScript(connection, "db/dm8/U001__consultation_mvp_v0.sql");
            executeScript(connection, "db/dm8/V001__consultation_mvp_v0.sql");
            executeScript(connection, "db/dm8/U001__consultation_mvp_v0.sql");
        }
    }

    private static void insertNormalScenario(Connection connection) throws SQLException {
        insertConsultation(connection, "con-normal", "v0-authorized-user");
        insertSource(connection, "input-normal", "USER_MESSAGE", "consultation", "con-normal/input-1", "v1", "message:1");
        insert(connection, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", "input-normal-1", "con-normal", 1,
                "请检索电池极片涂布中水性粘结剂和双区烘干的技术参考。", "input-normal", now());
        insertSource(connection, "decision-normal", "ACCESS_DECISION", "v0-auth", "allow-normal", "v1", "decision");
        insert(connection, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "req-normal", "con-normal", "水性粘结剂；双区烘干", "TECHNICAL_REFERENCE", "中国发明专利", "ALLOWED", "decision-normal", now(), "COMPLETED", "V0 合成中国发明专利集", null, now());
        insertSource(connection, "pat-normal", "PATENT_DOCUMENT", "v0-synthetic-patent", "V0-SYN-PAT-001", "v1", "claim:1");
        insert(connection, "INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "candidate-normal", "req-normal", "pat-normal", "水性粘结剂、双区烘干", "与确认特征对应", "keyword-match", "USABLE", null);
        insert(connection, "INSERT INTO con_assisted_answer (answer_id,consultation_id,retrieval_request_id,outcome_type,content,limitations,professional_review_required,professional_review_reason,answer_version,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "answer-normal", "con-normal", "req-normal", "ANSWER_READY", "这是技术参考辅助回答。", "不构成正式专业意见", "Y", "拟正式采用前需复核", 1, now());
        insert(connection, "INSERT INTO con_answer_candidate VALUES (?, ?)", "answer-normal", "candidate-normal");
        insert(connection, "INSERT INTO con_answer_source_link VALUES (?, ?, ?, ?)", "answer-source-normal", "answer-normal", "pat-normal", "claim:1");
        insert(connection, "INSERT INTO con_idempotency_record VALUES (?, ?, ?, ?, ?, ?)", "v0-authorized-user", "normal-key", "normal-fingerprint", "con-normal", "answer-normal", now());
    }

    private static void insertClarificationScenario(Connection connection) throws SQLException {
        insertConsultation(connection, "con-clarification", "v0-authorized-user");
        insertSource(connection, "input-conflict-a", "CONTROLLED_ATTACHMENT", "consultation", "con-clarification/a", "v1", "page:1");
        insertSource(connection, "input-conflict-b", "CONTROLLED_ATTACHMENT", "consultation", "con-clarification/b", "v1", "page:1");
        insert(connection, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", "input-conflict-1", "con-clarification", 1, "附件 A 载明温度 80。", "input-conflict-a", now());
        insert(connection, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", "input-conflict-2", "con-clarification", 2, "附件 B 载明温度 120。", "input-conflict-b", now());
        insert(connection, "INSERT INTO con_information_issue VALUES (?, ?, ?, ?, ?, ?)", "issue-purpose", "con-clarification", "MISSING", "缺少检索目的", "input-conflict-a", null);
        insert(connection, "INSERT INTO con_information_issue VALUES (?, ?, ?, ?, ?, ?)", "issue-temperature", "con-clarification", "CONFLICT", "温度条件冲突", "input-conflict-b", null);
        insert(connection, "INSERT INTO con_assisted_answer (answer_id,consultation_id,retrieval_request_id,outcome_type,content,limitations,professional_review_required,professional_review_reason,answer_version,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "answer-clarification", "con-clarification", null, "NEEDS_CLARIFICATION", "请确认检索目的和温度条件。", "未执行检索", "N", null, 1, now());
    }

    private static void insertDeniedAndUnavailableScenarios(Connection connection) throws SQLException {
        insertConsultation(connection, "con-denied", "v0-denied-user");
        insertSource(connection, "input-denied", "USER_MESSAGE", "consultation", "con-denied/input-1", "v1", "message:1");
        insert(connection, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", "input-denied-1", "con-denied", 1, "检索技术参考。", "input-denied", now());
        insertSource(connection, "decision-denied", "ACCESS_DECISION", "v0-auth", "deny-user", "v1", "decision");
        insert(connection, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "req-denied", "con-denied", "技术特征", "TECHNICAL_REFERENCE", null, "DENIED", "decision-denied", now(), "NOT_EXECUTED", null, "ACCESS_DENIED", now());
        insert(connection, "INSERT INTO con_assisted_answer (answer_id,consultation_id,retrieval_request_id,outcome_type,content,limitations,professional_review_required,professional_review_reason,answer_version,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "answer-denied", "con-denied", "req-denied", "ACCESS_DENIED", "当前无权访问相关资料。", "未读取来源内容", "N", null, 1, now());

        insertConsultation(connection, "con-unavailable", "v0-authorized-user");
        insertSource(connection, "input-unavailable", "USER_MESSAGE", "consultation", "con-unavailable/input-1", "v1", "message:1");
        insert(connection, "INSERT INTO con_user_input VALUES (?, ?, ?, ?, ?, ?)", "input-unavailable-1", "con-unavailable", 1, "检索技术参考。", "input-unavailable", now());
        insertSource(connection, "decision-unavailable", "ACCESS_DECISION", "v0-auth", "allow-unavailable", "v1", "decision");
        insert(connection, "INSERT INTO con_retrieval_request VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "req-unavailable", "con-unavailable", "技术特征", "TECHNICAL_REFERENCE", null, "ALLOWED", "decision-unavailable", now(), "PARTIAL", "V0 合成来源", "SOURCE_UNAVAILABLE", now());
        insertSource(connection, "pat-unavailable", "PATENT_DOCUMENT", "v0-synthetic-patent", "V0-SYN-PAT-003", "v1", "claim:1");
        insert(connection, "INSERT INTO con_retrieval_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "candidate-unavailable", "req-unavailable", "pat-unavailable", "已失效来源的可展示摘要", "原匹配依据", "keyword-match", "UNAVAILABLE", "SOURCE_UNAVAILABLE");
        insert(connection, "INSERT INTO con_assisted_answer (answer_id,consultation_id,retrieval_request_id,outcome_type,content,limitations,professional_review_required,professional_review_reason,answer_version,created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "answer-unavailable", "con-unavailable", "req-unavailable", "SOURCE_UNAVAILABLE", "来源当前不可用，无法形成有依据的回答。", "不等于没有相关技术", "N", null, 1, now());
    }

    private static void insertConsultation(Connection connection, String id, String requester) throws SQLException {
        insert(connection, "INSERT INTO con_consultation VALUES (?, ?, ?, ?)", id, requester, "PATENT_TECHNICAL_REFERENCE", now());
    }

    private static void insertSource(Connection connection, String id, String kind, String system, String objectId, String version, String location) throws SQLException {
        insert(connection, "INSERT INTO con_source_locator VALUES (?, ?, ?, ?, ?, ?, ?)", id, kind, system, objectId, version, location, now());
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static Timestamp now() { return Timestamp.from(Instant.parse("2026-09-15T10:30:00Z")); }

    private static int count(Connection connection, String table) throws SQLException { return countWhere(connection, table, "1 = 1"); }

    private static int countWhere(Connection connection, String table, String predicate) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
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
        try { executeScript(connection, relativePath); } catch (SQLException ignored) { }
    }
}
