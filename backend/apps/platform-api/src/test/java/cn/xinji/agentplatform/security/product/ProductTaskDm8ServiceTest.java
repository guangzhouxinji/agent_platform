package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductTaskDm8ServiceTest {
    private static final String ISSUER = "https://task-service-physical.example.org";
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void currentGrantGoalResumeCancelAndRestartUseProductServicesOnDm8() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        String authorityUrl = System.getenv("IDENTITY_AUTHORITY_DM8_JDBC_URL");
        String authorityAdmin = System.getenv("IDENTITY_AUTHORITY_DM8_USER");
        String authorityPassword = System.getenv("IDENTITY_AUTHORITY_DM8_PASSWORD");
        String archiveContainer = System.getenv("DM8_PRIMARY_CONTAINER");
        Assumptions.assumeTrue(url != null && admin != null && password != null
                        && authorityUrl != null && authorityAdmin != null && authorityPassword != null
                        && archiveContainer != null && !url.equals(authorityUrl),
                "Two isolated DM8 instances and primary archive container are required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "CS" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        boolean authorityCreated = false;
        try {
            try (var control = DriverManager.getConnection(url, admin, password);
                 var statement = control.createStatement()) {
                statement.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
                created = true;
                statement.execute("GRANT RESOURCE TO " + schema);
            }
            try (var control = DriverManager.getConnection(authorityUrl, authorityAdmin, authorityPassword);
                 var statement = control.createStatement()) {
                statement.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
                authorityCreated = true;
                statement.execute("GRANT RESOURCE TO " + schema);
            }
            var source = new DriverManagerDataSource(url, schema, secret);
            var authoritySource = new DriverManagerDataSource(authorityUrl, schema, secret);
            try (var connection = source.getConnection(); var authorityConnection = authoritySource.getConnection()) {
                try {
                    for (String name : new String[] {"V032__product_identity.sql",
                            "V034__product_consultation_initial_task.sql",
                            "V036__product_source_candidates.sql", "V037__product_source_admission.sql",
                            "V039__product_source_verification.sql",
                            "V041__product_task_authorization_version.sql",
                            "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                            "V044__product_task_session_resume.sql",
                            "V045__product_task_execution_fence.sql",
                            "V046__product_workload_subject.sql",
                            "V047__product_task_worker_delegation.sql",
                            "V048__product_task_execution_reconciliation.sql",
                            "V072__product_search_metadata_filters.sql"})
                        ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
                    var db = new JdbcTemplate(source);
                    Instant now = Instant.now();
                    db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                                    + "credential_version,created_at) VALUES(?,?,?,?,?,1,?)",
                            ISSUER, "member", "member", "encoded", "ACTIVE", Timestamp.from(now));
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
                    String kid = new ProductSigningKeys(db, tx, ISSUER,
                            Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(5),
                            Clock.systemUTC()).rotate("maintainer");
                    db.update("INSERT INTO id2_session(sid,jti,issuer,subject_id,client_id,kid,issued_at,"
                                    + "expires_at,status,revoked_at) VALUES(?,?,?,?,?,?,?,?,?,NULL)",
                            "session", "jti", ISSUER, "member", "client", kid, Timestamp.from(now),
                            Timestamp.from(now.plusSeconds(3600)), "ACTIVE");
                    ScriptUtils.executeSqlScript(authorityConnection, new FileSystemResource(
                            Path.of("db/identity-authority/V001__grant_watermark.sql")));
                    ScriptUtils.executeSqlScript(authorityConnection, new FileSystemResource(
                            Path.of("db/identity-authority/V003__consultation_source_watermark.sql")));
                    var authority = new JdbcTemplate(authoritySource);
                    authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                            + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY_SHA);
                    var watermark = new ProductGrantWatermark(db, authority, ISSUER);
                    var grants = new ProductGrants(db, tx, Clock.systemUTC());
                    var directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
                    var retention = new ProductRetentionPolicy("task-policy", 1, "a".repeat(64), Duration.ofHours(2));
                    var initial = new ProductInitialTaskService(directory, grants, watermark, tx,
                            new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), ISSUER, retention);
                    var input = new ProductInitialTaskService.Input("org", "Organization", "member",
                            "task-policy", 1, now.plusSeconds(1800));
                    var task = initial.create(input, "initial", "maintainer", "org");
                    assertThat(initial.create(input, "initial", "maintainer", "org")).isEqualTo(task);
                    var write = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, "member", "org",
                                    "CONSULTATION", task.taskId(), task.dataScope(), "TASK", task.taskId(), 1,
                                    "WRITE", "TASK_WORK"), now.plusSeconds(1800), 0, "maintainer", "write",
                            "b".repeat(64)));
                    var jwt = Jwt.withTokenValue("signed-by-isolated-issuer-in-http-contract")
                            .header("alg", "RS256").issuer(ISSUER).subject("member")
                            .claim("jti", "jti").claim("sid", "session")
                            .issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
                    var access = new ProductConsultationAccess(db, watermark, ISSUER, Clock.systemUTC());
                    var goalPolicy = new ProductRetentionPolicy("goal-policy", 1, "c".repeat(64), Duration.ofHours(1));
                    var mapper = new ObjectMapper().findAndRegisterModules();
                    var goals = new ProductTaskGoalService(db, directory, access, tx, goalPolicy, mapper,
                            Clock.systemUTC(), 16384);
                    var reader = new ProductTaskGoalReader(db, directory, access, goalPolicy, mapper,
                            Clock.systemUTC());
                    var goalInput = new ProductTaskGoalService.Input(1, "中文目标", "SEARCH", "中文目标",
                            List.of("source"), List.of("TITLE"), List.of(), true, "goal-policy", 1);
                    var goal = goals.confirm(task.taskId(), jwt, "goal", goalInput);
                    assertThat(goals.confirm(task.taskId(), jwt, "goal", goalInput)).isEqualTo(goal);
                    assertThat(reader.current(task.taskId(), jwt).messageText()).isEqualTo("中文目标");
                    var fence = new ProductTaskExecutionFence(db, tx, Clock.systemUTC());
                    var lease = fence.acquire(task.taskId(), goal.inputId(), 2, "worker-a",
                            Duration.ofMinutes(5));
                    fence.checkpoint(task.taskId(), goal.inputId(), lease, 1, "committed-db-step",
                            "e".repeat(64));
                    assertThatThrownBy(() -> fence.publish(task.taskId(), goal.inputId(), lease,
                            () -> {
                                db.update("INSERT INTO con3_execution_checkpoint VALUES(?,?,?,?,?,?,?)",
                                        task.taskId(), lease.attemptId(), lease.token(), 2,
                                        "uncommitted-result", "f".repeat(64), Timestamp.from(Instant.now()));
                                throw new IllegalStateException("result transaction failed");
                            }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(db.queryForObject("SELECT status FROM con3_execution_lease WHERE task_id=?",
                            String.class, task.taskId())).isEqualTo("ACTIVE");
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_execution_checkpoint WHERE task_id=?",
                            Integer.class, task.taskId())).isEqualTo(1);
                    var expiredFence = new ProductTaskExecutionFence(db, tx,
                            Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));
                    assertThatThrownBy(() -> expiredFence.acquire(task.taskId(), goal.inputId(), 2,
                            "worker-b", Duration.ofMinutes(5))).isInstanceOf(IllegalStateException.class);
                    assertThat(db.queryForObject("SELECT status FROM con3_execution_lease WHERE task_id=?",
                            String.class, task.taskId())).isEqualTo("RECONCILE_REQUIRED");
                    var next = tx.execute(status -> expiredFence.reconcileNoDispatch(task.taskId(),
                            goal.inputId(), new ProductWorkerAccess.Authorized("worker-b", task.taskId(),
                                    goal.inputId(), 2), Duration.ofMinutes(5)));
                    assertThat(next.token()).isEqualTo(2);
                    assertThat(next.attemptId()).isNotEqualTo(lease.attemptId());
                    assertThat(db.queryForObject("SELECT last_checkpoint_sequence FROM "
                            + "con3_execution_reconciliation WHERE task_id=? AND old_token=1",
                            Long.class, task.taskId())).isEqualTo(1);
                    assertThatThrownBy(() -> fence.checkpoint(task.taskId(), goal.inputId(), lease,
                            2, "late-old", "e".repeat(64))).isInstanceOf(IllegalStateException.class);
                    var sessions = new ProductTaskSessionService(db, directory, access, reader, tx,
                            Clock.systemUTC());
                    var resumed = sessions.resume(task.taskId(), jwt, "resume", 2);
                    assertThat(sessions.resume(task.taskId(), jwt, "resume", 2)).isEqualTo(resumed);
                    var cancellation = new ProductTaskCancelService(db, directory, access, tx, Clock.systemUTC());
                    assertThat(cancellation.cancel(task.taskId(), jwt, "cancel", 2).cancelEpoch()).isEqualTo(1);
                    assertThat(cancellation.cancel(task.taskId(), jwt, "cancel", 2).taskVersion()).isEqualTo(3);
                    int[] published = {0};
                    assertThatThrownBy(() -> fence.publish(task.taskId(), goal.inputId(), lease,
                            () -> published[0]++ )).isInstanceOf(IllegalStateException.class);
                    assertThat(published[0]).isZero();
                    assertThatThrownBy(() -> sessions.resume(task.taskId(), jwt, "after-cancel", 2))
                            .isInstanceOf(ResponseStatusException.class);
                    var reopened = new JdbcTemplate(new DriverManagerDataSource(url, schema, secret));
                    assertThat(reopened.queryForObject("SELECT cancel_epoch FROM con3_task WHERE task_id=?",
                            Long.class, task.taskId())).isEqualTo(1);
                    assertThat(reopened.queryForObject("SELECT COUNT(*) FROM con3_command_receipt WHERE task_id=?",
                            Integer.class, task.taskId())).isEqualTo(3);
                    var recovery = new ProductTaskRecoveryVerifier(reopened, watermark);
                    recovery.verify();
                    reopened.update("UPDATE con3_task SET state='IN_PROGRESS' WHERE task_id=?", task.taskId());
                    assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
                    reopened.update("UPDATE con3_task SET state='USER_CANCELLED' WHERE task_id=?", task.taskId());
                    recovery.verify();
                    String oldDigest = authority.queryForObject(
                            "SELECT history_sha FROM id2_authority_watermark WHERE issuer=?", String.class, ISSUER);
                    try (var files = new ProductTaskDm8EvidenceCopy(db, tx);
                         var archive = new ProductTaskDm8Archive(archiveContainer, url, admin, password,
                            schema, "CR" + HexFormat.of().formatHex(random).toUpperCase(), secret)) {
                        var restored = new JdbcTemplate(new DriverManagerDataSource(url, archive.target(), secret));
                        new ProductTaskRecoveryVerifier(restored,
                                new ProductGrantWatermark(restored, authority, ISSUER)).verify();
                        var restoredConnections = new ProductTaskRecoveryCheck.Connections(url,
                                archive.target(), secret, archive.target(), authorityUrl, schema, secret,
                                schema, ISSUER, files.root(), 10_000_000);
                        ProductTaskRecoveryCheck.verify(restoredConnections);
                        files.removeOneOriginal();
                        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(restoredConnections))
                                .isInstanceOf(IllegalStateException.class);
                        files.restoreOneOriginal();
                        ProductTaskRecoveryCheck.verify(restoredConnections);
                        authority.update("INSERT INTO id4_source_watermark VALUES(?,1,'READY',NULL,?)",
                                "unmatched-source", EMPTY_SHA);
                        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(restoredConnections))
                                .isInstanceOf(IllegalStateException.class);
                        authority.update("DELETE FROM id4_source_watermark WHERE source_id=?",
                                "unmatched-source");
                        ProductTaskRecoveryCheck.verify(restoredConnections);
                        db.update("INSERT INTO con3_execution_dispatch VALUES(?,?,?,?, 'INTENT',?)",
                                task.taskId(), next.attemptId(), next.token(), "external-op",
                                Timestamp.from(Instant.now()));
                        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
                        watermark.change(() -> grants.revoke(write.grantId(), 1, "maintainer", "revoke",
                                "d".repeat(64)));
                        assertThatThrownBy(() -> cancellation.cancel(task.taskId(), jwt, "cancel", 2))
                                .isInstanceOf(ResponseStatusException.class);
                        assertThatThrownBy(() -> new ProductGrantWatermark(restored, authority, ISSUER))
                                .isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> ProductTaskRecoveryCheck.verify(restoredConnections))
                                .isInstanceOf(IllegalStateException.class);
                        authority.update("UPDATE id2_authority_watermark SET generation=generation-1,history_sha=? "
                                + "WHERE issuer=?", oldDigest, ISSUER);
                        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
                        assertThatThrownBy(() -> reader.current(task.taskId(), jwt))
                                .isInstanceOf(IllegalStateException.class);
                    }
                } finally {
                    for (String table : new String[] {"id4_source_watermark", "id2_maintenance_audit", "id2_maintainer",
                            "id2_authority_watermark"}) {
                        try (var drop = authorityConnection.createStatement()) { drop.execute("DROP TABLE " + table); }
                        catch (SQLException absent) { /* A partial migration can leave a table absent. */ }
                    }
                    for (String table : new String[] {"con3_worker_delegation_receipt",
                            "con3_worker_delegation_event", "con3_worker_delegation",
                            "id2_workload_request", "id2_workload_event", "id2_workload",
                            "con3_execution_reconciliation",
                            "con3_execution_dispatch", "con3_execution_checkpoint", "con3_execution_lease",
                            "con4_source_verification_request", "con4_source_verification",
                            "con4_source_authority_request", "con4_source_authority_event",
                            "con4_source_current", "con4_source_field_use", "con4_source_admission",
                            "con4_source_request", "con4_source_event", "con4_source_contract",
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
            if (authorityCreated) try (var control = DriverManager.getConnection(authorityUrl,
                    authorityAdmin, authorityPassword); var statement = control.createStatement()) {
                statement.execute("DROP USER " + schema);
            }
            if (created) try (var control = DriverManager.getConnection(url, admin, password);
                             var statement = control.createStatement()) {
                statement.execute("DROP USER " + schema);
            }
        }
    }
}
