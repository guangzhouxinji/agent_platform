package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductSourceScopeGrantTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** C-level scope/version contracts; no patent content, product identities or rights evidence. */
class ProductSourceScopeReauthorizationTest {
    @Test void explicitSourceVersionChangeRetiresOldGrantsAndCannotReplayOldState() throws Exception {
        var main = database();
        var independent = database();
        for (String name : new String[]{"V032__product_identity.sql", "V034__product_consultation_initial_task.sql",
                "V036__product_source_candidates.sql", "V037__product_source_admission.sql",
                "V038__product_task_source_scope.sql", "V039__product_source_verification.sql",
                "V041__product_task_authorization_version.sql", "V053__product_source_use_decision.sql",
                "V056__product_query_only_source_scope.sql"}) script(main, "db/dm8/" + name);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        String issuer = "https://identity.example.org", source = "source-1", hash = "a".repeat(64);
        var db = new JdbcTemplate(main);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",
                issuer, "member", "registered");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(main));
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
        Instant expiry = Instant.now().plusSeconds(1800);
        var task = new ProductInitialTaskService(directory, grants, watermark, transaction,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), issuer,
                new ProductRetentionPolicy("approved-retention", 1, hash, Duration.ofHours(2)))
                .create(new ProductInitialTaskService.Input("organization", "Named", "member",
                        "approved-retention", 1, expiry), "scope-task", "maintainer", "organization");
        var sourceWatermark = new ProductSourceWatermark(db, authority, transaction);
        sourceWatermark.change(source, intent -> {
            admit(db, expiry, task.taskId(), task.dataScope().split(":")[0], intent); return null;
        });
        var service = new ProductSourceScopeGrants(db, transaction, directory, grants, watermark,
                sourceWatermark, null, Clock.systemUTC(), issuer);
        var original = new ProductSourceScopeGrants.Input(task.taskId(), "member", source, 1, 1,
                expiry.minusSeconds(60), "scope-original");
        var first = service.authorizeVerified(original, "maintainer", "organization");
        sourceWatermark.change(source, intent -> {
            db.update("UPDATE con4_source_current SET source_epoch=2,row_version=2 WHERE source_id=?", source);
            db.update("INSERT INTO con4_source_authority_event(event_id,source_id,contract_version,decision_id,"
                    + "operation,before_epoch,after_epoch,before_version,after_version,intent_id,"
                    + "before_history_sha,actor_ref,request_sha,occurred_at) "
                    + "VALUES(?,?,?,?,'ADMIT',1,2,1,2,?,?,?,?,CURRENT_TIMESTAMP)", UUID.randomUUID().toString(),
                    source, "contract-1", "11111111-1111-4111-8111-111111111111", intent.id(),
                    intent.beforeHistorySha(), "maintainer", hash);
            return null;
        });
        var implicit = new ProductSourceScopeGrants.Input(task.taskId(), "member", source, 1, 2,
                expiry.minusSeconds(60), "scope-implicit");
        assertThatThrownBy(() -> service.authorizeVerified(implicit, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        var stale = new ProductSourceScopeGrants.Input(task.taskId(), "member", source, 1, 2,
                expiry.minusSeconds(60), "scope-stale", 99);
        assertThatThrownBy(() -> service.authorizeVerified(stale, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope", Integer.class)).isEqualTo(1);
        var explicit = new ProductSourceScopeGrants.Input(task.taskId(), "member", source, 1, 2,
                expiry.minusSeconds(60), "scope-explicit", 1);
        var renewed = service.authorizeVerified(explicit, "maintainer", "organization");
        assertThat(renewed.scopeId()).isEqualTo(first.scopeId());
        assertThat(renewed.scopeVersion()).isEqualTo(2);
        for (String id : new String[]{first.queryGrantId(), first.displayGrantId()})
            assertThat(db.queryForObject("SELECT status FROM id2_grant WHERE grant_id=?", String.class, id))
                    .isEqualTo("REVOKED");
        assertThat(service.authorizeVerified(explicit, "maintainer", "organization")).isEqualTo(renewed);
        assertThatThrownBy(() -> service.authorizeVerified(original, "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.authorizeVerified(new ProductSourceScopeGrants.Input(task.taskId(),
                "member", source, 1, 2, expiry.minusSeconds(60), "scope-new-stale", 1),
                "maintainer", "organization")).isInstanceOf(IllegalStateException.class);
        watermark.assertCurrent();
        sourceWatermark.assertUnchanged(source, 2);
    }
}
