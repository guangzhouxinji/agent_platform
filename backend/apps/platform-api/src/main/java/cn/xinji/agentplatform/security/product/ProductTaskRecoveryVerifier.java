package cn.xinji.agentplatform.security.product;

import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only restoration gate for the Task-owned relational closure and independent grant authority. */
public final class ProductTaskRecoveryVerifier {
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;

    public ProductTaskRecoveryVerifier(JdbcTemplate db, ProductGrantWatermark watermark) {
        this.db = db;
        this.watermark = watermark;
    }

    public void verify() {
        requireTable("ID2_WORKLOAD");
        requireTable("CON3_WORKER_DELEGATION");
        requireTable("CON3_EXECUTION_RECONCILIATION");
        requireTable("CON3_EXECUTION_DISPATCH");
        long generation = watermark.currentGeneration();
        requireZero("SELECT COUNT(*) FROM con3_task t WHERE "
                + "(t.state='IN_PROGRESS' AND NOT EXISTS (SELECT 1 FROM con3_goal_current c "
                + "WHERE c.task_id=t.task_id AND c.task_version=t.version)) OR "
                + "(t.state='AWAITING_GOAL' AND EXISTS (SELECT 1 FROM con3_goal_current c "
                + "WHERE c.task_id=t.task_id))");
        requireZero("SELECT COUNT(*) FROM con3_goal_current c LEFT JOIN con3_search_input i "
                + "ON i.task_id=c.task_id AND i.goal_id=c.goal_id AND i.goal_revision=c.revision "
                + "AND i.task_version=c.task_version WHERE i.input_id IS NULL");
        requireZero("SELECT COUNT(*) FROM con3_execution_lease l JOIN con3_task t "
                + "ON t.task_id=l.task_id WHERE l.status='ACTIVE' AND "
                + "(t.state<>'IN_PROGRESS' OR t.version<>l.task_version "
                + "OR t.cancel_epoch<>l.cancel_epoch OR NOT EXISTS "
                + "(SELECT 1 FROM con3_goal_current c JOIN con3_search_input i "
                + "ON i.task_id=c.task_id AND i.goal_id=c.goal_id "
                + "AND i.goal_revision=c.revision AND i.task_version=c.task_version "
                + "WHERE c.task_id=l.task_id AND i.input_id=l.input_id))");
        requireZero("SELECT COUNT(*) FROM con3_execution_checkpoint p "
                    + "LEFT JOIN con3_execution_lease l ON l.task_id=p.task_id "
                    + "AND l.attempt_id=p.attempt_id AND l.fencing_token=p.fencing_token "
                    + "LEFT JOIN con3_execution_reconciliation r ON r.task_id=p.task_id "
                    + "AND r.old_attempt_id=p.attempt_id AND r.old_token=p.fencing_token "
                    + "WHERE l.task_id IS NULL AND r.task_id IS NULL");
        requireZero("SELECT COUNT(*) FROM con3_execution_reconciliation r "
                    + "LEFT JOIN con3_execution_lease l ON l.task_id=r.task_id "
                    + "AND l.attempt_id=r.new_attempt_id AND l.fencing_token=r.new_token "
                    + "LEFT JOIN con3_execution_reconciliation n ON n.task_id=r.task_id "
                    + "AND n.old_attempt_id=r.new_attempt_id AND n.old_token=r.new_token "
                    + "WHERE (l.task_id IS NULL AND n.task_id IS NULL) OR "
                    + "COALESCE((SELECT MAX(p.sequence_no) FROM con3_execution_checkpoint p "
                    + "WHERE p.task_id=r.task_id AND p.attempt_id=r.old_attempt_id "
                    + "AND p.fencing_token=r.old_token),0)<>r.last_checkpoint_sequence OR "
                    + "(r.last_checkpoint_sequence>0 AND NOT EXISTS "
                    + "(SELECT 1 FROM con3_execution_checkpoint p WHERE p.task_id=r.task_id "
                    + "AND p.attempt_id=r.old_attempt_id AND p.fencing_token=r.old_token "
                    + "AND p.sequence_no=r.last_checkpoint_sequence "
                    + "AND p.result_ref=r.last_checkpoint_ref AND p.result_sha=r.last_checkpoint_sha))");
        requireZero("SELECT COUNT(*) FROM con3_execution_dispatch");
        requireZero("SELECT COUNT(*) FROM con3_command_receipt r JOIN con3_task t "
                + "ON t.task_id=r.task_id WHERE r.result_version>t.version OR "
                + "(r.operation='MESSAGE_ACCEPTED' AND NOT EXISTS "
                + "(SELECT 1 FROM con3_message m WHERE m.task_id=r.task_id AND m.message_id=r.result_id)) OR "
                + "(r.operation='GOAL_CONFIRMED' AND NOT EXISTS "
                + "(SELECT 1 FROM con3_goal_revision g WHERE g.task_id=r.task_id "
                + "AND g.goal_id=r.result_id)) OR "
                + "(r.operation='SESSION_RESUMED' AND NOT EXISTS "
                + "(SELECT 1 FROM con3_session s WHERE s.task_id=r.task_id "
                + "AND s.session_id=r.result_id)) OR "
                + "(r.operation='USER_CANCELLED' AND r.result_id<>r.task_id)");
        requireZero("SELECT COUNT(*) FROM con3_event e JOIN con3_task t ON t.task_id=e.task_id "
                + "WHERE e.version>t.version");
        watermark.assertUnchanged(generation);
    }

    private void requireZero(String sql) {
        Integer violations = db.queryForObject(sql, Integer.class);
        if (violations == null || violations != 0)
            throw new IllegalStateException("Recovered Task references are inconsistent");
    }

    private void requireTable(String name) {
        try (var connection = db.getDataSource().getConnection();
             var tables = connection.getMetaData().getTables(null, connection.getSchema(),
                     name, new String[] {"TABLE"})) {
            if (!tables.next()) throw new IllegalStateException("Task recovery migration missing: " + name);
        } catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("Task recovery schema cannot be inspected", unavailable);
        }
    }
}
