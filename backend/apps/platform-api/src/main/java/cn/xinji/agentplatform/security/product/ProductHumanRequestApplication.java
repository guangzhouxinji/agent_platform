package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Internal complete request operation. HTTP admission and maintenance are separate adapters. */
final class ProductHumanRequestApplication {
    record Command(long expectedTaskVersion, ProductHumanComparisonReferences.Selection comparison,
                   List<ProductHumanMaterialReferences.Selection> materials, String reason, String purpose) {
        Command {
            unicode(reason); unicode(purpose);
            for (var material : materials) {
                unicode(material.sourceId()); unicode(material.documentId()); unicode(material.documentVersion());
                material.fields().forEach(Command::unicode);
            }
            if (comparison != null) {
                unicode(comparison.comparisonId()); comparison.reportIds().forEach(Command::unicode);
            }
            materials = materials.stream().sorted(Comparator.comparing(ProductHumanMaterialReferences.Selection::sourceId)
                    .thenComparing(ProductHumanMaterialReferences.Selection::documentId)
                    .thenComparing(ProductHumanMaterialReferences.Selection::documentVersion)).toList();
            if (comparison != null) comparison = new ProductHumanComparisonReferences.Selection(comparison.comparisonId(),
                    comparison.reportIds().stream().sorted().toList());
            if (expectedTaskVersion < 1 || reason == null || reason.isBlank() || reason.length() > 4000
                    || !"ORDINARY_ASSISTANCE".equals(purpose)) throw new IllegalArgumentException("Ordinary request required");
            if (materials.stream().map(m -> List.of(m.sourceId(), m.documentId(), m.documentVersion())).distinct().count()
                    != materials.size()) throw new IllegalArgumentException("Duplicate material selection");
        }
        private static void unicode(String value) {
            if (value == null) throw new IllegalArgumentException("Ordinary command text required");
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (Character.isHighSurrogate(current)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                        throw new IllegalArgumentException("Malformed ordinary command Unicode");
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException("Malformed ordinary command Unicode");
                }
            }
        }
    }
    record Result(ProductHumanRequestStore.Receipt receipt, ProductHumanRequestStore.Stored body) { }
    private final JdbcTemplate db;
    private final ProductHumanTaskTransaction transactions;
    private final ProductHumanRequestLocks locks;
    private final ProductHumanTaskFacts facts;
    private final ProductHumanMaterialReferences materials;
    private final ProductHumanComparisonReferences comparisons;
    private final ProductHumanCleanupScope cleanup;
    private final ProductHumanRequestStore store;
    private final ProductHumanRequestWitness witness;
    private final JwtDecoder decoder;
    private final ProductComparisonJson json;
    private final OrdinaryHumanRetention.Policy policy;
    private final ProductRetentionPolicy taskPolicy;
    private final Clock clock;

    ProductHumanRequestApplication(JdbcTemplate db, javax.sql.DataSource independent,
            ProductHumanTaskTransaction transactions, ProductHumanRequestLocks locks, ProductHumanTaskFacts facts,
            ProductHumanMaterialReferences materials, ProductHumanComparisonReferences comparisons,
            ProductHumanCleanupScope cleanup, ProductHumanRequestStore store, JwtDecoder decoder,
            ProductComparisonJson json, OrdinaryHumanRetention.Policy policy, ProductRetentionPolicy taskPolicy, Clock clock) {
        this.db = db; this.transactions = Objects.requireNonNull(transactions); this.locks = Objects.requireNonNull(locks);
        this.facts = Objects.requireNonNull(facts); this.materials = Objects.requireNonNull(materials);
        this.comparisons = Objects.requireNonNull(comparisons); this.cleanup = Objects.requireNonNull(cleanup);
        this.store = Objects.requireNonNull(store); this.decoder = Objects.requireNonNull(decoder);
        this.json = Objects.requireNonNull(json); this.policy = Objects.requireNonNull(policy);
        this.taskPolicy = Objects.requireNonNull(taskPolicy); this.clock = Objects.requireNonNull(clock);
        transactions.requireBinding(db.getDataSource(), clock); facts.requireBinding(db.getDataSource(), clock);
        store.requireBinding(db.getDataSource(), clock); comparisons.requireBinding(db.getDataSource());
        cleanup.requireBinding(db.getDataSource(), independent, clock);
        witness = new ProductHumanRequestWitness(clock, cleanup);
    }

    Result request(String token, String task, String requestId, Command command) {
        return execute(token, task, requestId, Objects.requireNonNull(command));
    }

    Result lookup(String token, String task, String requestId) {
        return execute(token, task, requestId, null);
    }

    private Result execute(String token, String task, String requestId, Command submitted) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Original request key required");
        return transactions.executeVerifiedRequest(token, task, locks, context -> {
            var scope = scope(context);
            var resolved = resolve(context, requestId, submitted, scope);
            if (resolved.found().isPresent() && !resolved.found().get().bodyAvailable(clock.instant())) return List.of();
            return uses(task, resolved.command());
        }, (context, plan) -> {
            var accessDeadlines = new ArrayList<Instant>();
            var scope = scope(context);
            var resolved = resolve(context, requestId, submitted, scope);
            var command = resolved.command(); var key = resolved.key(); var found = resolved.found();
            if (found.isPresent() && !found.get().bodyAvailable(clock.instant())) {
                var original = found.get();
                return new ProductHumanTaskTransaction.Verified<>(new Result(original.receipt(), null), () -> {
                    cleanup.assertUnchanged(scope);
                    if (!original.equals(store.locate(context, key).orElseThrow(ProductHumanRequestApplication::changed))) throw changed();
                    requireClearedIfNeeded(original, scope);
                }, () -> witness.receipt(original, scope));
            }
            ProductHumanRequestStore.Stored stored;
            boolean replay = found.isPresent();
            if (replay) {
                stored = body(context, key, found.get(), scope);
                if (!stored.manifest().policy().equals(policy)) throw changed();
            } else {
                if (submitted == null) throw missing();
                if (command.expectedTaskVersion() != context.task().version()) throw changed();
                var references = references(context, plan, token, command, null, scope, accessDeadlines);
                Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                var bounds = bounds(context, references);
                var manifest = new ProductHumanRequestStore.Manifest(task, context.task().organization(),
                        context.task().dataScope(), context.task().version(), UUID.randomUUID().toString(), 1,
                        context.actor(), references.binding(), references, command.reason(), command.purpose(), policy, bounds, now);
                stored = store.save(context, manifest, key);
            }
            var original = stored;
            var authenticated = references(context, plan, token, command, replay ? original.manifest().references() : null, scope, accessDeadlines);
            if (replay) witness.historical(original, authenticated);
            else witness.same(original.manifest().references(), authenticated, false);
            verifyBounds(context, original, authenticated);
            var receipt = store.locate(context, key).orElseThrow(ProductHumanRequestApplication::changed);
            return new ProductHumanTaskTransaction.Verified<>(new Result(receipt.receipt(), original), () -> {
                var checked = references(context, plan, token, command, replay ? original.manifest().references() : null, scope, accessDeadlines);
                witness.same(authenticated, checked, false);
                verifyBounds(context, original, checked);
                if (!original.equals(body(context, key, store.locate(context, key).orElseThrow(ProductHumanRequestApplication::changed), scope)))
                    throw changed();
                cleanup.assertUnchanged(scope);
            }, () -> {
                if (accessDeadlines.stream().anyMatch(until -> !clock.instant().isBefore(until))) throw changed();
                witness.body(original, authenticated, scope);
                witness.receipt(receipt, scope);
            });
        });
    }

    private record Resolved(Command command, ProductHumanRequestStore.Key key,
                            java.util.Optional<ProductHumanRequestStore.Lookup> found) { }

    private Resolved resolve(ProductHumanTaskTransaction.Context context, String requestId, Command submitted,
                             ProductHumanCleanupScope.Snapshot scope) {
        if (submitted != null) {
            var key = key(context, requestId, submitted);
            var found = store.locate(context, key);
            found.ifPresent(value -> requireClearedIfNeeded(value, scope));
            return new Resolved(submitted, key, found);
        }
        var found = store.locate(context, requestId).orElseThrow(ProductHumanRequestApplication::missing);
        requireClearedIfNeeded(found, scope);
        var key = new ProductHumanRequestStore.Key(requestId, found.receipt().requestSha());
        if (!found.bodyAvailable(clock.instant())) return new Resolved(null, key, java.util.Optional.of(found));
        var manifest = body(context, key, found, scope).manifest();
        var command = ProductHumanRequestCommands.fromManifest(manifest);
        if (!key.equals(key(context, requestId, command))) throw changed();
        return new Resolved(command, key, java.util.Optional.of(found));
    }

    private ProductHumanRequestStore.Stored body(ProductHumanTaskTransaction.Context context,
            ProductHumanRequestStore.Key key, ProductHumanRequestStore.Lookup found,
            ProductHumanCleanupScope.Snapshot scope) {
        cleanup.assertUnchanged(scope);
        var stored = store.body(context, key, found);
        cleanup.assertUnchanged(scope);
        return stored;
    }

    private void requireClearedIfNeeded(ProductHumanRequestStore.Lookup found, ProductHumanCleanupScope.Snapshot scope) {
        if (found.bodyAccessUntil() != null) return;
        var receipt = found.receipt();
        cleanup.requireCleared(scope, new ProductHumanCleanupHistory.BodyIdentity(
                receipt.packageId(), receipt.packageRevision(), receipt.manifestSha()));
    }

    private ProductHumanRequestReferences references(ProductHumanTaskTransaction.Context context,
            ProductHumanRequestLocks.Plan plan, String token, Command command, ProductHumanRequestReferences historical,
            ProductHumanCleanupScope.Snapshot scope, List<Instant> accessDeadlines) {
        Jwt jwt = decoder.decode(token);
        if (jwt.getIssuer() == null || !context.actor().issuer().equals(jwt.getIssuer().toString())
                || !context.actor().subject().equals(jwt.getSubject())) throw changed();
        var taskFacts = historical == null ? facts.load(context, token) : facts.historical(context, token, historical.facts());
        var refs = command.materials().stream().map(selection -> materials.load(context, plan, selection, jwt)).toList();
        var pins = new ArrayList<ProductHumanRequestReferences.SourcePin>();
        for (var use : plan.sourceUses()) {
            var source = plan.ordinarySource(context, use.sourceId(), use.fields(), jwt);
            pins.add(new ProductHumanRequestReferences.SourcePin(use, source.source(), source.scopeId(), source.scopeVersion(), plan.rights(use, jwt)));
        }
        var checkedComparison = command.comparison() == null ? null
                : comparisons.loadChecked(context, taskFacts, command.comparison(), jwt, historical != null);
        var comparison = checkedComparison == null ? null : checkedComparison.reference();
        if (checkedComparison != null) accessDeadlines.add(checkedComparison.accessUntil());
        if (!merged(uses(context.task().id(), command)).equals(merged(plan.sourceUses()))) throw changed();
        cleanup.assertUnchanged(scope);
        var gaps = comparison == null ? List.of(ProductHumanRequestReferences.Gap.NO_COMPARISON_SELECTED,
                ProductHumanRequestReferences.Gap.NO_REPORT_SELECTED) : comparison.reports().isEmpty()
                ? List.of(ProductHumanRequestReferences.Gap.NO_REPORT_SELECTED) : List.<ProductHumanRequestReferences.Gap>of();
        return new ProductHumanRequestReferences(taskFacts, refs, pins, comparison, scope, gaps);
    }

    private List<ProductHumanRequestLocks.SourceUse> uses(String task, Command command) {
        var uses = new ArrayList<>(command.materials().stream().map(ProductHumanMaterialReferences.Selection::use).toList());
        if (command.comparison() != null) uses.addAll(comparisons.discover(task, command.comparison()));
        return List.copyOf(uses);
    }

    private Object merged(List<ProductHumanRequestLocks.SourceUse> uses) {
        var result = new java.util.TreeMap<String, List<java.util.SortedSet<String>>>();
        for (var use : uses) {
            var fields = result.computeIfAbsent(use.sourceId(), ignored -> List.of(new java.util.TreeSet<>(), new java.util.TreeSet<>()));
            fields.getFirst().addAll(use.fields()); fields.getLast().addAll(use.comparisonFields());
        }
        return result;
    }

    private ProductHumanRequestStore.Key key(ProductHumanTaskTransaction.Context context, String id, Command command) {
        return ProductHumanRequestCommands.key(context.task().id(), context.actor(), id, command, json);
    }

    private ProductHumanCleanupScope.Snapshot scope(ProductHumanTaskTransaction.Context context) {
        var scope = cleanup.current(context.task().id(), new ProductHumanCleanupScope.Policy(policy.reference(), policy.version(), policy.evidenceSha256()));
        if (!context.task().organization().equals(scope.organization()) || scope.validUntil().isBefore(context.task().expiresAt())) throw changed();
        taskRetention(context);
        return scope;
    }

    private void taskRetention(ProductHumanTaskTransaction.Context context) {
        var rows = db.query("SELECT retention_ref,retention_version,retention_evidence_sha,created_at,expires_at FROM con3_task WHERE task_id=?",
                (rs, n) -> taskPolicy.reference().equals(rs.getString(1)) && taskPolicy.version() == rs.getLong(2)
                        && taskPolicy.evidenceSha256().equals(rs.getString(3))
                        && !rs.getTimestamp(4).toInstant().isAfter(clock.instant())
                        && rs.getTimestamp(5).toInstant().isAfter(rs.getTimestamp(4).toInstant())
                        && !rs.getTimestamp(5).toInstant().isAfter(rs.getTimestamp(4).toInstant().plus(taskPolicy.maxAge()))
                        && context.task().expiresAt().equals(rs.getTimestamp(5).toInstant()), context.task().id());
        if (rows.size() != 1 || !rows.getFirst()) throw changed();
    }

    private OrdinaryHumanRetention.Bounds bounds(ProductHumanTaskTransaction.Context context, ProductHumanRequestReferences refs) {
        Instant use = refs.facts().expiresAt(), save = use;
        Instant task = context.task().expiresAt();
        Instant audit = minimum(task, refs.cleanup().validUntil());
        for (var source : refs.sources()) {
            use = minimum(use, source.rights().useUntil()); save = minimum(save, source.rights().saveUntil());
            audit = minimum(audit, source.rights().saveUntil());
        }
        if (refs.comparison() != null) {
            use = minimum(use, refs.comparison().expiresAt()); save = minimum(save, refs.comparison().expiresAt());
        }
        return new OrdinaryHumanRetention.Bounds(task, task, use, save, audit, minimum(task, save));
    }

    private void verifyBounds(ProductHumanTaskTransaction.Context context, ProductHumanRequestStore.Stored stored, ProductHumanRequestReferences current) {
        taskRetention(context);
        var b = stored.manifest().bounds(); var allowed = bounds(context, current);
        if (b.taskWriteUntil().isAfter(allowed.taskWriteUntil()) || b.taskSaveUntil().isAfter(allowed.taskSaveUntil())
                || b.sourceUseUntil().isAfter(allowed.sourceUseUntil()) || b.sourceSaveUntil().isAfter(allowed.sourceSaveUntil())
                || b.auditSaveUntil().isAfter(allowed.auditSaveUntil()) || b.backupSaveUntil().isAfter(allowed.backupSaveUntil())) throw changed();
    }
    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static org.springframework.web.server.ResponseStatusException missing() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
    private static IllegalStateException changed() { return new IllegalStateException("Human request evidence changed"); }
}
