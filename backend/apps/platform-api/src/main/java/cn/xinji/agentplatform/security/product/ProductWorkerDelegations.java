package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/** A current human WRITE grant delegates one bounded task to one registered service. */
final class ProductWorkerDelegations {
    record Receipt(String delegationId, long version, String status) { }
    private record Existing(String digest, String operation, String id, long version, String status) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductGrantWatermark watermark;
    private final ProductConsultationAccess access;
    private final ProductTaskDirectory directory;
    private final Clock clock;
    private final String issuer;

    ProductWorkerDelegations(JdbcTemplate db, TransactionTemplate transactions,
            ProductGrantWatermark watermark, ProductConsultationAccess access,
            ProductTaskDirectory directory, Clock clock, String issuer) {
        this.db = db;
        this.transactions = transactions;
        this.watermark = watermark;
        this.access = access;
        this.directory = directory;
        this.clock = clock;
        this.issuer = issuer;
    }

    Receipt issue(String taskId, String serviceSubject, Instant expiresAt, Jwt human, String requestId) {
        if (blank(taskId) || blank(serviceSubject) || expiresAt == null || !expiresAt.isAfter(clock.instant())
                || !validRequest(requestId) || human == null || blank(human.getSubject()))
            throw new IllegalArgumentException("Exact human delegation required");
        var task = authorized(taskId, human);
        String digest = sha(human.getSubject(), taskId, serviceSubject, expiresAt.toString());
        return watermark.change(() -> transactions.execute(status -> {
            lock(taskId);
            var current = directory.find(taskId).orElseThrow(() -> new IllegalStateException("Task missing"));
            if (!task.equals(current) || !"IN_PROGRESS".equals(current.state())
                    || !current.expiresAt().isAfter(expiresAt))
                throw new IllegalStateException("Task changed");
            if (!sessionCurrent(human)) throw new IllegalStateException("Human session changed");
            String humanGrant = grant(human.getSubject(), task, expiresAt, false);
            String serviceGrant = grant(serviceSubject, task, expiresAt, true);
            Existing replay = replay(requestId);
            if (replay != null) {
                if (!digest.equals(replay.digest()) || !"ISSUE".equals(replay.operation())
                        || !"ACTIVE".equals(replay.status()) || replay.version() != 1)
                    throw new IllegalStateException("Delegation replay changed");
                return new Receipt(replay.id(), 1, "ACTIVE");
            }
            Instant now = clock.instant();
            String id = UUID.randomUUID().toString();
            db.update("INSERT INTO con3_worker_delegation VALUES(?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',1,?,?)",
                    id, taskId, task.organization(), task.dataScope(), task.authorizationVersion(),
                    issuer, human.getSubject(), issuer, serviceSubject, humanGrant, serviceGrant,
                    Timestamp.from(now), Timestamp.from(expiresAt));
            event(id, "ISSUE", 0, 1, human.getSubject(), now);
            receipt(requestId, digest, "ISSUE", id, 1, now);
            return new Receipt(id, 1, "ACTIVE");
        }));
    }

    Receipt revoke(String taskId, String id, long expectedVersion, Jwt human, String requestId) {
        if (blank(id) || expectedVersion < 1 || !validRequest(requestId) || human == null)
            throw new IllegalArgumentException("Exact delegation revocation required");
        authorized(taskId, human);
        String digest = sha(human.getSubject(), taskId, id, Long.toString(expectedVersion));
        return watermark.change(() -> transactions.execute(status -> {
            lock(taskId);
            Existing replay = replay(requestId);
            if (replay != null) {
                if (!digest.equals(replay.digest()) || !"REVOKE".equals(replay.operation())
                        || !id.equals(replay.id()) || !"REVOKED".equals(replay.status()))
                    throw new IllegalStateException("Delegation replay changed");
                return new Receipt(id, replay.version(), "REVOKED");
            }
            int changed = db.update("UPDATE con3_worker_delegation SET status='REVOKED',version=version+1 "
                            + "WHERE delegation_id=? AND task_id=? AND human_issuer=? AND human_subject=? "
                            + "AND status='ACTIVE' AND version=?", id, taskId, issuer, human.getSubject(),
                    expectedVersion);
            if (changed != 1) throw new IllegalStateException("Delegation changed");
            Instant now = clock.instant();
            event(id, "REVOKE", expectedVersion, expectedVersion + 1, human.getSubject(), now);
            receipt(requestId, digest, "REVOKE", id, expectedVersion + 1, now);
            return new Receipt(id, expectedVersion + 1, "REVOKED");
        }));
    }

    private ProductTaskDirectory.Task authorized(String taskId, Jwt human) {
        var task = directory.find(taskId).orElseThrow(() -> new IllegalStateException("Task missing"));
        if (!issuer.equals(task.ownerIssuer()) || !"IN_PROGRESS".equals(task.state())
                || !task.expiresAt().isAfter(clock.instant())
                || !access.permits(human, new ProductConsultationAccess.ResourceFacts(
                        task.organization(), task.id(), task.dataScope(), "TASK", task.id(),
                        task.authorizationVersion()), ProductAccessPolicy.Action.WRITE))
            throw new IllegalStateException("Current human WRITE grant required");
        return task;
    }

    private void lock(String taskId) {
        if (db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), taskId).size() != 1)
            throw new IllegalStateException("Task missing");
    }

    private boolean sessionCurrent(Jwt human) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM id2_session s JOIN id2_subject u "
                        + "ON u.issuer=s.issuer AND u.subject_id=s.subject_id WHERE s.issuer=? "
                        + "AND s.subject_id=? AND s.sid=? AND s.jti=? AND s.status='ACTIVE' "
                        + "AND s.expires_at>? AND u.status='ACTIVE' AND u.password_hash IS NOT NULL",
                Integer.class, issuer, human.getSubject(), human.getClaimAsString("sid"), human.getId(),
                Timestamp.from(clock.instant()));
        return count == 1;
    }

    private String grant(String subject, ProductTaskDirectory.Task task, Instant until, boolean service) {
        List<String> rows = db.query("SELECT g.grant_id FROM id2_grant g "
                        + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "AND c.authorization_epoch=g.grant_generation "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + (service ? "JOIN id2_workload w ON w.issuer=u.issuer AND w.subject_id=u.subject_id " : "")
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? "
                        + "AND g.domain_code='CONSULTATION' AND g.task_scope=? AND g.data_scope=? "
                        + "AND g.resource_type='TASK' AND g.resource_id=? AND g.resource_version=? "
                        + "AND g.action_code='WRITE' AND g.purpose='TASK_WORK' AND g.status='ACTIVE' "
                        + "AND g.expires_at>=? AND u.status='ACTIVE' "
                        + (service ? "AND u.subject_kind='SERVICE' AND w.workload_kind='AGENT' "
                                + "AND w.status='ACTIVE' AND w.expires_at>=? "
                                : "AND u.password_hash IS NOT NULL "),
                (rs, row) -> rs.getString(1), service
                        ? new Object[] {issuer, subject, task.organization(), task.id(), task.dataScope(),
                                task.id(), task.authorizationVersion(), Timestamp.from(until), Timestamp.from(until)}
                        : new Object[] {issuer, subject, task.organization(), task.id(), task.dataScope(),
                                task.id(), task.authorizationVersion(), Timestamp.from(until)});
        if (rows.size() != 1) throw new IllegalStateException("Exact current task grant required");
        return rows.getFirst();
    }

    private Existing replay(String requestId) {
        return db.query("SELECT r.request_sha,r.operation,r.delegation_id,r.result_version,d.status "
                        + "FROM con3_worker_delegation_receipt r JOIN con3_worker_delegation d "
                        + "ON d.delegation_id=r.delegation_id WHERE r.request_id=?",
                (rs, row) -> new Existing(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5)), requestId).stream().findFirst().orElse(null);
    }

    private void event(String id, String operation, long before, long after, String actor, Instant at) {
        db.update("INSERT INTO con3_worker_delegation_event VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, operation, before, after, actor, Timestamp.from(at));
    }

    private void receipt(String key, String digest, String operation, String id, long version, Instant at) {
        db.update("INSERT INTO con3_worker_delegation_receipt VALUES(?,?,?,?,?,?)",
                key, digest, operation, id, version, Timestamp.from(at));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean validRequest(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    }
    private static String sha(String... fields) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields)
                hash.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
