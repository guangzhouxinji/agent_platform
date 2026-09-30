package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductTaskDm8PhysicalTest {
    private static final String ISSUER = "https://task-physical.example.org";
    private static final String SHA = "a".repeat(64);

    @Test void goalCancelRestartAndReverseInIsolatedPhysicalSchema() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && admin != null && password != null,
                "Isolated DM8 credentials are not configured");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "CT" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        try {
            try (var control = DriverManager.getConnection(url, admin, password);
                 var statement = control.createStatement()) {
                statement.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
                created = true;
                statement.execute("GRANT RESOURCE TO " + schema);
            }
            var source = new DriverManagerDataSource(url, schema, secret);
            try (var connection = source.getConnection()) {
                try {
                    for (String name : new String[] {"V032__product_identity.sql",
                            "V034__product_consultation_initial_task.sql",
                            "V041__product_task_authorization_version.sql",
                            "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                            "V044__product_task_session_resume.sql",
                            "V045__product_task_execution_fence.sql",
                            "V046__product_workload_subject.sql",
                            "V047__product_task_worker_delegation.sql",
                            "V048__product_task_execution_reconciliation.sql",
                            "V061__product_worker_execution_request.sql"}) script(connection, name);
                    JdbcTemplate db = new JdbcTemplate(source);
                    Instant now = Instant.now();
                    Timestamp start = Timestamp.from(now);
                    Timestamp expiry = Timestamp.from(now.plusSeconds(3600));
                    db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                                    + "credential_version,created_at) VALUES(?,?,?,?,?,1,?)",
                            ISSUER, "member", "member", "encoded", "ACTIVE", start);
                    db.update("INSERT INTO con3_organization VALUES('org','Organization','ACTIVE',1,?)", start);
                    String taskId = UUID.randomUUID().toString();
                    String scopeId = UUID.randomUUID().toString();
                    String sessionId = UUID.randomUUID().toString();
                    String messageId = UUID.randomUUID().toString();
                    String goalId = UUID.randomUUID().toString();
                    String inputId = UUID.randomUUID().toString();
                    db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                                    + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,"
                                    + "created_at) VALUES(?,?,?,?,?,1,?,?,1,?,?)",
                            taskId, "org", ISSUER, "member", "AWAITING_GOAL", expiry, "policy", SHA, start);
                    db.update("INSERT INTO con3_data_scope VALUES(?,?,'org',1,'TASK_METADATA')", scopeId, taskId);
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
                    assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        db.update("INSERT INTO con3_session VALUES(?,?,'ACTIVE',?,?)",
                                sessionId, taskId, start, expiry);
                        db.update("INSERT INTO con3_message VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                messageId, taskId, sessionId, 1, ISSUER, "member", "中文检索目标", SHA,
                                start, expiry, "policy", 1, SHA);
                        throw new IllegalStateException("uncommitted command");
                    })).isInstanceOf(IllegalStateException.class);
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_message", Integer.class)).isZero();
                    tx.executeWithoutResult(status -> {
                        assertThat(db.update("UPDATE con3_task SET state='IN_PROGRESS',version=2 "
                                + "WHERE task_id=? AND version=1 AND state='AWAITING_GOAL'", taskId)).isEqualTo(1);
                        db.update("INSERT INTO con3_session VALUES(?,?,'ACTIVE',?,?)", sessionId, taskId,
                                start, expiry);
                        db.update("INSERT INTO con3_message VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", messageId,
                                taskId, sessionId, 1, ISSUER, "member", "中文检索目标", SHA, start, expiry,
                                "policy", 1, SHA);
                        db.update("INSERT INTO con3_goal_revision VALUES(?,1,?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?,?,?,?,?)",
                                goalId, taskId, "org", "SEARCH", "中文检索目标", "[\"source\"]", "[]",
                                messageId, ISSUER, "member", start, expiry, SHA, "policy", 1, SHA);
                        db.update("INSERT INTO con3_goal_current VALUES(?,?,1,2)", taskId, goalId);
                        db.update("INSERT INTO con3_search_input VALUES(?,?,?,1,2,?,?,?,?,?,?)", inputId,
                                taskId, goalId, "中文检索目标", "[\"source\"]", "[\"TITLE\"]", SHA,
                                start, expiry);
                        db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'GOAL_CONFIRMED',?,?,?,?,?)",
                                taskId, ISSUER, "member", "goal-command", SHA, goalId, 2, start);
                        db.update("INSERT INTO con3_event VALUES(?,?,'GOAL_CONFIRMED',?,?,?,?)",
                                UUID.randomUUID().toString(), taskId, "member", 2, SHA, start);
                    });
                    var reopened = new JdbcTemplate(new DriverManagerDataSource(url, schema, secret));
                    assertThat(reopened.queryForObject("SELECT m.raw_text FROM con3_goal_current c "
                                    + "JOIN con3_goal_revision g ON g.task_id=c.task_id AND g.goal_id=c.goal_id "
                                    + "AND g.revision=c.revision JOIN con3_search_input i ON i.task_id=c.task_id "
                                    + "AND i.goal_id=c.goal_id AND i.task_version=c.task_version "
                                    + "JOIN con3_message m ON m.task_id=c.task_id AND m.message_id=g.source_message_id "
                                    + "WHERE c.task_id=?", String.class, taskId)).isEqualTo("中文检索目标");
                    db.update("INSERT INTO con3_execution_request VALUES(?,?,?,?,?,?,?,?,?,?)",
                            taskId, "worker", "acquire-once", SHA, inputId, UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), 1, expiry, start);
                    assertThat(reopened.queryForObject("SELECT fencing_token FROM con3_execution_request "
                            + "WHERE task_id=? AND service_subject='worker' AND request_id='acquire-once'",
                            Long.class, taskId)).isEqualTo(1);
                    assertThatThrownBy(() -> db.update("INSERT INTO con3_execution_request "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)", taskId, "worker", "acquire-once", SHA,
                            inputId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            1, expiry, start)).isInstanceOf(Exception.class);
                    tx.executeWithoutResult(status -> {
                        assertThat(db.update("UPDATE con3_task SET state='USER_CANCELLED',version=3,"
                                + "cancel_epoch=cancel_epoch+1 WHERE task_id=? AND version=2 "
                                + "AND cancel_epoch=0 AND state='IN_PROGRESS'", taskId)).isEqualTo(1);
                        db.update("UPDATE con3_session SET status='ENDED' WHERE task_id=?", taskId);
                        db.update("INSERT INTO con3_command_receipt VALUES(?,?,?,'USER_CANCELLED',?,?,?,?,?)",
                                taskId, ISSUER, "member", "cancel-command", SHA, taskId, 3, start);
                        db.update("INSERT INTO con3_event VALUES(?,?,'USER_CANCELLED',?,?,?,?)",
                                UUID.randomUUID().toString(), taskId, "member", 3, SHA, start);
                    });
                    assertThat(reopened.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id=?",
                            Long.class, taskId)).isEqualTo(1);
                    assertThat(db.update("UPDATE con3_task SET version=4 WHERE task_id=? AND version=2 "
                            + "AND cancel_epoch=0", taskId)).isZero();
                    assertThat(reopened.queryForObject("SELECT COUNT(*) FROM con3_command_receipt WHERE task_id=?",
                            Integer.class, taskId)).isEqualTo(2);
                    for (String table : new String[] {"con3_execution_request", "con3_execution_checkpoint", "con3_execution_lease",
                            "con3_command_receipt", "con3_search_input",
                            "con3_goal_current", "con3_goal_revision", "con3_message", "con3_session",
                            "con3_event", "con3_data_scope", "con3_task", "con3_organization", "id2_subject"})
                        db.update("DELETE FROM " + table);
                    for (String name : new String[] {"U061__product_worker_execution_request.sql",
                            "U048__product_task_execution_reconciliation.sql",
                            "U047__product_task_worker_delegation.sql",
                            "U046__product_workload_subject.sql",
                            "U045__product_task_execution_fence.sql",
                            "U044__product_task_session_resume.sql",
                            "U043__product_task_cancel_epoch.sql", "U042__product_consultation_goal.sql",
                            "U041__product_task_authorization_version.sql",
                            "U034__product_consultation_initial_task.sql", "U032__product_identity.sql"})
                        script(connection, name);
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'CON3_%'",
                            Integer.class)).isZero();
                } finally {
                    for (String table : new String[] {"con3_execution_request", "con3_execution_reconciliation",
                            "con3_execution_dispatch", "con3_worker_delegation_receipt",
                            "con3_worker_delegation_event", "con3_worker_delegation",
                            "id2_workload_request", "id2_workload_event",
                            "id2_workload", "con3_execution_checkpoint", "con3_execution_lease",
                            "con3_command_receipt", "con3_search_input",
                            "con3_goal_current", "con3_goal_revision", "con3_message", "con3_session",
                            "con3_event", "con3_initial_receipt", "con3_initial_task", "con3_data_scope",
                            "con3_task", "con3_organization", "id2_grant_event", "id2_current_epoch",
                            "id2_grant", "id2_session", "id2_key_event", "id2_signing_key", "id2_key_control",
                            "id2_subject_event", "id2_subject"}) {
                        try (var drop = connection.createStatement()) { drop.execute("DROP TABLE " + table + " CASCADE"); }
                        catch (SQLException absent) { /* A partial migration can leave a table absent. */ }
                    }
                }
            }
        } finally {
            if (created) try (var control = DriverManager.getConnection(url, admin, password);
                             var statement = control.createStatement()) {
                statement.execute("DROP USER " + schema);
            }
        }
    }

    private static void script(java.sql.Connection connection, String name) {
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
    }
}
