package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductHumanInitializationTest {
    private ProductHumanInitializationFixture f;
    @BeforeEach void setup() throws Exception { f = new ProductHumanInitializationFixture(); }

    @Test void signedCreatorAndDistinctMaintainerProduceOneBoundScopeAcrossBothStores() {
        var kernel = f.kernel();
        var result = f.execute(kernel, "first");
        assertThat(result.scope().taskId()).isEqualTo(result.task().taskId());
        assertThat(result.scope().organization()).isEqualTo(result.task().organizationId());
        assertThat(result.scope().policy()).isEqualTo(f.policy);
        assertThat(result.scope().validUntil()).isEqualTo(f.base.expires);
        assertThat(f.base.db.queryForObject("SELECT owner_subject FROM con3_task", String.class)).isEqualTo("member");
        assertThat(f.base.db.queryForObject("SELECT event_json FROM con6_human_cleanup_event", String.class))
                .contains("controlled-maintainer").doesNotContain(f.token, "private_pkcs8", "password");
        assertThat(f.base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class)).isEqualTo("READY");
        assertThat(f.count(f.base.db, "con3_task_create_receipt")).isEqualTo(1);
        assertThatThrownBy(() -> f.execute(kernel, "second")).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(1);
        assertThat(f.count(f.base.db, "con6_human_cleanup_event")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void neitherReceiptReplayCanCreateAScopeOrReviveConsumedKernel(boolean transactionReplay) {
        f.base.service.create(f.decoder.decode(f.token), "prior", f.base.input);
        var directory = spy(f.base.directory);
        var lookups = new AtomicInteger();
        if (transactionReplay) doAnswer(call -> lookups.incrementAndGet() == 1 ? Optional.empty() : call.callRealMethod())
                .when(directory).creationReceipt("prior");
        var kernel = f.kernel(f.base.using(directory, f.clock));
        assertThatThrownBy(() -> f.execute(kernel, "prior")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.execute(kernel, "new-key")).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(1);
        assertNoScope();
        f.base.watermark.assertCurrent();
    }

    @Test void invalidSignedTokenConsumesInstanceBeforeAnyBusinessWrite() {
        var kernel = f.kernel();
        assertThatThrownBy(() -> kernel.execute("invalid.token.signature", "bad-token", f.base.input,
                f.policy, f.base.expires, f.base.now, f.base.expires))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        assertThatThrownBy(() -> f.execute(kernel, "valid-now")).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(f.base.db, "con3_task")).isZero();
        assertNoScope();
    }

    @Test void revokedRealSessionCannotBeReplacedByMaintenancePermission() {
        f.base.db.update("UPDATE id2_session SET status='REVOKED'");
        assertThatThrownBy(() -> f.execute(f.kernel(), "revoked")).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        assertThat(f.count(f.base.db, "con3_task")).isZero();
        assertNoScope();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void creationAndCleanupMustUseTheSameActualDataSources(boolean replaceAuthority) {
        var original = replaceAuthority ? f.base.authority : f.base.db;
        var different = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DelegatingDataSource(original.getDataSource()));
        var main = replaceAuthority ? f.base.db : different;
        var authority = replaceAuthority ? different : f.base.authority;
        assertThatThrownBy(() -> new ProductHumanInitialization(main, authority,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(main.getDataSource()),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), f.clock,
                f.base.service, f.decoder, f.lease)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.count(f.base.db, "con3_task")).isZero();
        assertNoScope();
    }

    @Test void invalidCreationInputConsumesInstanceWithoutCreatingAnything() {
        var kernel = f.kernel();
        assertThatThrownBy(() -> kernel.execute(f.token, "invalid-input", null,
                f.policy, f.base.expires, f.base.now, f.base.expires)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> f.execute(kernel, "fixed-input")).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(f.base.db, "con3_task")).isZero();
        assertNoScope();
    }

    @Test void initializationParameterFailurePreservesCommittedTaskAndCannotSupplementBirth() {
        var kernel = f.kernel();
        assertThatThrownBy(() -> kernel.execute(f.token, "bad-scope", f.base.input, f.policy,
                f.base.expires, f.base.expires.plusSeconds(1), f.base.expires))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(1);
        assertNoScope();
        assertThatThrownBy(() -> f.execute(kernel, "replacement")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.execute(f.kernel(), "bad-scope")).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(1);
        assertNoScope();
        f.base.watermark.assertCurrent();
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "retention", "receipt"})
    void changedNativeCreationCoordinatesCannotAuthorizeInitialization(String coordinate) {
        if (coordinate.equals("actor")) f.base.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,'other','other','encoded','ACTIVE',1,CURRENT_TIMESTAMP)",
                ProductTaskCreationFixture.ISSUER);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        f.denied = () -> {
            if (f.committed("con3_task") == 1 && changed.compareAndSet(false, true)) {
                if (coordinate.equals("actor")) f.base.db.update("UPDATE con3_task_create_receipt SET actor_subject='other'");
                else if (coordinate.equals("retention"))
                    f.base.db.update("UPDATE con3_task SET retention_evidence_sha=?", "f".repeat(64));
                else f.base.db.update("UPDATE con3_task_create_receipt SET request_sha=?", "f".repeat(64));
            }
            return false;
        };
        var kernel = f.kernel();
        assertThatThrownBy(() -> f.execute(kernel, "changed-native-fact")).isInstanceOf(IllegalStateException.class);
        assertThat(changed).isTrue();
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(1);
        assertNoScope();
        assertThatThrownBy(() -> f.execute(kernel, "retry")).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"entry", "task-committed", "scope-pending", "scope-main-committed", "scope-ready", "scope-returned"})
    void lossOfSameLeasePreservesActualCommittedStateAndRefusesReuse(String stage) {
        f.denied = () -> reached(stage);
        var kernel = f.kernel();
        assertThatThrownBy(() -> f.execute(kernel, "lease-loss")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Controlled lease lost");
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(stage.equals("entry") ? 0 : 1);
        if (stage.equals("entry") || stage.equals("task-committed")) assertNoScope();
        else {
            assertThat(f.base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class))
                    .isEqualTo(stage.equals("scope-returned") ? "READY" : "PENDING");
            assertThat(f.count(f.base.db, "con6_human_cleanup_scope")).isEqualTo(stage.equals("scope-pending") ? 0 : 1);
        }
        f.denied = () -> false;
        assertThatThrownBy(() -> f.execute(kernel, "retry")).isInstanceOf(IllegalStateException.class);
        f.base.watermark.assertCurrent();
    }

    @ParameterizedTest @ValueSource(strings = {"entry", "task-committed", "scope-ready", "scope-returned"})
    void deadlineReachedWhileLeaseCheckWaitsIsRejectedAfterCallback(String stage) {
        f.crossDeadline = () -> reached(stage);
        assertThatThrownBy(() -> f.execute(f.kernel(), "lease-deadline")).isInstanceOf(IllegalStateException.class);
        assertThat(f.now).isEqualTo(f.binding.expiresAt());
        assertThat(f.count(f.base.db, "con3_task")).isEqualTo(stage.equals("entry") ? 0 : 1);
        if (stage.equals("scope-ready") || stage.equals("scope-returned"))
            assertThat(f.base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class))
                    .isEqualTo(stage.equals("scope-returned") ? "READY" : "PENDING");
        else assertNoScope();
    }

    private boolean reached(String stage) {
        if (stage.equals("entry")) return true;
        if (stage.equals("task-committed")) return f.committed("con3_task") == 1;
        if (stage.equals("scope-pending")) return f.count(f.base.authority, "id6_human_cleanup_scope") == 1;
        if (stage.equals("scope-main-committed")) return f.committed("con6_human_cleanup_scope") == 1;
        if (stage.equals("scope-returned")) return "READY".equals(f.committedAuthorityState());
        return f.count(f.base.authority, "id6_human_cleanup_scope") == 1
                && "READY".equals(f.base.authority.queryForObject("SELECT state FROM id6_human_cleanup_scope", String.class));
    }
    private void assertNoScope() {
        assertThat(f.count(f.base.db, "con6_human_cleanup_scope")).isZero();
        assertThat(f.count(f.base.db, "con6_human_cleanup_event")).isZero();
        assertThat(f.count(f.base.authority, "id6_human_cleanup_scope")).isZero();
    }
}
