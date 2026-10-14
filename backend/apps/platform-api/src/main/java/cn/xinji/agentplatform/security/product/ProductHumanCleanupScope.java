package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal maintenance preparation and fail-closed admission; never initializes on a read. */
final class ProductHumanCleanupScope {
    interface InitializationAdmission {
        void requireNewTaskWithoutHistory(String taskId, String organization, Policy policy,
                                          String operator, Instant now);
        default void requireCurrent(Instant now) {
            throw new IllegalStateException("Current maintenance admission required");
        }
    }
    record Policy(String id, long version, String sha) {
        Policy {
            if (blank(id) || version < 1 || !digest(sha)) throw new IllegalArgumentException("Exact policy required");
        }
    }
    record Snapshot(String scopeId, String organization, String taskId, long generation, String state,
                    String intent, Policy policy, String historySha, Instant createdAt, Instant validUntil) { }
    record Initialization(String scopeId, String organization, String taskId, String operation, Policy policy,
                          String operator, Instant occurredAt, Instant auditUntil, Instant validUntil) { }
    private record Prepared(Snapshot scope, Instant taskExpiresAt) { }
    private record EventHeader(long generation, String sha, Instant occurredAt, Instant auditUntil) { }
    private final JdbcTemplate main;
    private final JdbcTemplate authority;
    private final TransactionTemplate transactions;
    private final TransactionTemplate authorityTransactions;
    private final ProductComparisonJson json;
    private final Clock clock;
    private final InitializationAdmission admission;

    ProductHumanCleanupScope(JdbcTemplate main, JdbcTemplate authority,
                            DataSourceTransactionManager manager, ObjectMapper mapper, Clock clock,
                            InitializationAdmission admission) {
        if (main.getDataSource() == null || authority.getDataSource() == null
                || main.getDataSource() == authority.getDataSource() || manager.getDataSource() != main.getDataSource())
            throw new IllegalArgumentException("Matching primary manager and separate authority required");
        try (var a = main.getDataSource().getConnection(); var b = authority.getDataSource().getConnection()) {
            if (Objects.equals(a.getMetaData().getURL(), b.getMetaData().getURL()))
                AuthorityDataSourceSettings.resolve("shared-instance", a.getMetaData().getURL(), a.getSchema(),
                        a.getMetaData().getUserName(), b.getMetaData().getURL(), b.getSchema(),
                        b.getMetaData().getUserName(), "");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cleanup storage binding unavailable", failure);
        }
        this.main = main; this.authority = authority; this.transactions = new TransactionTemplate(manager);
        this.authorityTransactions = new TransactionTemplate(new DataSourceTransactionManager(authority.getDataSource()));
        this.json = new ProductComparisonJson(mapper); this.clock = Objects.requireNonNull(clock);
        this.admission = Objects.requireNonNull(admission);
    }

    // Callable only by a future admitted maintenance adapter with independent history evidence.
    Snapshot initialize(String taskId, Policy policy, String operator, Instant approvedAuditUntil,
                        Instant lastBackupUntil, Instant validUntil) {
        if (blank(taskId) || blank(operator) || TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(main.getDataSource())
                || TransactionSynchronizationManager.hasResource(authority.getDataSource()))
            throw new IllegalArgumentException("Standalone admitted maintenance operation required");
        Objects.requireNonNull(policy); Objects.requireNonNull(approvedAuditUntil);
        Objects.requireNonNull(lastBackupUntil); Objects.requireNonNull(validUntil);
        if (!millisecondExact(approvedAuditUntil) || !millisecondExact(lastBackupUntil) || !millisecondExact(validUntil))
            throw new IllegalArgumentException("Cleanup deadlines require exact millisecond precision");
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant auditUntil = minimum(now.plus(Duration.ofDays(365)), approvedAuditUntil);
        if (!now.isBefore(validUntil) || validUntil.isAfter(auditUntil) || lastBackupUntil.isAfter(validUntil))
            throw new IllegalArgumentException("Finite approved cleanup validity required");
        String intent = UUID.randomUUID().toString();
        Prepared prepared = transactions.execute(status -> {
            var tasks = main.query("SELECT organization_id,expires_at FROM con3_task WHERE task_id=? FOR UPDATE",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant()}, taskId);
            if (tasks.size() != 1 || !clock.instant().isBefore((Instant) tasks.getFirst()[1])
                    || ((Instant) tasks.getFirst()[1]).isAfter(validUntil)) throw unavailable();
            String organization = (String) tasks.getFirst()[0];
            admission.requireNewTaskWithoutHistory(taskId, organization, policy, operator, clock.instant());
            if (read(main, "con6_human_cleanup_scope", taskId) != null
                    || read(authority, "id6_human_cleanup_scope", taskId) != null) throw unavailable();
            for (String table : List.of("con6_human_package", "con6_human_receipt"))
                if (main.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Long.class, taskId) != 0)
                    throw unavailable();
            String scopeId = UUID.randomUUID().toString();
            Initialization event = new Initialization(scopeId, organization, taskId, "INITIALIZED", policy, operator, now, auditUntil, validUntil);
            String sha = json.sha(List.of(event));
            var pending = new Snapshot(scopeId, organization, taskId, 1, "PENDING", intent, policy, sha, now, validUntil);
            Instant taskExpiresAt = (Instant) tasks.getFirst()[1];
            requireCurrent(taskExpiresAt, validUntil);
            insert(authority, "id6_human_cleanup_scope", pending);
            var ready = new Snapshot(scopeId, organization, taskId, 1, "READY", null, policy, sha, now, validUntil);
            insert(main, "con6_human_cleanup_scope", ready);
            main.update("INSERT INTO con6_human_cleanup_event VALUES(?,1,?,?,?,?)", scopeId,
                    json.canonical(event), json.sha(event), Timestamp.from(now), Timestamp.from(auditUntil));
            requireCurrent(taskExpiresAt, validUntil);
            return new Prepared(ready, taskExpiresAt);
        });
        // An interrupted cross-database initialization remains PENDING for explicit reconciliation.
        Snapshot initialized = prepared.scope();
        return authorityTransactions.execute(status -> {
            requireCurrent(prepared.taskExpiresAt(), validUntil);
            if (authority.update("UPDATE id6_human_cleanup_scope SET state='READY',intent_id=NULL "
                    + "WHERE task_id=? AND scope_id=? AND generation=1 AND state='PENDING' AND intent_id=? "
                    + "AND policy_id=? AND policy_version=? AND policy_sha=? AND history_sha=? AND valid_until=?",
                    taskId, initialized.scopeId(), intent, policy.id(), policy.version(), policy.sha(),
                    initialized.historySha(), Timestamp.from(validUntil)) != 1) throw unavailable();
            requireCurrent(prepared.taskExpiresAt(), validUntil);
            Snapshot result = current(taskId, policy);
            requireCurrent(prepared.taskExpiresAt(), validUntil);
            return result;
        });
    }

    private void requireCurrent(Instant taskExpiresAt, Instant validUntil) {
        admission.requireCurrent(clock.instant());
        requireFinite(taskExpiresAt, validUntil);
    }

    private void requireFinite(Instant taskExpiresAt, Instant validUntil) {
        Instant at = clock.instant();
        if (!at.isBefore(taskExpiresAt) || !at.isBefore(validUntil)) throw unavailable();
    }

    Snapshot current(String taskId, Policy policy) {
        return verifiedHistory(taskId, policy).scope();
    }

    private ProductHumanCleanupHistory.Verified verifiedHistory(String taskId, Policy policy) {
        Snapshot before = read(authority, "id6_human_cleanup_scope", taskId);
        if (before == null || !"READY".equals(before.state()) || before.intent() != null
                || !before.policy().equals(policy) || !clock.instant().isBefore(before.validUntil())) throw unavailable();
        Snapshot primary = read(main, "con6_human_cleanup_scope", taskId);
        if (!before.equals(primary)) throw unavailable();
        var headers = main.query("SELECT generation,event_sha,occurred_at,audit_until FROM con6_human_cleanup_event "
                        + "WHERE scope_id=? ORDER BY generation",
                (rs, n) -> new EventHeader(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant()), before.scopeId());
        requireHeaders(before, headers);
        var events = main.query("SELECT generation,event_json,event_sha,occurred_at,audit_until "
                        + "FROM con6_human_cleanup_event WHERE scope_id=? ORDER BY generation",
                (rs, n) -> {
                    requireHeaders(before, headers);
                    var header = new EventHeader(rs.getLong(1), rs.getString(3), rs.getTimestamp(4).toInstant(),
                            rs.getTimestamp(5).toInstant());
                    if (n >= headers.size() || !header.equals(headers.get(n))) throw unavailable();
                    return new ProductHumanCleanupHistory.StoredEvent(header.generation(), rs.getString(2),
                            header.sha(), header.occurredAt(), header.auditUntil());
                }, before.scopeId());
        var verified = ProductHumanCleanupHistory.verify(before, events, json, clock,
                selection -> nativeBody(before, selection.packageId(), selection.packageRevision()));
        if (!before.equals(read(authority, "id6_human_cleanup_scope", taskId))) throw unavailable();
        requireHeaders(before, headers);
        return verified;
    }

    private void requireHeaders(Snapshot scope, List<EventHeader> headers) {
        Instant now = clock.instant();
        if (!now.isBefore(scope.validUntil()) || headers.size() != scope.generation() || headers.isEmpty()) throw unavailable();
        for (int i = 0; i < headers.size(); i++) {
            var h = headers.get(i);
            if (h.generation() != i + 1L || !digest(h.sha()) || h.occurredAt().isAfter(now)
                    || !h.auditUntil().isAfter(h.occurredAt()) || !now.isBefore(h.auditUntil())) throw unavailable();
        }
    }

    void requireBodyUnchanged(Snapshot historical, Snapshot expectedCurrent, ProductHumanCleanupHistory.BodyIdentity body) {
        if (historical == null || expectedCurrent == null) throw unavailable();
        var verified = verifiedHistory(expectedCurrent.taskId(), expectedCurrent.policy());
        if (!expectedCurrent.equals(verified.scope())) throw unavailable();
        verified.requireAncestorUnchanged(historical, body);
        var actual = nativeBody(expectedCurrent, body.packageId(), body.packageRevision());
        if (!actual.manifestPresent() || !body.manifestSha().equals(actual.retained().manifestSha())
                || actual.bodyAccessUntil() == null || !clock.instant().isBefore(actual.bodyAccessUntil())) throw unavailable();
        assertUnchanged(expectedCurrent);
        if (!clock.instant().isBefore(actual.bodyAccessUntil()) || !clock.instant().isBefore(actual.retained().auditUntil())) throw unavailable();
    }

    void requireCleared(Snapshot expectedCurrent, ProductHumanCleanupHistory.BodyIdentity body) {
        if (expectedCurrent == null) throw unavailable();
        var verified = verifiedHistory(expectedCurrent.taskId(), expectedCurrent.policy());
        if (!expectedCurrent.equals(verified.scope())) throw unavailable();
        verified.requireCleared(body);
        assertUnchanged(expectedCurrent);
    }

    private ProductHumanCleanupHistory.NativeBody nativeBody(Snapshot scope, String id, long revision) {
        var rows = main.query("SELECT p.task_id,p.organization_id,p.manifest_sha,p.task_version,p.data_scope,"
                        + "p.requester_issuer,p.requester_subject,p.created_at,p.body_until,p.body_access_until,"
                        + "CASE WHEN p.manifest_json IS NULL THEN 0 ELSE 1 END,h.processing_version,h.state,h.last_change_at,"
                        + "e.sequence_no,e.operation,e.before_version,e.after_version,e.actor_issuer,e.actor_subject,"
                        + "e.manifest_sha,e.request_sha,e.occurred_at,e.audit_until,r.task_id,r.actor_issuer,r.actor_subject,"
                        + "r.command_code,r.request_id,r.request_sha,r.event_sequence,r.created_at,r.audit_until,t.expires_at,"
                        + "(SELECT COUNT(*) FROM con6_human_handling x WHERE x.package_id=p.package_id AND x.package_revision=p.package_revision),"
                        + "(SELECT COUNT(*) FROM con6_human_event x WHERE x.package_id=p.package_id AND x.package_revision=p.package_revision),"
                        + "(SELECT COUNT(*) FROM con6_human_receipt x WHERE x.package_id=p.package_id AND x.package_revision=p.package_revision),u.subject_kind "
                        + "FROM con6_human_package p JOIN con6_human_handling h ON h.package_id=p.package_id AND h.package_revision=p.package_revision "
                        + "JOIN con6_human_event e ON e.package_id=p.package_id AND e.package_revision=p.package_revision "
                        + "JOIN con6_human_receipt r ON r.package_id=p.package_id AND r.package_revision=p.package_revision "
                        + "JOIN con3_task t ON t.task_id=p.task_id AND t.organization_id=p.organization_id "
                        + "JOIN id2_subject u ON u.issuer=p.requester_issuer AND u.subject_id=p.requester_subject "
                        + "WHERE p.package_id=? AND p.package_revision=?", (rs, n) -> {
                    Instant created = rs.getTimestamp(8).toInstant(), bodyUntil = rs.getTimestamp(9).toInstant();
                    Instant audit = rs.getTimestamp(24).toInstant(), taskUntil = rs.getTimestamp(34).toInstant();
                    Instant access = rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant();
                    if (!scope.taskId().equals(rs.getString(1)) || !scope.organization().equals(rs.getString(2))
                            || !scope.taskId().equals(rs.getString(25)) || rs.getLong(4) < 1 || !digest(rs.getString(3))
                            || blank(rs.getString(5)) || blank(rs.getString(6)) || blank(rs.getString(7))
                            || created.isAfter(clock.instant()) || !bodyUntil.isAfter(created) || bodyUntil.isAfter(taskUntil)
                            || bodyUntil.isAfter(created.plus(Duration.ofDays(180)))
                            || !clock.instant().isBefore(audit) || audit.isAfter(taskUntil)
                            || audit.isAfter(created.plus(Duration.ofDays(365)))
                            || access != null && (!access.isAfter(created) || access.isAfter(bodyUntil))
                            || rs.getLong(12) != 1 || !"WAITING".equals(rs.getString(13))
                            || !created.equals(rs.getTimestamp(14).toInstant()) || rs.getLong(15) != 1
                            || !"REQUESTED".equals(rs.getString(16)) || rs.getLong(17) != 0 || rs.getLong(18) != 1
                            || !rs.getString(6).equals(rs.getString(19)) || !rs.getString(7).equals(rs.getString(20))
                            || !rs.getString(3).equals(rs.getString(21)) || !digest(rs.getString(22))
                            || !created.equals(rs.getTimestamp(23).toInstant())
                            || !rs.getString(6).equals(rs.getString(26)) || !rs.getString(7).equals(rs.getString(27))
                            || !"REQUEST".equals(rs.getString(28)) || blank(rs.getString(29)) || rs.getString(29).length() > 180
                            || !rs.getString(22).equals(rs.getString(30)) || rs.getLong(31) != 1
                            || !created.equals(rs.getTimestamp(32).toInstant()) || !audit.equals(rs.getTimestamp(33).toInstant())
                            || rs.getLong(35) != 1 || rs.getLong(36) != 1 || rs.getLong(37) != 1
                            || !"HUMAN".equals(rs.getString(38))) throw unavailable();
                    var retained = new ProductHumanCleanupHistory.RetainedRequestScalars(rs.getString(3), rs.getLong(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), created, bodyUntil, audit, rs.getLong(12),
                            rs.getString(13), rs.getTimestamp(14).toInstant(), rs.getLong(15), rs.getString(22), rs.getString(29));
                    return new ProductHumanCleanupHistory.NativeBody(id, revision, rs.getString(1), rs.getString(2),
                            retained, access, rs.getInt(11) == 1);
                }, id, revision);
        if (rows.size() != 1) throw unavailable();
        return rows.getFirst();
    }

    void requireBinding(javax.sql.DataSource primary, javax.sql.DataSource independent, Clock expectedClock) {
        if (main.getDataSource() != primary || authority.getDataSource() != independent || clock != expectedClock)
            throw new IllegalArgumentException("Matching human cleanup authority required");
    }

    void assertUnchanged(Snapshot expected) {
        if (!expected.equals(current(expected.taskId(), expected.policy()))) throw unavailable();
    }

    void requireRestorable(Snapshot backup) {
        assertUnchanged(backup);
    }

    private Snapshot read(JdbcTemplate db, String table, String taskId) {
        var rows = db.query("SELECT scope_id,organization_id,task_id,generation,state,intent_id,policy_id,"
                        + "policy_version,policy_sha,history_sha,created_at,valid_until FROM " + table + " WHERE task_id=?",
                (rs, n) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), new Policy(rs.getString(7), rs.getLong(8), rs.getString(9)),
                        rs.getString(10), rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant()), taskId);
        if (rows.size() > 1) throw unavailable();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insert(JdbcTemplate db, String table, Snapshot s) {
        db.update("INSERT INTO " + table + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", s.scopeId(), s.organization(), s.taskId(),
                s.generation(), s.state(), s.intent(), s.policy().id(), s.policy().version(), s.policy().sha(), s.historySha(),
                Timestamp.from(s.createdAt()), Timestamp.from(s.validUntil()));
    }
    private static boolean millisecondExact(Instant value) {
        return value.equals(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Human cleanup authority unavailable"); }
}
