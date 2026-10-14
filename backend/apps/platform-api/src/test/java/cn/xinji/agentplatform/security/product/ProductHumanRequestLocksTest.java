package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProductHumanRequestLocksTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String DECISION = "11111111-1111-4111-8111-111111111111";
    final ProductHumanTaskTransactionTest base = new ProductHumanTaskTransactionTest();
    ProductHumanRequestLocks locks;
    private String scope;
    private ProductSourceWatermark sourceWatermark;
    private final List<ProductHumanRequestLocks.SourceUse> ordinary = List.of(
            new ProductHumanRequestLocks.SourceUse("source-1", Set.of("TITLE"), Set.of()));

    @BeforeEach void setup() throws Exception {
        setupCapture("capture-1", "capture/manifest.json", "a".repeat(64), "a".repeat(64));
    }

    void setupCapture(String sourceVersion, String manifestRef, String manifestSha, String recordsSha) throws Exception {
        setupCapture(sourceVersion, manifestRef, manifestSha, recordsSha, true);
    }

    void setupCapture(String sourceVersion, String manifestRef, String manifestSha, String recordsSha, boolean allowSave) throws Exception {
        base.setup(); base.taskFacts();
        migrate(base.source, "dm8/V036__product_source_candidates.sql", "dm8/V037__product_source_admission.sql",
                "dm8/V038__product_task_source_scope.sql", "dm8/V039__product_source_verification.sql",
                "dm8/V053__product_source_use_decision.sql", "dm8/V056__product_query_only_source_scope.sql",
                "dm8/V092__product_source_comparison_use.sql", "dm8/V109__product_human_request.sql",
                "dm8/V111__product_human_body_access_deadline.sql");
        migrate(base.authority.getDataSource(), "identity-authority/V003__consultation_source_watermark.sql");
        var transactions = new TransactionTemplate(base.manager);
        sourceWatermark = new ProductSourceWatermark(base.db, base.authority, transactions);
        sourceWatermark.change("source-1", intent -> {
            ProductSourceScopeGrantTest.admit(base.db, base.task.expiresAt(), base.task.id(),
                    base.task.dataScope().split(":")[0], intent, base.clock);
            for (String use : allowSave ? List.of("SAVE", "COMPARISON") : List.of("COMPARISON"))
                base.db.update("INSERT INTO con4_source_field_use VALUES(?,'TITLE',?,?)", DECISION, use,
                        Timestamp.from(base.task.expiresAt()));
            base.db.update("UPDATE con4_source_contract SET source_version=? WHERE source_id='source-1'", sourceVersion);
            base.db.update("INSERT INTO con4_source_verification VALUES(?,'source-1','contract-1',?,"
                    + "1,?,?,?,?,1,1,'verifier',?)", UUID.randomUUID().toString(), sourceVersion,
                    manifestRef, manifestSha, recordsSha, "a".repeat(64), Timestamp.from(base.clock.instant()));
            return null;
        });
        var grants = new ProductGrants(base.db, transactions, base.clock);
        var scopes = new ProductSourceScopeGrants(base.db, transactions,
                new JdbcProductTaskDirectory(base.db, base.clock), grants, base.watermark, sourceWatermark,
                null, base.clock, ISSUER);
        scope = scopes.authorizeVerified(new ProductSourceScopeGrants.Input(base.task.id(), "member", "source-1",
                base.task.version(), 1, base.task.expiresAt(), "source-request"), "maintainer", "organization").scopeId();
        locks = new ProductHumanRequestLocks(base.db, base.access, base.watermark, base.manager, ISSUER, base.clock);
    }

    @Test void ordinaryRequestLocksSourceBeforeTaskWithoutRequiringComparison() {
        base.transaction.executeRequest(base.token, base.task.id(), locks, context -> ordinary, (context, held) -> {
            var plan = locks.prepare(context, base.decoder.decode(base.token), ordinary);
            assertThat(plan.keys().stream().map(ProductHumanRequestLocks.EpochKey::type))
                    .containsExactly("SOURCE_SCOPE", "TASK", "TASK");
            assertThat(plan.keys().stream().map(ProductHumanRequestLocks.EpochKey::action))
                    .containsExactly("DISPLAY", "READ", "WRITE");
            write(); return null;
        });
        assertThat(writes()).isEqualTo(1);
        assertThat(base.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE action_code='COMPARE'", Integer.class)).isZero();
    }

    @Test void mergedSameSourceUsesOneScopeAndAddsComparisonBeforeDisplay() {
        var compared = new ProductHumanRequestLocks.SourceUse("source-1", Set.of("TITLE"), Set.of("TITLE"));
        base.transaction.execute(base.token, base.task.id(), context -> {
            var plan = locks.prepare(context, base.decoder.decode(base.token), List.of(ordinary.getFirst(), compared));
            assertThat(plan.keys().stream().map(ProductHumanRequestLocks.EpochKey::action))
                    .containsExactly("COMPARE", "DISPLAY", "READ", "WRITE");
            return null;
        });
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), locks,
                context -> List.of(compared), (context, held) -> { write(); return null; })).isInstanceOf(IllegalStateException.class);
        assertThat(writes()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"read", "display", "save", "pending", "scope", "deadline", "epoch"})
    void finalRevocationOrDriftRollsBackTheWholeCallback(String change) {
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), locks, context -> ordinary, (context, held) -> {
            write();
            switch (change) {
                case "read" -> base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='READ'");
                case "display" -> base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='DISPLAY'");
                case "save" -> base.db.update("DELETE FROM con4_source_field_use WHERE use_code='SAVE'");
                case "pending" -> base.authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id='pending'");
                case "scope" -> base.db.update("UPDATE con4_task_source_current SET row_version=2");
                case "deadline" -> base.clock.now = base.task.expiresAt();
                case "epoch" -> base.db.update("UPDATE id2_current_epoch SET source_epoch=2 WHERE resource_type='SOURCE_SCOPE'");
                default -> throw new AssertionError(change);
            }
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void noReadGrantNeverConsumesSelectedMaterialOrWrites() {
        base.watermark.change(() -> {
            base.db.update("UPDATE id2_grant SET status='REVOKED' WHERE action_code='READ'"); return null;
        });
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), locks,
                context -> ordinary, (context, held) -> { write(); return null; })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void currentScopeForAnotherTaskVersionNeverEntersTheCallback() {
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), locks, context -> {
            // Plan discovery follows the callback: force a current scope that no longer matches the Task.
            base.db.update("UPDATE con4_task_source_scope SET task_version=6 WHERE scope_id=?", scope);
            return ordinary;
        }, (context, held) -> { write(); return null; })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void sourceWriterWaitsForRequestCommit() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            CountDownLatch started = new CountDownLatch(1);
            var pending = base.transaction.executeRequest(base.token, base.task.id(), locks, context -> ordinary, (context, held) -> {
                write();
                var writer = pool.submit(() -> {
                    started.countDown();
                    base.db.update("UPDATE con4_source_current SET row_version=2 WHERE source_id='source-1'");
                });
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> writer.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                return writer;
            });
            pending.get(5, TimeUnit.SECONDS);
            assertThat(writes()).isEqualTo(1);
            assertThatThrownBy(() -> sourceWatermark.current("source-1")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void taskOnlyOrdinaryPackageStillRequiresReadAndWrite() {
        base.transaction.executeRequest(base.token, base.task.id(), locks, context -> List.of(), (context, held) -> { write(); return null; });
        assertThat(writes()).isEqualTo(1);
    }

    @Test void sourceGrantExpiringDuringFieldDeadlineReadsNeverEntersCallback() {
        Instant until = base.clock.instant().plusSeconds(50).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var grants = new ProductGrants(base.db, new TransactionTemplate(base.manager), base.clock);
        String original = base.db.queryForObject("SELECT grant_id FROM id2_grant WHERE action_code='DISPLAY' AND status='ACTIVE'", String.class);
        base.watermark.change(() -> grants.revoke(original, 1, "maintainer", "revoke-display", "e".repeat(64)));
        base.watermark.change(() -> grants.issueSourceScope(new ProductGrants.Scope(ISSUER, "member", "organization",
                "CONSULTATION", base.task.id(), scope + ":1", "SOURCE_SCOPE", scope, 1, "DISPLAY", "SOURCE_DISPLAY"),
                until, 2, "maintainer", "short-display", "f".repeat(64), sourceWatermark));
        var duringRead = new JdbcTemplate(base.source) {
            private int deadlines;
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                var result = super.query(sql, mapper, args);
                if (sql.startsWith("SELECT k.valid_until") && ++deadlines == 2) base.clock.now = until;
                return result;
            }
        };
        var guarded = new ProductHumanRequestLocks(duringRead, base.access, base.watermark, base.manager, ISSUER, base.clock);
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), guarded,
                context -> ordinary, (context, held) -> { write(); return null; })).isInstanceOf(IllegalStateException.class);
        assertThat(writes()).isZero();
    }

    @Test void comparedMaterialNeedsAndUsesItsOwnExactGrant() {
        var grants = new ProductGrants(base.db, new TransactionTemplate(base.manager), base.clock);
        base.watermark.change(() -> grants.issueSourceScope(new ProductGrants.Scope(ISSUER, "member", "organization",
                "CONSULTATION", base.task.id(), scope + ":1", "SOURCE_SCOPE", scope, 1, "COMPARE", "COMPARISON"),
                base.task.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), 0,
                "maintainer", "comparison", "f".repeat(64), sourceWatermark));
        var compared = new ProductHumanRequestLocks.SourceUse("source-1", Set.of("TITLE"), Set.of("TITLE"));
        base.transaction.executeRequest(base.token, base.task.id(), locks, context -> List.of(compared),
                (context, held) -> { write(); return null; });
        assertThat(writes()).isEqualTo(1);
    }

    @Test void finalReadGrantDeadlineIsNotReplacedByLongerWriteGrant() {
        var grants = new ProductGrants(base.db, new TransactionTemplate(base.manager), base.clock);
        String original = base.db.queryForObject("SELECT grant_id FROM id2_grant WHERE action_code='READ' AND status='ACTIVE'", String.class);
        base.watermark.change(() -> grants.revoke(original, 1, "maintainer", "revoke-read", "e".repeat(64)));
        Instant until = base.clock.instant().plusSeconds(50).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        base.watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                base.task.id(), base.task.dataScope(), "TASK", base.task.id(), 1, "READ", "TASK_WORK"),
                until, 2, "maintainer", "short-read", "f".repeat(64)));
        assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), locks, context -> List.of(),
                (context, held) -> { write(); base.clock.now = until; return null; })).isInstanceOf(RuntimeException.class);
        assertThat(writes()).isZero();
    }

    @Test void alternativeAuthorityOrClockCannotBeMixedIntoTaskTransaction() {
        var otherAuthority = new ProductHumanRequestLocks(base.db, base.access,
                new ProductGrantWatermark(base.db, base.authority, ISSUER), base.manager, ISSUER, base.clock);
        var otherClock = new ProductHumanRequestLocks(base.db, base.access, base.watermark, base.manager, ISSUER,
                java.time.Clock.fixed(base.clock.instant(), java.time.ZoneOffset.UTC));
        for (var candidate : List.of(otherAuthority, otherClock))
            assertThatThrownBy(() -> base.transaction.executeRequest(base.token, base.task.id(), candidate,
                    context -> ordinary, (context, held) -> { write(); return null; })).isInstanceOf(IllegalArgumentException.class);
        assertThat(writes()).isZero();
    }

    private void write() {
        base.db.update("INSERT INTO con3_event VALUES('lock-callback',?,'INITIAL_CREATE','member',7,?,CURRENT_TIMESTAMP)",
                base.task.id(), "c".repeat(64));
    }
    private int writes() { return base.db.queryForObject("SELECT COUNT(*) FROM con3_event WHERE event_id='lock-callback'", Integer.class); }
}
