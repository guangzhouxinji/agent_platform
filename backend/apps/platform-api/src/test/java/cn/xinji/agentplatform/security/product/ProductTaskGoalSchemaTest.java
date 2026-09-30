package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductTaskGoalSchemaTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String SHA = "a".repeat(64);

    @Test void goalInputCannotCrossTaskOrMessageAndContentVersionDoesNotChangeAuthorization() throws Exception {
        JdbcDataSource source = database();
        for (String migration : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V041__product_task_authorization_version.sql",
                "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                "V044__product_task_session_resume.sql",
                "V045__product_task_execution_fence.sql",
                "V072__product_search_metadata_filters.sql"}) script(source, migration);
        JdbcTemplate db = new JdbcTemplate(source);
        Instant now = Instant.now();
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,?,?,?,?,1,?)",
                ISSUER, "member", "member", "encoded", "ACTIVE", Timestamp.from(now));
        db.update("INSERT INTO con3_organization VALUES('org','Organization','ACTIVE',1,?)", Timestamp.from(now));
        for (String id : new String[] {"task-1", "task-2"})
            db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                    + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                    + "VALUES(?,'org',?,?,'AWAITING_GOAL',1,?,'retention',1,?,?)",
                    id, ISSUER, "member", Timestamp.from(now.plusSeconds(3600)), SHA, Timestamp.from(now));
        db.update("INSERT INTO con3_session VALUES('session-1','task-1','ACTIVE',?,?)",
                Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)));
        db.update("INSERT INTO con3_message VALUES('message-1','task-1','session-1',1,?,?,?, ?, ?, ?, 'retention',1,?)",
                ISSUER, "member", "binder coating", SHA, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1800)), SHA);
        db.update("INSERT INTO con3_goal_revision VALUES('goal-1',1,'task-1','org','SEARCH','CONFIRMED',"
                + "'binder coating','[\"source-1\"]','[]','message-1',?,?,?, ?, ?, 'retention',1,?)",
                ISSUER, "member", Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)), SHA, SHA);
        db.update("INSERT INTO con3_goal_current VALUES('task-1','goal-1',1,2)");
        db.update("INSERT INTO con3_search_input VALUES('input-1','task-1','goal-1',1,2,"
                + "'binder coating','[\"source-1\"]','[\"TITLE\"]',?,?,?,NULL)",
                SHA, Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)));
        assertThatThrownBy(() -> db.update("INSERT INTO con3_goal_current VALUES('task-2','goal-1',1,2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO con3_search_input VALUES('input-2','task-2',"
                + "'goal-1',1,2,'binder','[]','[]',?,?,?,?)",
                SHA, Timestamp.from(now), Timestamp.from(now.plusSeconds(1800)), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        db.update("UPDATE con3_task SET state='IN_PROGRESS',version=2 WHERE task_id='task-1'");
        assertThat(db.queryForObject("SELECT authorization_version FROM con3_task WHERE task_id='task-1'",
                Long.class)).isEqualTo(1);
        db.update("UPDATE con3_task SET state='USER_CANCELLED',version=3,cancel_epoch=cancel_epoch+1 "
                + "WHERE task_id='task-1'");
        assertThat(db.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id='task-1'",
                Long.class)).isEqualTo(1);
    }

    @Test void emptyRollbackRestoresInitialTaskConstraints() throws Exception {
        JdbcDataSource source = database();
        for (String migration : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V041__product_task_authorization_version.sql",
                "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                "V044__product_task_session_resume.sql",
                "V045__product_task_execution_fence.sql",
                "V072__product_search_metadata_filters.sql"}) script(source, migration);
        script(source, "U072__product_search_metadata_filters.sql");
        script(source, "U045__product_task_execution_fence.sql");
        script(source, "U044__product_task_session_resume.sql");
        script(source, "U043__product_task_cancel_epoch.sql");
        script(source, "U042__product_consultation_goal.sql");
        JdbcTemplate db = new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class)).isZero();
        assertThatThrownBy(() -> db.update("INSERT INTO con3_event VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "missing-task", "GOAL_CONFIRMED", "actor", 1, SHA,
                Timestamp.from(Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
        script(source, "U041__product_task_authorization_version.sql");
    }

    private static JdbcDataSource database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task-goal-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String migration) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/" + migration)));
        }
    }
}
