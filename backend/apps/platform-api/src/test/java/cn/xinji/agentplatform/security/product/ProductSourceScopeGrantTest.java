package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSourceScopeGrantTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String SOURCE = "source-1";
    private static final String DECISION = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void exactScopeUsesCurrentTaskGrantAndSeparatesSourceAcrossTasks() throws Exception {
        JdbcDataSource main = database();
        JdbcDataSource independent = database();
        for (String name : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V038__product_task_source_scope.sql",
                "V039__product_source_verification.sql", "V041__product_task_authorization_version.sql",
                "V053__product_source_use_decision.sql", "V056__product_query_only_source_scope.sql"})
            script(main, "db/dm8/" + name);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        JdbcTemplate db = new JdbcTemplate(main);
        JdbcTemplate authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "member", "registered", "encoded");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(main));
        var grantWatermark = new ProductGrantWatermark(db, authority, ISSUER);
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var taskService = new ProductInitialTaskService(new JdbcProductTaskDirectory(db, Clock.systemUTC()),
                grants, grantWatermark, transaction, new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(), ISSUER,
                new ProductRetentionPolicy("approved-retention", 1, HASH, Duration.ofHours(2)));
        Instant expiry = Instant.now().plusSeconds(1800);
        var task = taskService.create(new ProductInitialTaskService.Input("organization", "Named",
                "member", "approved-retention", 1, expiry), "first-task", "maintainer", "organization");
        var sourceWatermark = new ProductSourceWatermark(db, authority, transaction);
        sourceWatermark.change(SOURCE, intent -> { admit(db, expiry, task.taskId(),
                task.dataScope().split(":")[0], intent); return null; });
        var service = new ProductSourceScopeGrants(db, transaction,
                new JdbcProductTaskDirectory(db, Clock.systemUTC()), grants, grantWatermark,
                sourceWatermark, null, Clock.systemUTC(), ISSUER);
        var input = new ProductSourceScopeGrants.Input(task.taskId(), "member", SOURCE,
                task.version(), 1, expiry, "source-scope-1");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "ungranted", "ungranted", "encoded");
        assertThatThrownBy(() -> service.authorizeVerified(new ProductSourceScopeGrants.Input(
                task.taskId(), "ungranted", SOURCE, task.version(), 1, expiry, "no-task-grant"),
                "maintainer", "organization")).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_request", Integer.class)).isZero();
        grantWatermark.assertCurrent();
        assertThatThrownBy(() -> service.authorizeVerified(new ProductSourceScopeGrants.Input(
                UUID.randomUUID().toString(), "member", SOURCE, 1, 1, expiry, "wrong-task"),
                "maintainer", "organization")).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope", Integer.class)).isZero();
        var receipt = service.authorizeVerified(input, "maintainer", "organization");
        String scopeId = receipt.scopeId();
        var workerSource = new ProductWorkerSourceGrant(db, ISSUER);
        workerSource.require("member", "organization", task.taskId(), SOURCE,
                task.version(), 1, 1, Instant.now());
        assertThatThrownBy(() -> workerSource.require("ungranted", "organization", task.taskId(),
                SOURCE, task.version(), 1, 1, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workerSource.require("member", "organization", task.taskId(),
                SOURCE, task.version(), 1, 2, Instant.now())).isInstanceOf(IllegalStateException.class);
        long generation = grantWatermark.currentGeneration();
        assertThat(service.authorizeVerified(input, "maintainer", "organization")).isEqualTo(receipt);
        assertThat(grantWatermark.currentGeneration()).isEqualTo(generation);
        db.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThatThrownBy(() -> service.authorizeVerified(input, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id=?", ISSUER, "member");

        var query = new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                task.taskId(), scopeId + ":1", "SOURCE_SCOPE", scopeId, 1, "QUERY", "SOURCE_QUERY");
        var display = new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                task.taskId(), scopeId + ":1", "SOURCE_SCOPE", scopeId, 1, "DISPLAY", "SOURCE_DISPLAY");
        assertThatThrownBy(() -> grants.issue(query, expiry, 0, "maintainer", "generic", HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grants.issueSourceScope(new ProductGrants.Scope(ISSUER, "member",
                "organization", "CONSULTATION", UUID.randomUUID().toString(), scopeId + ":1",
                "SOURCE_SCOPE", scopeId, 1, "QUERY", "SOURCE_QUERY"), expiry, 0,
                "maintainer", "cross-task", HASH, sourceWatermark)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE resource_type='SOURCE_SCOPE'",
                Integer.class)).isEqualTo(2);
        assertThat(grantWatermark.currentGeneration()).isPositive();
        assertThat(sourceWatermark.current(SOURCE).epoch()).isEqualTo(1);
        assertThatThrownBy(() -> service.authorizeVerified(new ProductSourceScopeGrants.Input(
                task.taskId(), "member", SOURCE, task.version(), 1, expiry.plusSeconds(1),
                "bad-expiry"), "maintainer", "organization")).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_request", Integer.class))
                .isEqualTo(1);
        grantWatermark.change(() -> grants.revoke(receipt.queryGrantId(), 1, "maintainer",
                "revoke-query", HASH));
        assertThatThrownBy(() -> workerSource.require("member", "organization", task.taskId(),
                SOURCE, task.version(), 1, 1, Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.authorizeVerified(input, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);

        String secondTask = UUID.randomUUID().toString();
        String secondScope = UUID.randomUUID().toString();
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,"
                        + "expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                        + "VALUES(?,?,?,?,'AWAITING_GOAL',1,?,?,1,?,?)", secondTask, "organization",
                ISSUER, "member", Timestamp.from(expiry), "approved-retention", HASH,
                Timestamp.from(Instant.now()));
        db.update("INSERT INTO con3_data_scope(scope_id,task_id,organization_id,scope_version,kind) "
                + "VALUES(?,?,?,1,'TASK_METADATA')", UUID.randomUUID().toString(), secondTask,
                "organization");
        String taskDataScope = db.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",
                String.class, secondTask) + ":1";
        grantWatermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, "member", "organization",
                "CONSULTATION", secondTask, taskDataScope, "TASK", secondTask, 1, "READ", "TASK_WORK"),
                expiry, 0, "maintainer", "task-2-grant", HASH));
        db.update("INSERT INTO con4_task_source_scope(scope_id,scope_version,task_id,organization_id,"
                        + "task_version,source_id,contract_version,decision_id,source_epoch,source_row_version,"
                        + "status,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                secondScope, 1, secondTask, "organization", 1, SOURCE, "contract-1", DECISION, 1, 1,
                Timestamp.from(expiry), Timestamp.from(Instant.now()));
        db.update("INSERT INTO con4_task_source_current(task_id,source_id,scope_id,scope_version,"
                        + "row_version,status) VALUES(?,?,?,?,1,'ACTIVE')", secondTask, SOURCE, secondScope, 1);
        var otherTaskQuery = new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                secondTask, secondScope + ":1", "SOURCE_SCOPE", secondScope, 1, "QUERY", "SOURCE_QUERY");
        grantWatermark.change(() -> grants.issueSourceScope(otherTaskQuery,
                expiry.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 0, "maintainer",
                "query-2", HASH, sourceWatermark));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_current_epoch "
                + "WHERE resource_type='SOURCE_SCOPE' AND action_code='QUERY' AND current_grant_id IS NOT NULL",
                Integer.class)).isEqualTo(1);
        authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id=? WHERE source_id=?",
                UUID.randomUUID().toString(), SOURCE);
        assertThatThrownBy(() -> service.authorizeVerified(input, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        authority.update("UPDATE id4_source_watermark SET state='READY',intent_id=NULL WHERE source_id=?", SOURCE);
        authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=? WHERE issuer=?",
                UUID.randomUUID().toString(), ISSUER);
        assertThatThrownBy(() -> service.authorizeVerified(input, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL WHERE issuer=?", ISSUER);
    }

    @Test void confirmedTaskContentVersionRenewsSourceScopeWithoutChangingTaskGrantVersion() throws Exception {
        JdbcDataSource main = database();
        JdbcDataSource independent = database();
        for (String name : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V038__product_task_source_scope.sql",
                "V039__product_source_verification.sql", "V041__product_task_authorization_version.sql",
                "V042__product_consultation_goal.sql", "V053__product_source_use_decision.sql",
                "V056__product_query_only_source_scope.sql"})
            script(main, "db/dm8/" + name);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        JdbcTemplate db = new JdbcTemplate(main);
        JdbcTemplate authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "member", "registered");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(main));
        var watermark = new ProductGrantWatermark(db, authority, ISSUER);
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var taskService = new ProductInitialTaskService(new JdbcProductTaskDirectory(db, Clock.systemUTC()),
                grants, watermark, transaction, new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(), ISSUER,
                new ProductRetentionPolicy("approved-retention", 1, HASH, Duration.ofHours(2)));
        Instant expiry = Instant.now().plusSeconds(1800);
        var task = taskService.create(new ProductInitialTaskService.Input("organization", "Named",
                "member", "approved-retention", 1, expiry), "renew-task", "maintainer", "organization");
        var sourceWatermark = new ProductSourceWatermark(db, authority, transaction);
        sourceWatermark.change(SOURCE, intent -> { admit(db, expiry, task.taskId(),
                task.dataScope().split(":")[0], intent); return null; });
        var service = new ProductSourceScopeGrants(db, transaction,
                new JdbcProductTaskDirectory(db, Clock.systemUTC()), grants, watermark,
                sourceWatermark, null, Clock.systemUTC(), ISSUER);
        var original = new ProductSourceScopeGrants.Input(task.taskId(), "member", SOURCE,
                1, 1, expiry, "source-first");
        var first = service.authorizeVerified(original, "maintainer", "organization");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "second", "second");
        watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, "second", "organization",
                "CONSULTATION", task.taskId(), task.dataScope(), "TASK", task.taskId(), 1,
                "READ", "TASK_WORK"), expiry.minusSeconds(10), 0, "maintainer", "second-task",
                HASH));
        var secondOld = service.authorizeVerified(new ProductSourceScopeGrants.Input(task.taskId(),
                "second", SOURCE, 1, 1, expiry.minusSeconds(10), "second-source-old"),
                "maintainer", "organization");
        db.update("UPDATE con3_task SET version=2,state='IN_PROGRESS' WHERE task_id=?", task.taskId());
        assertThatThrownBy(() -> grants.issueSourceScope(new ProductGrants.Scope(ISSUER, "member",
                "organization", "CONSULTATION", task.taskId(), first.scopeId() + ":1",
                "SOURCE_SCOPE", first.scopeId(), 1, "QUERY", "SOURCE_QUERY"),
                expiry.minusSeconds(10), 0, "maintainer", "stale-source", HASH, sourceWatermark))
                .isInstanceOf(IllegalStateException.class);
        var renewed = service.authorizeVerified(new ProductSourceScopeGrants.Input(task.taskId(),
                "member", SOURCE, 2, 1, expiry, "source-renew"), "maintainer", "organization");
        assertThat(renewed.scopeId()).isEqualTo(first.scopeId());
        assertThat(renewed.scopeVersion()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT status FROM id2_grant WHERE grant_id=?",
                String.class, first.queryGrantId())).isEqualTo("REVOKED");
        assertThat(db.queryForObject("SELECT status FROM id2_grant WHERE grant_id=?",
                String.class, secondOld.queryGrantId())).isEqualTo("REVOKED");
        var secondNew = service.authorizeVerified(new ProductSourceScopeGrants.Input(task.taskId(),
                "second", SOURCE, 2, 1, expiry.minusSeconds(10), "second-source-new"),
                "maintainer", "organization");
        assertThat(secondNew.scopeVersion()).isEqualTo(2);
        assertThat(secondNew.queryGrantId()).isNotEqualTo(secondOld.queryGrantId());
        assertThat(db.queryForObject("SELECT status FROM con4_task_source_scope "
                + "WHERE scope_id=? AND scope_version=1", String.class, first.scopeId()))
                .isEqualTo("REVOKED");
        assertThat(db.queryForObject("SELECT authorization_version FROM con3_task WHERE task_id=?",
                Long.class, task.taskId())).isEqualTo(1);
        assertThatThrownBy(() -> service.authorizeVerified(original, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        watermark.assertCurrent();
    }

    @Test void queryOnlyDecisionIssuesNoDisplayGrantAndReplaysCurrentQuery() throws Exception {
        JdbcDataSource main = database();
        JdbcDataSource independent = database();
        for (String name : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V038__product_task_source_scope.sql",
                "V039__product_source_verification.sql", "V041__product_task_authorization_version.sql",
                "V053__product_source_use_decision.sql", "V056__product_query_only_source_scope.sql"})
            script(main, "db/dm8/" + name);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        JdbcTemplate db = new JdbcTemplate(main);
        JdbcTemplate authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "member", "registered");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(main));
        var grantWatermark = new ProductGrantWatermark(db, authority, ISSUER);
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
        Instant expiry = Instant.now().plusSeconds(1800);
        var task = new ProductInitialTaskService(directory, grants, grantWatermark, transaction,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), ISSUER,
                new ProductRetentionPolicy("approved-retention", 1, HASH, Duration.ofHours(2)))
                .create(new ProductInitialTaskService.Input("organization", "Named", "member",
                        "approved-retention", 1, expiry), "query-task", "maintainer", "organization");
        var sourceWatermark = new ProductSourceWatermark(db, authority, transaction);
        sourceWatermark.change(SOURCE, intent -> {
            admit(db, expiry, task.taskId(), task.dataScope().split(":")[0], intent, false);
            return null;
        });
        var service = new ProductSourceScopeGrants(db, transaction, directory, grants,
                grantWatermark, sourceWatermark, null, Clock.systemUTC(), ISSUER);
        var input = new ProductSourceScopeGrants.Input(task.taskId(), "member", SOURCE, 1, 1,
                expiry, "query-only");
        var receipt = service.authorizeVerified(input, "maintainer", "organization");
        assertThat(receipt.queryGrantId()).isNotBlank();
        assertThat(receipt.displayGrantId()).isNull();
        assertThat(service.authorizeVerified(input, "maintainer", "organization")).isEqualTo(receipt);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE resource_type='SOURCE_SCOPE'",
                Integer.class)).isEqualTo(1);
        new ProductWorkerSourceGrant(db, ISSUER).require("member", "organization", task.taskId(),
                SOURCE, 1, 1, 1, Instant.now());
        assertThatThrownBy(() -> grantWatermark.change(() -> grants.issueSourceScope(
                new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                        task.taskId(), receipt.scopeId() + ":1", "SOURCE_SCOPE", receipt.scopeId(),
                        1, "DISPLAY", "SOURCE_DISPLAY"), expiry, 0, "maintainer", "no-display",
                HASH, sourceWatermark))).isInstanceOf(IllegalStateException.class);
        grantWatermark.change(() -> grants.revoke(receipt.queryGrantId(), 1, "maintainer",
                "revoke-query-only", HASH));
        assertThatThrownBy(() -> service.authorizeVerified(input, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
    }

    static void admit(JdbcTemplate db, Instant expiry, String taskId, String scopeId,
            ProductSourceWatermark.Intent intent) {
        admit(db, expiry, taskId, scopeId, intent, true);
    }

    static void admit(JdbcTemplate db, Instant expiry, String taskId, String scopeId,
            ProductSourceWatermark.Intent intent, Clock clock) {
        admit(db, expiry, taskId, scopeId, intent, true, clock.instant());
    }

    private static void admit(JdbcTemplate db, Instant expiry, String taskId, String scopeId,
            ProductSourceWatermark.Intent intent, boolean display) {
        admit(db, expiry, taskId, scopeId, intent, display, Instant.now());
    }

    private static void admit(JdbcTemplate db, Instant expiry, String taskId, String scopeId,
            ProductSourceWatermark.Intent intent, boolean display, Instant now) {
        db.update("INSERT INTO con4_source_contract(source_id,contract_version,source_version,"
                        + "declaration_json,request_sha,evidence_ref,evidence_sha,retention_ref,retention_version,"
                        + "retention_evidence_sha,collected_at,valid_until,status,row_version,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", SOURCE, "contract-1", "capture-1", "{}",
                HASH, "rights", HASH, "retention", 1, HASH, Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(expiry), "ACTIVE", 2, Timestamp.from(now));
        db.update("INSERT INTO con4_source_admission(decision_id,source_id,contract_version,rights_ref,"
                        + "rights_sha,capability_ref,capability_sha,responsible_party,maintainer_ref,coverage_sha,"
                        + "valid_until,decided_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", DECISION, SOURCE,
                "contract-1", "rights", HASH, "capability", HASH, "responsible", "maintainer", HASH,
                Timestamp.from(expiry), Timestamp.from(now));
        db.update("INSERT INTO con4_source_product_decision(decision_id,decision_version,"
                + "decision_ref,decision_sha,rights_evidence_status,organization_id,task_id,"
                + "scope_id,retention_ref,retention_version,retention_evidence_sha) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", DECISION, "v1", "decision.json", HASH,
                "UNRESOLVED", "organization", taskId, scopeId, "retention", 1, HASH);
        for (String use : display ? new String[] {"QUERY", "DISPLAY"} : new String[] {"QUERY"})
            db.update("INSERT INTO con4_source_field_use(decision_id,field_code,use_code,valid_until) "
                    + "VALUES(?,?,?,?)", DECISION, "TITLE", use, Timestamp.from(expiry));
        db.update("INSERT INTO con4_source_current(source_id,contract_version,decision_id,source_epoch,"
                        + "status,row_version,history_sha,updated_at) VALUES(?,?,?,1,'ACTIVE',1,?,?)",
                SOURCE, "contract-1", DECISION, "0".repeat(64), Timestamp.from(now));
        db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,decision_id,"
                        + "operation,before_epoch,after_epoch,before_version,after_version,intent_id,"
                        + "before_history_sha,actor_ref,request_sha,occurred_at) "
                        + "VALUES(?,?,?,?, 'ADMIT',0,1,0,1,?,?,?,?,?)", UUID.randomUUID().toString(),
                SOURCE, "contract-1", DECISION, intent.id(), intent.beforeHistorySha(),
                "maintainer", HASH, Timestamp.from(now));
    }

    static JdbcDataSource database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-scope-grant-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
