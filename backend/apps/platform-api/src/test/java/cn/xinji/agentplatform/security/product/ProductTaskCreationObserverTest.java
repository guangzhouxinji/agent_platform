package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real main/independent transactions; only maintenance admission and failure points are controlled. */
class ProductTaskCreationObserverTest {
    private ProductTaskCreationFixture fixture;
    @BeforeEach void setup() throws Exception { fixture = new ProductTaskCreationFixture(); fixture.grant(); }

    @Test void newFactIsDeliveredOnlyAfterBothCommitsWithOriginalTaskGrantAndRetention() {
        var observer = new Observer(0);
        var result = fixture.service.createObserved(fixture.jwt, "observed", fixture.input, observer);
        assertThat(observer.transactionChecks).containsExactly(false, true, true, false);
        assertThat(observer.rowChecks).containsExactly(0, 0, 1, 1);
        assertThat(observer.facts).hasSize(1);
        var fact = observer.facts.getFirst();
        var nativeReceipt = fixture.directory.creationReceipt("observed").orElseThrow();
        assertThat(fact.task()).isEqualTo(nativeReceipt.task());
        assertThat(fact.grantId()).isEqualTo(nativeReceipt.grantId()).isEqualTo(result.grantId());
        assertThat(fact.creation().requestId()).isEqualTo("observed");
        assertThat(fact.creation().requestSha()).isEqualTo(nativeReceipt.requestSha());
        assertThat(fact.creation().issuer()).isEqualTo(fixture.jwt.getIssuer().toString());
        assertThat(fact.creation().subject()).isEqualTo(fixture.jwt.getSubject());
        assertThat(fact.creation().organization()).isEqualTo(result.organizationId());
        assertThat(fact.creation().expiresAt()).isEqualTo(fixture.input.expiresAt());
        assertThat(fact.creation().retentionRef()).isEqualTo("approved");
        assertThat(fact.creation().retentionVersion()).isEqualTo(1);
        assertThat(fact.creation().retentionEvidenceSha()).isEqualTo("a".repeat(64));
        assertThat(fact.toString()).doesNotContain(fixture.jwt.getTokenValue(), "private_pkcs8");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void neitherOuterNorTransactionReceiptReplayProducesAnotherBirth(boolean hideFirstLookup) {
        var original = fixture.service.create(fixture.jwt, "prior", fixture.input);
        var directory = spy(fixture.directory);
        var calls = new AtomicInteger();
        if (hideFirstLookup) doAnswer(call -> calls.incrementAndGet() == 1 ? Optional.empty() : call.callRealMethod())
                .when(directory).creationReceipt("prior");
        var observer = new Observer(0);
        assertThat(fixture.using(directory).createObserved(fixture.jwt, "prior", fixture.input, observer)).isEqualTo(original);
        assertThat(observer.facts).isEmpty();
        assertThat(observer.transactionChecks).containsExactly(false);
        assertThat(rows()).isEqualTo(1);
        fixture.watermark.assertCurrent();
    }

    @Test void enclosingTransactionAndMissingObserverAreRejectedBeforeAnyCreation() {
        var observer = new Observer(0);
        fixture.transactions.executeWithoutResult(status -> assertThatThrownBy(() -> fixture.service.createObserved(
                fixture.jwt, "outer", fixture.input, observer)).isInstanceOf(IllegalStateException.class));
        assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "null", fixture.input, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(observer.transactionChecks).isEmpty(); assertThat(observer.facts).isEmpty();
        assertThat(rows()).isZero(); fixture.watermark.assertCurrent();
    }

    @Test void independentManagerCannotStandInForPrimaryCreationTransaction() {
        fixture.transactions.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(fixture.authority.getDataSource()));
        var observer = new Observer(0);
        assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "wrong-manager", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound primary REQUIRED");
        assertThat(observer.transactionChecks).isEmpty(); assertThat(observer.facts).isEmpty();
        assertThat(rows()).isZero(); fixture.watermark.assertCurrent();
    }

    @ParameterizedTest @ValueSource(ints = {org.springframework.transaction.TransactionDefinition.PROPAGATION_SUPPORTS,
            org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED})
    void propagationWithoutGuaranteedPrimaryCommitIsRejectedBeforeWrites(int propagation) {
        fixture.transactions.setPropagationBehavior(propagation);
        var observer = new Observer(0);
        assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "wrong-propagation", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound primary REQUIRED");
        assertThat(observer.transactionChecks).isEmpty(); assertThat(observer.facts).isEmpty();
        assertThat(rows()).isZero(); fixture.watermark.assertCurrent();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void enclosingTransactionWithoutSynchronizationStillCannotDeliverBeforeItsCommit(boolean independent) {
        var dataSource = (independent ? fixture.authority : fixture.db).getDataSource();
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        manager.setTransactionSynchronization(org.springframework.transaction.support.AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        var observer = new Observer(0);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isTrue();
            assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "outer-no-sync", fixture.input, observer))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("standalone");
        });
        assertThat(observer.transactionChecks).isEmpty(); assertThat(observer.facts).isEmpty();
        assertThat(rows()).isZero(); fixture.watermark.assertCurrent();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
    void maintenanceDenialAtEachBoundaryNeverDeliversBirthAndOnlyPrecommitFailuresRollback(int denyAt) {
        var observer = new Observer(denyAt);
        assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "denied", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessage("Controlled maintenance permission expired");
        assertThat(observer.facts).isEmpty(); assertThat(observer.transactionChecks).hasSize(denyAt);
        assertThat(rows()).isEqualTo(denyAt == 4 ? 1 : 0);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM con3_task_create_receipt", Integer.class)).isEqualTo(denyAt == 4 ? 1 : 0);
        assertThat(fixture.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE resource_type='TASK'", Integer.class)).isEqualTo(denyAt == 4 ? 1 : 0);
        fixture.watermark.assertCurrent();
        if (denyAt == 4) {
            var replayObserver = new Observer(0);
            fixture.service.createObserved(fixture.jwt, "denied", fixture.input, replayObserver);
            assertThat(replayObserver.facts).isEmpty();
        }
    }

    @Test void deadlineEqualityRejectsObservedEntryWithoutTaskWrites() {
        Instant approvedUntil = fixture.now.plusSeconds(10);
        var service = fixture.using(fixture.directory, java.time.Clock.fixed(approvedUntil, java.time.ZoneOffset.UTC));
        var observer = new Observer(0) {
            @Override public void requireCurrent(Instant now) {
                if (!now.isBefore(approvedUntil)) throw new IllegalStateException("Maintenance lease expired at boundary");
            }
        };
        assertThatThrownBy(() -> service.createObserved(fixture.jwt, "expired", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("boundary");
        assertThat(rows()).isZero(); assertThat(observer.facts).isEmpty();
    }

    @Test void independentReadyFailureAfterMainCommitKeepsTaskButDoesNotDeliverFact() {
        var directory = spy(fixture.directory);
        doAnswer(call -> {
            call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    fixture.authority.update("UPDATE id2_authority_watermark SET intent_id='controlled-interruption'");
                }
            });
            return null;
        }).when(directory).recordCreationReceipt(any(), any(), anyString());
        var observer = new Observer(0);
        assertThatThrownBy(() -> fixture.using(directory).createObserved(fixture.jwt, "pending", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Independent authorization commit failed");
        assertThat(observer.facts).isEmpty(); assertThat(observer.transactionChecks).containsExactly(false, true, true);
        assertThat(rows()).isEqualTo(1);
        assertThat(fixture.directory.creationReceipt("pending")).isPresent();
        assertThat(fixture.authority.queryForObject("SELECT state FROM id2_authority_watermark", String.class)).isEqualTo("PENDING");
    }

    @Test void observerDeliveryExceptionCannotRollBackCommittedTaskOrMintProofOnReplay() {
        var observer = new Observer(0) {
            @Override public void created(ProductTaskCreationService.CreationFact fact) {
                throw new IllegalStateException("Controlled delivery failure");
            }
        };
        assertThatThrownBy(() -> fixture.service.createObserved(fixture.jwt, "delivery", fixture.input, observer))
                .isInstanceOf(IllegalStateException.class).hasMessage("Controlled delivery failure");
        assertThat(rows()).isEqualTo(1); fixture.watermark.assertCurrent();
        var replayObserver = new Observer(0);
        fixture.service.createObserved(fixture.jwt, "delivery", fixture.input, replayObserver);
        assertThat(replayObserver.facts).isEmpty();
    }

    private int rows() { return fixture.db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class); }
    private class Observer implements ProductTaskCreationService.CreationObserver {
        final List<ProductTaskCreationService.CreationFact> facts = new ArrayList<>();
        final List<Boolean> transactionChecks = new ArrayList<>();
        final List<Integer> rowChecks = new ArrayList<>();
        final int denyAt;
        Observer(int denyAt) { this.denyAt = denyAt; }
        @Override public void requireCurrent(Instant now) {
            transactionChecks.add(TransactionSynchronizationManager.isActualTransactionActive()); rowChecks.add(rows());
            if (transactionChecks.size() == denyAt) throw new IllegalStateException("Controlled maintenance permission expired");
        }
        @Override public void created(ProductTaskCreationService.CreationFact fact) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            fixture.watermark.assertCurrent(); assertThat(rows()).isEqualTo(1); facts.add(fact);
        }
    }
}
