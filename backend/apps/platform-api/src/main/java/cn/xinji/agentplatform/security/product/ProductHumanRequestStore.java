package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal persistence stage. Its application must establish source/save rights before calling. */
final class ProductHumanRequestStore {
    record Manifest(String taskId, String organization, String dataScope, long taskVersion,
                    String packageId, long packageRevision, OrdinaryHumanHandling.Actor requester,
                    OrdinaryHumanHandling.Binding binding, ProductHumanRequestReferences references, String reason,
                    String purpose, OrdinaryHumanRetention.Policy policy, OrdinaryHumanRetention.Bounds bounds,
                    Instant createdAt) {
        Manifest {
            Objects.requireNonNull(binding); Objects.requireNonNull(policy); Objects.requireNonNull(bounds);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(requester);
            Objects.requireNonNull(references);
            if (blank(taskId) || blank(organization) || blank(dataScope) || blank(packageId)
                    || taskVersion < 1 || packageRevision < 1 || blank(reason) || reason.length() > 4000
                    || !"ORDINARY_ASSISTANCE".equals(purpose) || !binding.equals(references.binding())
                    || !taskId.equals(references.facts().taskId()) || taskVersion != references.facts().taskVersion()
                    || !organization.equals(references.cleanup().organization()))
                throw new IllegalArgumentException("Exact ordinary request manifest required");
        }
    }
    record Key(String requestId, String requestSha) {
        Key {
            if (blank(requestId) || requestId.length() > 180 || requestSha == null
                    || !requestSha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Original request key and digest required");
        }
    }
    record Stored(Manifest manifest, String manifestSha, Key key, Instant auditUntil) {
        OrdinaryHumanHandling.Packet packet() {
            return new OrdinaryHumanHandling.Packet(manifest.taskId(), manifest.packageId(), manifest.packageRevision(),
                    manifestSha, manifest.requester(), manifest.binding(), manifest.references().coordinates());
        }
    }
    record Receipt(String taskId, String packageId, long packageRevision, String manifestSha,
                   OrdinaryHumanHandling.Actor requester, long eventSequence, String requestSha,
                   Instant createdAt, Instant auditUntil) { }
    record Lookup(Receipt receipt, Instant bodyAccessUntil) {
        boolean bodyAvailable(Instant now) { return bodyAccessUntil != null && now.isBefore(bodyAccessUntil); }
    }
    private final JdbcTemplate db;
    private final ProductComparisonJson json;
    private final Clock clock;

    ProductHumanRequestStore(JdbcTemplate db, ObjectMapper mapper, Clock clock) {
        this.db = db; this.json = new ProductComparisonJson(mapper); this.clock = clock;
    }

    void requireBinding(javax.sql.DataSource main, Clock expectedClock) {
        if (db.getDataSource() != main || clock != expectedClock) throw new IllegalArgumentException("Matching human request storage required");
    }

    Optional<Lookup> locate(ProductHumanTaskTransaction.Context context, String requestId) {
        boundTransaction(context);
        var keys = db.queryForList("SELECT request_sha FROM con6_human_receipt WHERE task_id=? AND actor_issuer=? "
                + "AND actor_subject=? AND command_code='REQUEST' AND request_id=?", String.class,
                context.task().id(), context.actor().issuer(), context.actor().subject(), requestId);
        if (keys.size() > 1) throw corrupt();
        return keys.isEmpty() ? Optional.empty() : locate(context, new Key(requestId, keys.getFirst()));
    }

    Optional<Lookup> locate(ProductHumanTaskTransaction.Context context, Key key) {
        boundTransaction(context);
        var rows = db.query("SELECT r.package_id,r.package_revision,r.request_sha,r.event_sequence,r.created_at,r.audit_until,"
                + "e.manifest_sha,e.actor_issuer,e.actor_subject,e.request_sha,e.occurred_at,e.audit_until,e.operation,"
                + "e.before_version,e.after_version,p.manifest_sha,p.organization_id,p.data_scope,p.requester_issuer,"
                + "p.requester_subject,p.created_at,p.body_access_until,p.body_until FROM con6_human_receipt r "
                + "JOIN con6_human_event e ON e.package_id=r.package_id AND e.package_revision=r.package_revision "
                + "AND e.sequence_no=r.event_sequence JOIN con6_human_package p ON p.task_id=r.task_id "
                + "AND p.package_id=r.package_id AND p.package_revision=r.package_revision WHERE r.task_id=? "
                + "AND r.actor_issuer=? AND r.actor_subject=? AND r.command_code='REQUEST' AND r.request_id=?", (rs, n) -> {
            if (!key.requestSha().equals(rs.getString(3))) throw new ProductHumanRequestConflict();
            Instant created = rs.getTimestamp(5).toInstant(), audit = rs.getTimestamp(6).toInstant();
            String sha = rs.getString(7); var actor = context.actor();
            Instant accessUntil = rs.getTimestamp(22) == null ? null : rs.getTimestamp(22).toInstant();
            if (rs.getLong(2) < 1 || rs.getLong(4) != 1 || sha == null || !sha.matches("[a-f0-9]{64}")
                    || !sha.equals(rs.getString(16)) || !actor.issuer().equals(rs.getString(8))
                    || !actor.subject().equals(rs.getString(9)) || !key.requestSha().equals(rs.getString(10))
                    || !created.equals(rs.getTimestamp(11).toInstant()) || !audit.equals(rs.getTimestamp(12).toInstant())
                    || !"REQUESTED".equals(rs.getString(13)) || rs.getLong(14) != 0 || rs.getLong(15) != 1
                    || !context.task().organization().equals(rs.getString(17)) || !context.task().dataScope().equals(rs.getString(18))
                    || !actor.issuer().equals(rs.getString(19)) || !actor.subject().equals(rs.getString(20))
                    || !created.equals(rs.getTimestamp(21).toInstant()) || created.isAfter(clock.instant())
                    || !audit.isAfter(clock.instant()) || audit.isAfter(context.task().expiresAt())
                    || audit.isAfter(created.plus(java.time.Duration.ofDays(365)))
                    || accessUntil != null && (!accessUntil.isAfter(created) || accessUntil.isAfter(rs.getTimestamp(23).toInstant()))) throw corrupt();
            return new Lookup(new Receipt(context.task().id(), rs.getString(1), rs.getLong(2), sha, actor, 1,
                    key.requestSha(), created, audit), accessUntil);
        }, context.task().id(), context.actor().issuer(), context.actor().subject(), key.requestId());
        Long nativeCount = db.queryForObject("SELECT COUNT(*) FROM con6_human_receipt WHERE task_id=? AND actor_issuer=? "
                + "AND actor_subject=? AND command_code='REQUEST' AND request_id=?", Long.class, context.task().id(),
                context.actor().issuer(), context.actor().subject(), key.requestId());
        if (rows.size() > 1 || nativeCount == null || nativeCount != rows.size()) throw corrupt();
        return rows.stream().findFirst();
    }

    Optional<Stored> replay(ProductHumanTaskTransaction.Context context, Key key) {
        return locate(context, key).map(found -> body(context, key, found));
    }

    Stored body(ProductHumanTaskTransaction.Context context, Key key, Lookup found) {
        boundTransaction(context);
        if (!found.bodyAvailable(clock.instant())) throw corrupt();
        var receipt = found.receipt();
        var stored = read(context, receipt.packageId(), receipt.packageRevision(), key);
        if (!stored.manifestSha().equals(receipt.manifestSha()) || !stored.auditUntil().equals(receipt.auditUntil())
                || !bodyAccessUntil(stored.manifest()).equals(found.bodyAccessUntil())) throw corrupt();
        return stored;
    }

    Stored save(ProductHumanTaskTransaction.Context context, Manifest manifest, Key key) {
        boundTransaction(context);
        var original = replay(context, key);
        if (original.isPresent()) return original.get();
        validate(context, manifest, true);
        var retention = new OrdinaryHumanRetention(manifest.policy());
        retention.requireWritable(clock.instant(), manifest.createdAt(), null, manifest.bounds());
        Instant bodyUntil = retention.bodyUntil(manifest.createdAt(), null, manifest.bounds());
        Instant auditUntil = retention.auditUntil(manifest.createdAt(), manifest.bounds());
        String sha = json.sha(manifest);
        var packet = new Stored(manifest, sha, key, auditUntil).packet();
        OrdinaryHumanHandling.request(packet, manifest.createdAt(), retention, manifest.bounds());
        db.update("INSERT INTO con6_human_package VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", manifest.packageId(),
                manifest.packageRevision(), manifest.taskId(), manifest.organization(), manifest.dataScope(), manifest.taskVersion(),
                manifest.requester().issuer(), manifest.requester().subject(), json.canonical(manifest), sha,
                Timestamp.from(manifest.createdAt()), Timestamp.from(bodyUntil), Timestamp.from(bodyAccessUntil(manifest)));
        db.update("INSERT INTO con6_human_handling VALUES(?,?,1,'WAITING',?)", manifest.packageId(),
                manifest.packageRevision(), Timestamp.from(manifest.createdAt()));
        db.update("INSERT INTO con6_human_event VALUES(?,?,1,'REQUESTED',0,1,?,?,?,?,?,?)", manifest.packageId(),
                manifest.packageRevision(), manifest.requester().issuer(), manifest.requester().subject(), sha, key.requestSha(),
                Timestamp.from(manifest.createdAt()), Timestamp.from(auditUntil));
        db.update("INSERT INTO con6_human_receipt VALUES(?,?,?,'REQUEST',?,?,?,?,1,?,?)", manifest.taskId(),
                manifest.requester().issuer(), manifest.requester().subject(), key.requestId(), key.requestSha(),
                manifest.packageId(), manifest.packageRevision(), Timestamp.from(manifest.createdAt()), Timestamp.from(auditUntil));
        return read(context, manifest.packageId(), manifest.packageRevision(), key);
    }

    private Stored read(ProductHumanTaskTransaction.Context context, String id, long revision, Key key) {
        var packages = db.query("SELECT manifest_json,manifest_sha,task_id,organization_id,data_scope,task_version,"
                        + "requester_issuer,requester_subject,created_at,body_until,body_access_until FROM con6_human_package "
                        + "WHERE package_id=? AND package_revision=?", (rs, n) -> {
                    var accessUntil = rs.getTimestamp(11);
                    if (accessUntil == null || !clock.instant().isBefore(accessUntil.toInstant())) throw corrupt();
                    var m = json.stored(rs.getString(1), Manifest.class);
                    String sha = rs.getString(2);
                    if (m == null || !id.equals(m.packageId()) || revision != m.packageRevision()
                            || !sha.equals(json.sha(m)) || !m.taskId().equals(rs.getString(3))
                            || !m.organization().equals(rs.getString(4)) || !m.dataScope().equals(rs.getString(5))
                            || m.taskVersion() != rs.getLong(6) || !m.requester().issuer().equals(rs.getString(7))
                            || !m.requester().subject().equals(rs.getString(8)) || !m.createdAt().equals(rs.getTimestamp(9).toInstant()))
                        throw corrupt();
                    validate(context, m, false);
                    var retention = new OrdinaryHumanRetention(m.policy());
                    retention.requireReadable(clock.instant(), m.createdAt(), null, m.bounds());
                    retention.requireReceiptCompatibility(m.createdAt(), m.bounds());
                    if (!retention.bodyUntil(m.createdAt(), null, m.bounds()).equals(rs.getTimestamp(10).toInstant())
                            || rs.getTimestamp(11) == null || !bodyAccessUntil(m).equals(rs.getTimestamp(11).toInstant())) throw corrupt();
                    return new Stored(m, sha, key, retention.auditUntil(m.createdAt(), m.bounds()));
                }, id, revision);
        if (packages.size() != 1) throw corrupt();
        var stored = packages.getFirst();
        var m = stored.manifest();
        if (!stored.auditUntil().isAfter(clock.instant())) throw corrupt();
        var handling = db.query("SELECT processing_version,state,last_change_at FROM con6_human_handling "
                + "WHERE package_id=? AND package_revision=?", (rs, n) -> rs.getLong(1) == 1
                    && "WAITING".equals(rs.getString(2)) && m.createdAt().equals(rs.getTimestamp(3).toInstant()), id, revision);
        var events = db.query("SELECT sequence_no,operation,before_version,after_version,actor_issuer,actor_subject,"
                + "manifest_sha,request_sha,occurred_at,audit_until FROM con6_human_event WHERE package_id=? AND package_revision=?",
                (rs, n) -> rs.getLong(1) == 1 && "REQUESTED".equals(rs.getString(2)) && rs.getLong(3) == 0 && rs.getLong(4) == 1
                    && m.requester().issuer().equals(rs.getString(5)) && m.requester().subject().equals(rs.getString(6))
                    && stored.manifestSha().equals(rs.getString(7)) && key.requestSha().equals(rs.getString(8))
                    && m.createdAt().equals(rs.getTimestamp(9).toInstant()) && stored.auditUntil().equals(rs.getTimestamp(10).toInstant()), id, revision);
        var receipts = db.query("SELECT task_id,actor_issuer,actor_subject,command_code,request_id,request_sha,"
                + "event_sequence,created_at,audit_until FROM con6_human_receipt WHERE package_id=? AND package_revision=?",
                (rs, n) -> m.taskId().equals(rs.getString(1)) && m.requester().issuer().equals(rs.getString(2))
                    && m.requester().subject().equals(rs.getString(3)) && "REQUEST".equals(rs.getString(4))
                    && key.requestId().equals(rs.getString(5)) && key.requestSha().equals(rs.getString(6)) && rs.getLong(7) == 1
                    && m.createdAt().equals(rs.getTimestamp(8).toInstant()) && stored.auditUntil().equals(rs.getTimestamp(9).toInstant()), id, revision);
        if (!complete(handling) || !complete(events) || !complete(receipts)) throw corrupt();
        return stored;
    }

    private void validate(ProductHumanTaskTransaction.Context context, Manifest m, boolean creating) {
        var task = context.task();
        if (!task.id().equals(m.taskId()) || !task.organization().equals(m.organization())
                || !task.dataScope().equals(m.dataScope()) || !context.actor().equals(m.requester())
                || creating && task.version() != m.taskVersion() || m.bounds().taskWriteUntil().isAfter(task.expiresAt())
                || m.bounds().taskSaveUntil().isAfter(task.expiresAt())) throw corrupt();
    }

    static Instant bodyAccessUntil(Manifest m) {
        return java.util.stream.Stream.of(new OrdinaryHumanRetention(m.policy()).bodyUntil(m.createdAt(), null, m.bounds()),
                m.bounds().taskWriteUntil(), m.bounds().sourceUseUntil()).min(Instant::compareTo).orElseThrow();
    }

    private void boundTransaction(ProductHumanTaskTransaction.Context context) {
        if (context == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(db.getDataSource())))
            throw new IllegalStateException("Human request store requires its bound Task transaction");
    }
    private static boolean complete(List<Boolean> rows) { return rows.size() == 1 && rows.getFirst(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalStateException corrupt() { return new IllegalStateException("Human request persistence closure invalid"); }
}
