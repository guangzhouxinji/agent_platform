package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All request epochs precede all source rows; reference completeness belongs to the assembler. */
final class ProductHumanRequestLocks {
    record SourceUse(String sourceId, Set<String> fields, Set<String> comparisonFields) {
        SourceUse {
            if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("Exact source required");
            fields = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(fields));
            comparisonFields = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(comparisonFields));
            if (fields.isEmpty() || fields.stream().anyMatch(String::isBlank) || !fields.containsAll(comparisonFields))
                throw new IllegalArgumentException("Exact selected field uses required");
        }
    }
    record EpochKey(String domain, String type, String id, String action, String purpose) { }
    record SourceReference(ProductSourceAvailability.Active source, String scopeId, long scopeVersion, Instant validUntil) { }
    record Rights(Instant useUntil, Instant saveUntil) { }
    private record Epoch(long authorization, long source, String grantId) { }
    private record Scope(String id, long version, long pointerVersion, String organization, long taskVersion,
                         String sourceId, String contract, String decision, long sourceEpoch, long sourceVersion,
                         String status, String pointerStatus, Instant expiresAt) { }
    private record Witness(ProductSourceAvailability.Active source, ProductPatentDocumentAccess.Authority display,
                           ProductPatentDocumentAccess.Authority comparison, Instant until) { }
    private static final Comparator<EpochKey> ORDER = Comparator.comparing(EpochKey::domain)
            .thenComparing(EpochKey::type).thenComparing(EpochKey::id)
            .thenComparing(EpochKey::action).thenComparing(EpochKey::purpose);
    private final JdbcTemplate db;
    private final ProductConsultationAccess access;
    private final ProductGrantWatermark grants;
    private final ProductSourceAvailability sources;
    private final ProductPatentDocumentAccess documents;
    private final String issuer;
    private final Clock clock;

    ProductHumanRequestLocks(JdbcTemplate db, ProductConsultationAccess access, ProductGrantWatermark grants,
                            DataSourceTransactionManager manager, String issuer, Clock clock) {
        if (db.getDataSource() == null || db.getDataSource() != access.mainDataSource()
                || db.getDataSource() != grants.mainDataSource() || db.getDataSource() != manager.getDataSource())
            throw new IllegalArgumentException("Matching main request storage required");
        this.db = db; this.access = access; this.grants = grants;
        var sourceWatermark = new ProductSourceWatermark(db, grants.independentDatabase(), new TransactionTemplate(manager));
        this.sources = new ProductSourceAvailability(db, sourceWatermark, clock);
        this.documents = new ProductPatentDocumentAccess(db, grants, clock, issuer);
        this.issuer = Objects.requireNonNull(issuer);
        this.clock = Objects.requireNonNull(clock);
    }
    void requireBinding(DataSource main, ProductGrantWatermark expectedGrants,
                        ProductConsultationAccess expectedAccess, Clock expectedClock) {
        if (db.getDataSource() != main || grants != expectedGrants || access != expectedAccess || clock != expectedClock)
            throw new IllegalArgumentException("Request and Task must share storage, authority, access and clock");
    }

    Plan prepare(ProductHumanTaskTransaction.Context context, Jwt jwt, List<SourceUse> selections) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(db.getDataSource())
                || !context.actor().issuer().equals(issuer) || !context.actor().subject().equals(jwt.getSubject()))
            throw unavailable();
        var merged = new TreeMap<String, SourceUse>();
        for (SourceUse use : List.copyOf(selections)) merged.merge(use.sourceId(), use, (a, b) -> {
            var fields = new TreeSet<>(a.fields()); fields.addAll(b.fields());
            var comparison = new TreeSet<>(a.comparisonFields()); comparison.addAll(b.comparisonFields());
            return new SourceUse(a.sourceId(), fields, comparison);
        });
        var scopes = merged.values().stream().map(use -> scope(context.task().id(), use.sourceId())).toList();
        var keys = new TreeSet<>(ORDER);
        keys.add(key("TASK", context.task().id(), "READ", "TASK_WORK"));
        keys.add(key("TASK", context.task().id(), "WRITE", "TASK_WORK"));
        for (Scope scope : scopes) {
            keys.add(key("SOURCE_SCOPE", scope.id(), "DISPLAY", "SOURCE_DISPLAY"));
            if (!merged.get(scope.sourceId()).comparisonFields().isEmpty())
                keys.add(key("SOURCE_SCOPE", scope.id(), "COMPARE", "COMPARISON"));
        }
        return new Plan(context, List.copyOf(merged.values()), scopes, List.copyOf(keys));
    }

    final class Plan {
        private final ProductHumanTaskTransaction.Context context;
        private final List<SourceUse> uses;
        private final List<Scope> scopes;
        private final List<EpochKey> keys;
        private final Object transactionResource;
        private List<Epoch> epochs;
        private List<Instant> grantDeadlines;
        private List<Witness> witnesses;
        private Plan(ProductHumanTaskTransaction.Context context, List<SourceUse> uses,
                     List<Scope> scopes, List<EpochKey> keys) {
            this.context = context; this.uses = uses; this.scopes = scopes; this.keys = keys;
            this.transactionResource = TransactionSynchronizationManager.getResource(db.getDataSource());
        }
        List<EpochKey> keys() { return keys; }
        List<SourceUse> sourceUses() { bound(); return uses; }

        Rights rights(SourceUse use, Jwt jwt) {
            assertCurrent(jwt);
            if (!uses.contains(use)) throw unavailable();
            Instant readable = context.task().expiresAt(), saved = context.task().expiresAt();
            for (String field : use.fields()) {
                readable = minimum(readable, sources.useUntil(use.sourceId(), field, "DISPLAY"));
                saved = minimum(saved, sources.useUntil(use.sourceId(), field, "SAVE"));
            }
            for (String field : use.comparisonFields())
                readable = minimum(readable, sources.useUntil(use.sourceId(), field, "COMPARISON"));
            assertCurrent(jwt);
            return new Rights(readable, saved);
        }

        SourceReference ordinarySource(ProductHumanTaskTransaction.Context expected, String sourceId,
                                       Set<String> fields, Jwt jwt) {
            if (!context.equals(expected)) throw unavailable();
            assertCurrent(jwt);
            for (int i = 0; i < uses.size(); i++) {
                var use = uses.get(i);
                if (use.sourceId().equals(sourceId) && !fields.isEmpty() && use.fields().containsAll(fields)) {
                    var witness = witnesses.get(i); var scope = scopes.get(i);
                    Instant until = minimum(witness.until(), grantDeadlines.stream().min(Instant::compareTo).orElseThrow());
                    return new SourceReference(witness.source(), scope.id(), scope.version(), until);
                }
            }
            throw unavailable();
        }

        void acquire() {
            bound();
            if (epochs != null) throw unavailable();
            epochs = keys.stream().map(k -> epoch(context, k, true)).toList();
            for (Scope s : scopes) lock("SELECT source_id FROM con4_source_current WHERE source_id=? FOR UPDATE", s.sourceId());
            for (Scope s : scopes) {
                lock("SELECT scope_id FROM con4_task_source_current WHERE task_id=? AND source_id=? FOR UPDATE",
                        context.task().id(), s.sourceId());
                lock("SELECT scope_id FROM con4_task_source_scope WHERE scope_id=? AND scope_version=? FOR UPDATE", s.id(), s.version());
            }
        }

        void assertCurrent(Jwt jwt) {
            bound();
            if (epochs == null || !epochs.equals(keys.stream().map(k -> epoch(context, k, false)).toList())
                    || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                    || !context.actor().subject().equals(jwt.getSubject())) throw unavailable();
            var task = context.task();
            var facts = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(), task.dataScope(),
                    "TASK", task.id(), task.authorizationVersion());
            if (!access.permits(jwt, facts, ProductAccessPolicy.Action.READ)
                    || !access.permits(jwt, facts, ProductAccessPolicy.Action.WRITE)) throw unavailable();
            var deadlines = keys.stream().map(k -> grantUntil(context, k)).toList();
            if (grantDeadlines != null && !grantDeadlines.equals(deadlines)) throw unavailable();
            var current = new ArrayList<Witness>();
            for (int i = 0; i < uses.size(); i++) {
                SourceUse use = uses.get(i); Scope expected = scopes.get(i);
                if (!expected.equals(scope(task.id(), use.sourceId())) || !task.organization().equals(expected.organization())
                        || task.version() != expected.taskVersion() || !"ACTIVE".equals(expected.status())
                        || !"ACTIVE".equals(expected.pointerStatus())) throw unavailable();
                var source = sources.requireFields(use.sourceId(), use.fields(), "DISPLAY");
                if (!source.equals(sources.requireFields(use.sourceId(), use.fields(), "SAVE"))
                        || !source.contractVersion().equals(expected.contract()) || !source.decisionId().equals(expected.decision())
                        || source.epoch() != expected.sourceEpoch() || source.rowVersion() != expected.sourceVersion()) throw unavailable();
                var display = documents.require(task.id(), source, jwt);
                ProductPatentDocumentAccess.Authority comparison = null;
                if (!use.comparisonFields().isEmpty()) {
                    if (!source.equals(sources.requireFields(use.sourceId(), use.comparisonFields(), "COMPARISON"))) throw unavailable();
                    comparison = documents.requireComparison(task.id(), source, jwt);
                }
                if (!display.scopeId().equals(expected.id()) || display.scopeVersion() != expected.version()) throw unavailable();
                Instant until = minimum(task.expiresAt(), jwt.getExpiresAt(), expected.expiresAt());
                for (String field : new TreeSet<>(use.fields()))
                    until = minimum(until, sources.useUntil(use.sourceId(), field, "DISPLAY"), sources.useUntil(use.sourceId(), field, "SAVE"));
                for (String field : new TreeSet<>(use.comparisonFields()))
                    until = minimum(until, sources.useUntil(use.sourceId(), field, "COMPARISON"));
                if (!until.isAfter(clock.instant())) throw unavailable();
                current.add(new Witness(source, display, comparison, until));
            }
            if (witnesses != null && !witnesses.equals(current)) throw unavailable();
            Instant now = clock.instant();
            if (deadlines.stream().anyMatch(until -> !until.isAfter(now))
                    || !jwt.getExpiresAt().isAfter(now) || !task.expiresAt().isAfter(now)
                    || current.stream().anyMatch(witness -> !witness.until().isAfter(now))) throw unavailable();
            grantDeadlines = deadlines;
            witnesses = List.copyOf(current);
        }
        private void bound() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() || transactionResource == null
                    || TransactionSynchronizationManager.getResource(db.getDataSource()) != transactionResource) throw unavailable();
        }

        void assertDeadlines() {
            bound();
            Instant now = clock.instant();
            if (grantDeadlines == null || witnesses == null || !context.task().expiresAt().isAfter(now)
                    || grantDeadlines.stream().anyMatch(until -> !until.isAfter(now))
                    || witnesses.stream().anyMatch(witness -> !witness.until().isAfter(now))) throw unavailable();
        }
    }

    private Scope scope(String task, String source) {
        return single(db.query("SELECT s.scope_id,s.scope_version,c.row_version,s.organization_id,s.task_version,"
                + "s.source_id,s.contract_version,s.decision_id,s.source_epoch,s.source_row_version,s.status,c.status,s.expires_at "
                + "FROM con4_task_source_current c JOIN con4_task_source_scope s ON s.scope_id=c.scope_id "
                + "AND s.scope_version=c.scope_version AND s.task_id=c.task_id AND s.source_id=c.source_id "
                + "WHERE c.task_id=? AND c.source_id=?", (rs, n) -> new Scope(rs.getString(1), rs.getLong(2), rs.getLong(3),
                    rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9),
                    rs.getLong(10), rs.getString(11), rs.getString(12), rs.getTimestamp(13).toInstant()), task, source));
    }
    private Epoch epoch(ProductHumanTaskTransaction.Context context, EpochKey key, boolean locking) {
        return single(db.query("SELECT authorization_epoch,source_epoch,current_grant_id FROM id2_current_epoch "
                + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code=? AND resource_type=? "
                + "AND resource_id=? AND action_code=? AND purpose=?" + (locking ? " FOR UPDATE" : ""),
                (rs, n) -> new Epoch(rs.getLong(1), rs.getLong(2), rs.getString(3)), issuer, context.actor().subject(),
                context.task().organization(), key.domain(), key.type(), key.id(), key.action(), key.purpose()));
    }
    private Instant grantUntil(ProductHumanTaskTransaction.Context context, EpochKey key) {
        return single(db.query("SELECT g.expires_at FROM id2_current_epoch c JOIN id2_grant g "
                + "ON g.grant_id=c.current_grant_id AND g.issuer=c.issuer AND g.subject_id=c.subject_id "
                + "AND g.organization=c.organization AND g.domain_code=c.domain_code AND g.resource_type=c.resource_type "
                + "AND g.resource_id=c.resource_id AND g.action_code=c.action_code AND g.purpose=c.purpose "
                + "AND g.grant_generation=c.authorization_epoch WHERE c.issuer=? AND c.subject_id=? "
                + "AND c.organization=? AND c.domain_code=? AND c.resource_type=? AND c.resource_id=? "
                + "AND c.action_code=? AND c.purpose=? AND g.task_scope=? AND g.status='ACTIVE'",
                (rs, n) -> rs.getTimestamp(1).toInstant(), issuer, context.actor().subject(), context.task().organization(),
                key.domain(), key.type(), key.id(), key.action(), key.purpose(), context.task().id()));
    }
    private void lock(String sql, Object... args) { single(db.query(sql, (rs, n) -> rs.getString(1), args)); }
    private static EpochKey key(String type, String id, String action, String purpose) {
        return new EpochKey("CONSULTATION", type, id, action, purpose);
    }
    private static Instant minimum(Instant... instants) { return List.of(instants).stream().min(Instant::compareTo).orElseThrow(); }
    private static <T> T single(List<T> rows) { if (rows.size() != 1) throw unavailable(); return rows.getFirst(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Human request lock facts unavailable"); }
}
