package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Real SQL reader evidence only: the test supplies events, not an admitted cleanup writer. */
class ProductHumanCleanupHistoryTest {
    private ProductHumanTaskTransactionTest base = new ProductHumanTaskTransactionTest();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductComparisonJson json = new ProductComparisonJson(mapper);
    private ProductHumanRequestStore store;
    private ProductHumanCleanupScope scope;
    private ProductHumanCleanupScope.Snapshot initial;
    private ProductHumanRequestStore.Stored a;
    private ProductHumanRequestStore.Stored b;
    private Instant start;

    @BeforeEach void setup() throws Exception {
        base.clock.now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        base.setup();
        store = base.requestStore();
        migrate(base.source, "dm8/V110__product_human_cleanup_scope.sql");
        migrate(base.authority.getDataSource(), "identity-authority/V006__consultation_human_cleanup_scope.sql");
        // Fixture-only nullability, deliberately not a product migration or physical cleanup adapter.
        base.db.execute("ALTER TABLE con6_human_package ALTER COLUMN manifest_json DROP NOT NULL");
        scope = reader(base.db, base.authority);
        start = base.clock.instant();
        initial = scope.initialize(base.task.id(), new ProductHumanCleanupScope.Policy("approved-human-v3", 3,
                "e".repeat(64)), "maintainer", base.task.expiresAt(), start, base.task.expiresAt());
        a = save("package-a", 50);
        b = save("package-b", 100);
    }

    @Test void initializedReaderAndWitnessAcceptExactOriginalBody() {
        assertThat(scope.current(base.task.id(), initial.policy())).isEqualTo(initial);
        scope.requireBodyUnchanged(initial, initial, identity(b));
        new ProductHumanRequestWitness(base.clock, scope).historical(b, b.manifest().references());
        assertThat(replay(b)).isEqualTo(b);
    }

    @Test void clearingOnePackagePreservesAnotherPackagesOriginalAncestorAndReceipt() {
        var current = clearA();
        scope.requireCleared(current, identity(a));
        scope.requireBodyUnchanged(initial, current, identity(b));
        var refs = withCleanup(b.manifest().references(), current);
        new ProductHumanRequestWitness(base.clock, scope).historical(b, refs);
        new ProductHumanRequestWitness(base.clock, scope).body(b, refs, current);
        assertThat(replay(b)).isEqualTo(b);
        base.transaction.execute(base.token, base.task.id(), context -> {
            var found = store.locate(context, a.key()).orElseThrow();
            assertThat(found.receipt().manifestSha()).isEqualTo(a.manifestSha());
            assertThat(found.bodyAccessUntil()).isNull();
            assertThat(found.bodyAvailable(base.clock.instant())).isFalse();
            assertThatThrownBy(() -> store.body(context, a.key(), found)).isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(base.db.queryForObject("SELECT COUNT(*) FROM con6_human_package", Long.class)).isEqualTo(2);
        assertThat(base.db.queryForObject("SELECT COUNT(*) FROM con6_human_receipt", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> scope.requireRestorable(initial)).isInstanceOf(IllegalStateException.class);
    }

    @Test void applicationLookupKeepsUnclearedOriginalBodyAndClearedOriginalReceipt(@TempDir Path root) throws Exception {
        var fixture = new ProductHumanRequestApplicationFixture();
        fixture.setup(root, true);
        base = fixture.base;
        store = new ProductHumanRequestStore(base.db, mapper, base.clock);
        scope = fixture.cleanup;
        initial = scope.current(base.task.id(), new ProductHumanCleanupScope.Policy(
                fixture.policy.reference(), fixture.policy.version(), fixture.policy.evidenceSha256()));
        start = base.clock.instant();
        b = fixture.request().body();
        assertThat(b).isNotNull();
        var template = b.manifest();
        a = base.transaction.execute(base.token, base.task.id(), context -> {
            var bounds = new OrdinaryHumanRetention.Bounds(base.task.expiresAt(), base.task.expiresAt(),
                    start.plusSeconds(50), start.plusSeconds(50), base.task.expiresAt(), start.plusSeconds(50));
            var manifest = new ProductHumanRequestStore.Manifest(template.taskId(), template.organization(), template.dataScope(),
                    template.taskVersion(), "package-a", 1, template.requester(), template.binding(), template.references(),
                    template.reason(), template.purpose(), template.policy(), bounds, template.createdAt());
            return store.save(context, manifest, new ProductHumanRequestStore.Key("short-storage-key", json.sha("short-storage-key")));
        });
        base.db.execute("ALTER TABLE con6_human_package ALTER COLUMN manifest_json DROP NOT NULL");
        String originalJson = base.db.queryForObject("SELECT manifest_json FROM con6_human_package WHERE package_id=?",
                String.class, b.manifest().packageId());
        clearA();
        var remaining = fixture.application.lookup(base.token, base.task.id(), b.key().requestId());
        assertThat(remaining.body()).isEqualTo(b);
        assertThat(base.db.queryForObject("SELECT manifest_json FROM con6_human_package WHERE package_id=?",
                String.class, b.manifest().packageId())).isEqualTo(originalJson);
        var cleared = fixture.application.lookup(base.token, base.task.id(), a.key().requestId());
        assertThat(cleared.body()).isNull();
        assertThat(cleared.receipt().packageId()).isEqualTo(a.manifest().packageId());
        assertThat(cleared.receipt().manifestSha()).isEqualTo(a.manifestSha());
        assertThat(cleared.receipt().auditUntil()).isEqualTo(a.auditUntil());
    }

    @Test void restoringOldManifestAndAccessDoesNotReviveClearedBody() {
        var current = clearA();
        base.db.update("UPDATE con6_human_package SET manifest_json=?,body_access_until=? WHERE package_id=?",
                json.canonical(a.manifest()), Timestamp.from(ProductHumanRequestStore.bodyAccessUntil(a.manifest())),
                a.manifest().packageId());
        assertThatThrownBy(() -> scope.current(base.task.id(), initial.policy())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.requireBodyUnchanged(initial, current, identity(a)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void nullBodyWithoutHistoryIsNotAClearingProof() {
        base.db.update("UPDATE con6_human_package SET manifest_json=NULL,body_access_until=NULL WHERE package_id=?",
                a.manifest().packageId());
        assertThatThrownBy(() -> scope.requireCleared(initial, identity(a))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.requireBodyUnchanged(initial, initial, identity(a)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"sha", "generation", "task", "scope", "policy", "intent"})
    void forgedAncestorIsRefused(String change) {
        var current = clearA();
        var forged = new ProductHumanCleanupScope.Snapshot(
                change.equals("scope") ? "other-scope" : initial.scopeId(), initial.organization(),
                change.equals("task") ? "other-task" : initial.taskId(), change.equals("generation") ? 2 : 1,
                "READY", change.equals("intent") ? "other-intent" : null,
                change.equals("policy") ? new ProductHumanCleanupScope.Policy("other-policy", 3, "e".repeat(64)) : initial.policy(),
                change.equals("sha") ? "f".repeat(64) : initial.historySha(), initial.createdAt(), initial.validUntil());
        assertThatThrownBy(() -> scope.requireBodyUnchanged(forged, current, identity(b)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "sha", "previous", "duplicate", "pending", "scalar", "receipt", "handling", "access"})
    void brokenChainOrNativeClearClosureIsRefused(String change) {
        clearA();
        switch (change) {
            case "missing" -> base.db.update("DELETE FROM con6_human_cleanup_event WHERE generation=1");
            case "sha" -> base.db.update("UPDATE con6_human_cleanup_event SET event_sha=? WHERE generation=2", "f".repeat(64));
            case "previous" -> rewritePreviousHistory();
            case "duplicate" -> rewriteDuplicateSelection();
            case "pending" -> base.authority.update("UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='unfinished'");
            case "scalar" -> base.db.update("UPDATE con6_human_package SET manifest_sha=? WHERE package_id=?", "f".repeat(64), a.manifest().packageId());
            case "receipt" -> base.db.update("DELETE FROM con6_human_receipt WHERE package_id=?", a.manifest().packageId());
            case "handling" -> base.db.update("UPDATE con6_human_handling SET state='CLAIMED',processing_version=2 WHERE package_id=?", a.manifest().packageId());
            case "access" -> base.db.update("UPDATE con6_human_package SET body_access_until=? WHERE package_id=?",
                    Timestamp.from(ProductHumanRequestStore.bodyAccessUntil(a.manifest())), a.manifest().packageId());
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> scope.current(base.task.id(), initial.policy())).isInstanceOf(IllegalStateException.class);
    }

    @Test void finalIndependentReadDetectsMutationDuringHistoryRead() {
        clearA();
        AtomicInteger reads = new AtomicInteger();
        var changingAuthority = new JdbcTemplate(base.authority.getDataSource()) {
            @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("id6_human_cleanup_scope") && reads.incrementAndGet() == 2)
                    base.authority.update("UPDATE id6_human_cleanup_scope SET state='PENDING',intent_id='late-change'");
                return super.query(sql, rowMapper, args);
            }
        };
        var changing = reader(base.db, changingAuthority);
        assertThatThrownBy(() -> changing.current(base.task.id(), initial.policy())).isInstanceOf(IllegalStateException.class);
        assertThat(reads.get()).isGreaterThanOrEqualTo(2);
    }

    @Test void expiredScopeRejectsBeforeAnyEventClobQuery() {
        clearA();
        base.clock.now = initial.validUntil();
        AtomicInteger clobQueries = new AtomicInteger();
        var guardedMain = new JdbcTemplate(base.source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("event_json") || sql.contains("manifest_json")) clobQueries.incrementAndGet();
                return super.query(sql, rowMapper, args);
            }
        };
        var guarded = reader(guardedMain, base.authority);
        assertThatThrownBy(() -> guarded.current(base.task.id(), initial.policy())).isInstanceOf(IllegalStateException.class);
        assertThat(clobQueries.get()).isZero();
    }

    @Test void expiredNativeEventHeaderRejectsBeforeMalformedEventClobQuery() {
        clearA();
        base.db.update("UPDATE con6_human_cleanup_event SET audit_until=?,event_json='invalid-expired-json' WHERE generation=1",
                Timestamp.from(base.clock.instant()));
        AtomicInteger clobQueries = new AtomicInteger();
        var guardedMain = new JdbcTemplate(base.source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("event_json")) clobQueries.incrementAndGet();
                return super.query(sql, rowMapper, args);
            }
        };
        var guarded = reader(guardedMain, base.authority);
        assertThatThrownBy(() -> guarded.current(base.task.id(), initial.policy())).isInstanceOf(IllegalStateException.class);
        assertThat(clobQueries.get()).isZero();
    }

    @Test void wrongBodyIdentityAndChangedFactsRemainRefused() {
        var current = clearA();
        assertThatThrownBy(() -> scope.requireBodyUnchanged(initial, current,
                new ProductHumanCleanupHistory.BodyIdentity(b.manifest().packageId(), 1, "f".repeat(64))))
                .isInstanceOf(IllegalStateException.class);
        var original = b.manifest().references();
        var f = original.facts();
        var changed = new ProductHumanTaskFacts.Snapshot(f.taskId(), f.taskVersion(), f.purpose(), f.goal(), f.message(),
                new ProductHumanTaskFacts.Reference("SEARCH_INPUT", "different-input", 1, "c".repeat(64)),
                f.policy(), f.confirmedAt(), f.expiresAt());
        var refs = new ProductHumanRequestReferences(changed, original.materials(), original.sources(), original.comparison(), current, original.gaps());
        assertThatThrownBy(() -> new ProductHumanRequestWitness(base.clock, scope).historical(b, refs))
                .isInstanceOf(IllegalStateException.class);
    }

    private ProductHumanCleanupScope reader(JdbcTemplate primary, JdbcTemplate independent) {
        return new ProductHumanCleanupScope(primary, independent, base.manager, mapper, base.clock,
                new ProductHumanCleanupScope.InitializationAdmission() {
                    @Override public void requireNewTaskWithoutHistory(String taskId, String organization,
                            ProductHumanCleanupScope.Policy policy, String operator, Instant now) { }
                    @Override public void requireCurrent(Instant now) { }
                });
    }

    private ProductHumanRequestStore.Stored save(String id, long seconds) {
        return base.transaction.execute(base.token, base.task.id(), context -> {
            var m = base.manifest(context);
            var refs = withCleanup(m.references(), initial);
            var bounds = new OrdinaryHumanRetention.Bounds(base.task.expiresAt(), base.task.expiresAt(),
                    start.plusSeconds(seconds), start.plusSeconds(seconds), base.task.expiresAt(), start.plusSeconds(seconds));
            var manifest = new ProductHumanRequestStore.Manifest(m.taskId(), m.organization(), m.dataScope(), m.taskVersion(),
                    id, 1, m.requester(), refs.binding(), refs, m.reason(), m.purpose(), m.policy(), bounds, m.createdAt());
            return store.save(context, manifest, new ProductHumanRequestStore.Key(id + "-request", json.sha(id)));
        });
    }

    private ProductHumanCleanupScope.Snapshot clearA() {
        base.clock.now = start.plusSeconds(50);
        var m = a.manifest();
        var retained = new ProductHumanCleanupHistory.RetainedRequestScalars(a.manifestSha(), m.taskVersion(), m.dataScope(),
                m.requester().issuer(), m.requester().subject(), m.createdAt(), start.plusSeconds(50), a.auditUntil(),
                1, "WAITING", m.createdAt(), 1, a.key().requestSha(), a.key().requestId());
        var selection = new ProductHumanCleanupHistory.BodyClearSelection(m.packageId(), m.packageRevision(),
                new ProductHumanCleanupHistory.BodyClearBefore(retained, ProductHumanRequestStore.bodyAccessUntil(m)),
                new ProductHumanCleanupHistory.BodyClearAfter("CLEARED", base.clock.instant(), 2, json.sha(retained)));
        var selections = List.of(selection);
        var event = new ProductHumanCleanupHistory.BodyClearEvent("HUMAN_BODY_CLEAR_EVENT/v1", "BODY_CLEAR", initial.scopeId(),
                initial.organization(), initial.taskId(), 2, 1, initial.historySha(), "test-clear-intent", initial.policy(),
                new ProductHumanCleanupHistory.Operator(m.requester().issuer(), m.requester().subject(), "d".repeat(64)),
                base.clock.instant(), base.clock.instant(), a.auditUntil(), initial.validUntil(), selections, json.sha(selections));
        String eventSha = json.sha(event);
        String historySha = json.sha(new ProductHumanCleanupHistory.BodyClearLink("HUMAN_CLEANUP_CHAIN/v1",
                initial.scopeId(), 2, initial.historySha(), eventSha));
        base.db.update("UPDATE con6_human_package SET manifest_json=NULL,body_access_until=NULL WHERE package_id=?", m.packageId());
        base.db.update("INSERT INTO con6_human_cleanup_event VALUES(?,2,?,?,?,?)", initial.scopeId(), json.canonical(event),
                eventSha, Timestamp.from(base.clock.instant()), Timestamp.from(a.auditUntil()));
        base.db.update("UPDATE con6_human_cleanup_scope SET generation=2,history_sha=?", historySha);
        base.authority.update("UPDATE id6_human_cleanup_scope SET generation=2,history_sha=?", historySha);
        return scope.current(base.task.id(), initial.policy());
    }

    private ProductHumanRequestStore.Stored replay(ProductHumanRequestStore.Stored stored) {
        return base.transaction.execute(base.token, base.task.id(), context -> store.replay(context, stored.key()).orElseThrow());
    }

    private void rewritePreviousHistory() {
        String raw = base.db.queryForObject("SELECT event_json FROM con6_human_cleanup_event WHERE generation=2", String.class);
        var event = json.stored(raw.replace(initial.historySha(), "f".repeat(64)), ProductHumanCleanupHistory.BodyClearEvent.class);
        writeRehashedEvent(event);
    }

    private void rewriteDuplicateSelection() {
        String raw = base.db.queryForObject("SELECT event_json FROM con6_human_cleanup_event WHERE generation=2", String.class);
        var event = json.stored(raw, ProductHumanCleanupHistory.BodyClearEvent.class);
        var duplicate = List.of(event.selections().getFirst(), event.selections().getFirst());
        var node = json.stored(raw, com.fasterxml.jackson.databind.node.ObjectNode.class);
        node.withArray("selections").add(node.withArray("selections").get(0).deepCopy());
        node.put("selectionSha256", json.sha(duplicate));
        writeRehashedEvent(json.stored(node.toString(), ProductHumanCleanupHistory.BodyClearEvent.class));
    }

    private void writeRehashedEvent(ProductHumanCleanupHistory.BodyClearEvent event) {
        String eventSha = json.sha(event);
        String historySha = json.sha(new ProductHumanCleanupHistory.BodyClearLink("HUMAN_CLEANUP_CHAIN/v1",
                initial.scopeId(), 2, initial.historySha(), eventSha));
        base.db.update("UPDATE con6_human_cleanup_event SET event_json=?,event_sha=? WHERE generation=2", json.canonical(event), eventSha);
        base.db.update("UPDATE con6_human_cleanup_scope SET history_sha=?", historySha);
        base.authority.update("UPDATE id6_human_cleanup_scope SET history_sha=?", historySha);
    }

    private static ProductHumanCleanupHistory.BodyIdentity identity(ProductHumanRequestStore.Stored stored) {
        return new ProductHumanCleanupHistory.BodyIdentity(stored.manifest().packageId(), stored.manifest().packageRevision(), stored.manifestSha());
    }

    private static ProductHumanRequestReferences withCleanup(ProductHumanRequestReferences refs, ProductHumanCleanupScope.Snapshot cleanup) {
        return new ProductHumanRequestReferences(refs.facts(), refs.materials(), refs.sources(), refs.comparison(), cleanup, refs.gaps());
    }
}
