package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class ProductHumanTaskCreationTest {
    private ProductHumanTaskCreationFixture f;
    @BeforeEach void setup() throws Exception { f = new ProductHumanTaskCreationFixture(); }

    @Test void normalCreationInitializesExactScopeAndReplayPreservesOriginalTaskContract() {
        var service = f.service();
        var result = service.create(f.jwt, "normal", f.base.input);
        assertThat(f.authorityState()).isEqualTo("READY");
        assertThat(f.count("con3_task")).isEqualTo(1);
        assertThat(f.count("con6_human_cleanup_scope")).isEqualTo(1);
        var event = f.base.db.queryForObject("SELECT event_json FROM con6_human_cleanup_event", String.class);
        assertThat(event).contains("member", "human-v3").doesNotContain("controlled-maintainer", f.original.token);
        assertThat(f.base.db.queryForObject("SELECT valid_until FROM con6_human_cleanup_scope", java.sql.Timestamp.class).toInstant())
                .isEqualTo(f.base.input.expiresAt());
        assertThat(service.create(f.jwt, "normal", f.base.input)).isEqualTo(result);
        assertThat(f.count("con6_human_cleanup_event")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothReceiptReplayBranchesReturnTaskWithoutCreatingScope(boolean transactionReplay) {
        var expected = f.base.service.create(f.jwt, "old", f.base.input);
        var directory = spy(f.base.directory);
        var lookups = new AtomicInteger();
        if (transactionReplay) doAnswer(call -> lookups.incrementAndGet() == 1 ? Optional.empty() : call.callRealMethod())
                .when(directory).creationReceipt("old");
        assertThat(f.service(f.base.using(directory, f.original.clock)).create(f.jwt, "old", f.base.input)).isEqualTo(expected);
        assertNoScope();
    }

    @Test void lifetimeBeyond365DaysFailsBeforeAnyTaskWrite() {
        var invalid = new ProductTaskCreationService.Input("org", f.base.now.plus(Duration.ofDays(366)), "approved", 1);
        assertThatThrownBy(() -> f.service().create(f.jwt, "too-long", invalid))
                .isInstanceOf(ResponseStatusException.class).satisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        assertThat(f.count("con3_task")).isZero(); assertNoScope();
    }

    @Test void nanosecondClockProducesOneExactMillisecondEventAndBothScopeTimestamps() throws Exception {
        f.original.now = f.base.now.plusNanos(123456);
        var expected = f.original.now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        f.service().create(f.jwt, "precise-clock", f.base.input);
        var event = f.mapper.readValue(f.base.db.queryForObject("SELECT event_json FROM con6_human_cleanup_event", String.class),
                ProductHumanCleanupScope.Initialization.class);
        assertThat(event.occurredAt()).isEqualTo(expected);
        assertThat(f.base.db.queryForObject("SELECT created_at FROM con6_human_cleanup_scope", java.sql.Timestamp.class).toInstant())
                .isEqualTo(expected);
        assertThat(f.base.authority.queryForObject("SELECT created_at FROM id6_human_cleanup_scope", java.sql.Timestamp.class).toInstant())
                .isEqualTo(expected);
        assertThat(f.base.db.queryForObject("SELECT occurred_at FROM con6_human_cleanup_event", java.sql.Timestamp.class).toInstant())
                .isEqualTo(expected);
        assertThat(event.validUntil()).isEqualTo(f.base.expires);
        assertThat(f.authorityState()).isEqualTo("READY");
    }

    @Test void expiryWithSubMillisecondPrecisionIsBadRequestBeforeAnyTaskOrScopeWrite() {
        var invalid = new ProductTaskCreationService.Input("org", f.base.expires.plusNanos(1), "approved", 1);
        assertThatThrownBy(() -> f.service().create(f.jwt, "precise-expiry", invalid))
                .isInstanceOf(ResponseStatusException.class).satisfies(e ->
                        assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
        assertThat(f.count("con3_task")).isZero();
        assertThat(f.count("con3_task_create_receipt")).isZero();
        assertNoScope();
    }

    @ParameterizedTest @ValueSource(strings = {"client", "session", "subject", "service", "key", "create"})
    void currentIdentityAndCreatePermissionCannotBeReplacedBySignedToken(String revoked) {
        switch (revoked) {
            case "client" -> f.base.db.update("UPDATE id2_client_current SET status='REVOKED',version=2");
            case "session" -> f.base.db.update("UPDATE id2_session SET status='REVOKED'");
            case "subject" -> f.base.db.update("UPDATE id2_subject SET status='DISABLED'");
            case "service" -> f.base.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");
            case "key" -> f.base.db.update("UPDATE id2_signing_key SET status='REVOKED'");
            case "create" -> f.base.watermark.change(() -> f.base.grants.revoke(
                    f.base.db.queryForObject("SELECT grant_id FROM id2_grant WHERE resource_type='ORGANIZATION'", String.class),
                    1, "maintainer", "revoke", "d".repeat(64)));
            default -> throw new AssertionError(revoked);
        }
        assertThatThrownBy(() -> f.service().create(f.jwt, "denied", f.base.input)).isInstanceOf(RuntimeException.class);
        assertThat(f.count("con3_task")).isZero(); assertNoScope();
    }

    @Test void scopeStorageFailureLeavesTaskAndPendingAndOriginalKeyCannotRepairIt() {
        f.base.db.execute("ALTER TABLE con6_human_cleanup_event ADD CONSTRAINT fail_native CHECK(generation>1)");
        var service = f.service();
        assertThatThrownBy(() -> service.create(f.jwt, "partial", f.base.input)).isInstanceOf(RuntimeException.class);
        assertThat(f.count("con3_task")).isEqualTo(1);
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
        assertThat(f.authorityState()).isEqualTo("PENDING");
        var replay = service.create(f.jwt, "partial", f.base.input);
        assertThat(replay.taskId()).isEqualTo(f.base.directory.creationReceipt("partial").orElseThrow().task().id());
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
        assertThat(f.authorityState()).isEqualTo("PENDING");
    }

    @Test void taskReadRevocationDuringScopeInitializationCannotBeAbsorbedAsNewGeneration() {
        f.beforeDecode = () -> {
            if (f.count("id6_human_cleanup_scope") == 1 && f.changed.compareAndSet(false, true)) {
                String grant = f.base.db.queryForObject("SELECT grant_id FROM id2_grant WHERE resource_type='TASK'", String.class);
                CompletableFuture.runAsync(() -> f.base.watermark.change(() -> f.base.grants.revoke(
                        grant, 1, "maintainer", "revoke-new-read", "d".repeat(64)))).join();
            }
        };
        assertThatThrownBy(() -> f.service().create(f.jwt, "read-revoked", f.base.input)).isInstanceOf(IllegalStateException.class);
        assertThat(f.changed).isTrue();
        assertThat(f.count("con3_task")).isEqualTo(1);
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
        assertThat(f.authorityState()).isEqualTo("PENDING");
    }

    @Test void taskReadRevokedBeforeFreshNotificationNeverBecomesTheAcceptedGeneration() {
        f.beforeDecode = () -> {
            if (f.original.committed("con3_task") == 1 && f.changed.compareAndSet(false, true)) {
                String grant = f.base.db.queryForObject("SELECT grant_id FROM id2_grant WHERE resource_type='TASK'", String.class);
                f.base.watermark.change(() -> f.base.grants.revoke(grant, 1, "maintainer", "revoke-before-fact", "d".repeat(64)));
            }
        };
        assertThatThrownBy(() -> f.service().create(f.jwt, "before-fact", f.base.input)).isInstanceOf(IllegalStateException.class);
        assertThat(f.changed).isTrue(); assertThat(f.count("con3_task")).isEqualTo(1); assertNoScope();
    }

    @ParameterizedTest @ValueSource(strings = {"session", "create", "jwt-expiry", "task-expiry"})
    void ownedTransactionRechecksCurrentAuthorityBeforeItsCommit(String change) {
        var directory = spy(f.base.directory);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            switch (change) {
                case "session" -> f.base.db.update("UPDATE id2_session SET status='REVOKED'");
                case "create" -> f.base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE resource_type='ORGANIZATION'");
                case "jwt-expiry" -> f.original.now = f.jwt.getExpiresAt();
                case "task-expiry" -> f.original.now = f.base.expires;
                default -> throw new AssertionError(change);
            }
            return result;
        }).when(directory).insert(org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> f.service(f.base.using(directory, f.original.clock))
                .create(f.jwt, "owned-denied", f.base.input)).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("con3_task")).isZero(); assertNoScope(); f.base.watermark.assertCurrent();
    }

    @Test void finalAuthorizationLossDoesNotPretendCommittedScopeRolledBack() {
        f.beforeDecode = () -> {
            if ("READY".equals(f.original.committedAuthorityState()) && f.changed.compareAndSet(false, true))
                f.base.db.update("UPDATE id2_client_current SET status='REVOKED',version=2");
        };
        assertThatThrownBy(() -> f.service().create(f.jwt, "late-loss", f.base.input)).isInstanceOf(IllegalStateException.class);
        assertThat(f.changed).isTrue(); assertThat(f.authorityState()).isEqualTo("READY");
        assertThat(f.count("con6_human_cleanup_scope")).isEqualTo(1);
    }

    private void assertNoScope() {
        assertThat(f.count("con6_human_cleanup_scope")).isZero();
        assertThat(f.count("con6_human_cleanup_event")).isZero();
        assertThat(f.count("id6_human_cleanup_scope")).isZero();
    }
}
