package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current Task authority only: not source permission, input intent, a lease or result authority. */
final class ProductComparisonWorkerTaskAuthority {
    record Authorized(String serviceSubject, String humanSubject, String taskId,
                      long taskVersion, long grantGeneration, Instant expiresAt) { }
    private record Facts(String service, String human, String organization, String scope,
                         long authorizationVersion, Instant delegationExpiry, Instant workloadExpiry,
                         Instant serviceGrantExpiry, Instant humanGrantExpiry) { }

    private final JdbcTemplate db;
    private final ProductGrantWatermark grants;
    private final ProductTaskDirectory directory;
    private final Clock clock;
    private final String issuer;

    ProductComparisonWorkerTaskAuthority(JdbcTemplate db, ProductGrantWatermark grants,
            ProductTaskDirectory directory, Clock clock, String issuer) {
        this.db = Objects.requireNonNull(db);
        this.grants = Objects.requireNonNull(grants);
        this.directory = Objects.requireNonNull(directory);
        this.clock = Objects.requireNonNull(clock);
        if (blank(issuer)) throw new IllegalArgumentException("Configured issuer required");
        this.issuer = issuer;
    }

    Authorized require(HttpServletRequest request, String taskId, String delegationId) {
        if (blank(taskId) || blank(delegationId))
            throw new IllegalStateException("Exact Task and delegation required");
        Instant now = clock.instant();
        String fingerprint = fingerprint(request, now);
        long generation = grants.currentGeneration();
        var task = directory.find(taskId).orElseThrow(() -> new IllegalStateException("Task missing"));
        if (!taskId.equals(task.id()) || !"IN_PROGRESS".equals(task.state())
                || !task.expiresAt().isAfter(now))
            throw new IllegalStateException("Task is not executable");
        List<Facts> rows = db.query("SELECT d.service_subject,d.human_subject,d.organization_id,d.data_scope,"
                        + "d.authorization_version,d.expires_at,w.expires_at,sg.expires_at,hg.expires_at "
                        + "FROM con3_worker_delegation d "
                        + "JOIN id2_workload w ON w.issuer=d.service_issuer AND w.subject_id=d.service_subject "
                        + "JOIN id2_subject s ON s.issuer=w.issuer AND s.subject_id=w.subject_id "
                        + "JOIN id2_subject h ON h.issuer=d.human_issuer AND h.subject_id=d.human_subject "
                        + "JOIN id2_grant sg ON sg.grant_id=d.service_grant_id "
                        + "JOIN id2_current_epoch sc ON sc.current_grant_id=sg.grant_id "
                        + "AND sc.authorization_epoch=sg.grant_generation "
                        + "AND sc.issuer=sg.issuer AND sc.subject_id=sg.subject_id "
                        + "AND sc.organization=sg.organization AND sc.domain_code=sg.domain_code "
                        + "AND sc.resource_type=sg.resource_type AND sc.resource_id=sg.resource_id "
                        + "AND sc.action_code=sg.action_code AND sc.purpose=sg.purpose "
                        + "JOIN id2_grant hg ON hg.grant_id=d.human_grant_id "
                        + "JOIN id2_current_epoch hc ON hc.current_grant_id=hg.grant_id "
                        + "AND hc.authorization_epoch=hg.grant_generation "
                        + "AND hc.issuer=hg.issuer AND hc.subject_id=hg.subject_id "
                        + "AND hc.organization=hg.organization AND hc.domain_code=hg.domain_code "
                        + "AND hc.resource_type=hg.resource_type AND hc.resource_id=hg.resource_id "
                        + "AND hc.action_code=hg.action_code AND hc.purpose=hg.purpose "
                        + "WHERE d.delegation_id=? AND d.task_id=? AND d.human_issuer=? "
                        + "AND d.service_issuer=? AND d.status='ACTIVE' AND d.expires_at>? "
                        + "AND w.certificate_sha=? AND w.organization=? AND w.status='ACTIVE' "
                        + "AND w.workload_kind='AGENT' AND w.allowed_purpose='TASK_WORK' "
                        + "AND w.expires_at>? AND s.status='ACTIVE' "
                        + "AND s.subject_kind='SERVICE' AND s.password_hash IS NULL "
                        + "AND h.status='ACTIVE' AND h.subject_kind='HUMAN' AND h.password_hash IS NOT NULL "
                        + "AND sg.status='ACTIVE' AND hg.status='ACTIVE' "
                        + "AND sg.expires_at>? AND hg.expires_at>? "
                        + "AND sg.issuer=d.service_issuer AND sg.subject_id=d.service_subject "
                        + "AND hg.issuer=d.human_issuer AND hg.subject_id=d.human_subject "
                        + "AND sg.organization=d.organization_id AND hg.organization=d.organization_id "
                        + "AND sg.domain_code='CONSULTATION' AND hg.domain_code='CONSULTATION' "
                        + "AND sg.task_scope=d.task_id AND hg.task_scope=d.task_id "
                        + "AND sg.data_scope=d.data_scope AND hg.data_scope=d.data_scope "
                        + "AND sg.resource_type='TASK' AND hg.resource_type='TASK' "
                        + "AND sg.resource_id=d.task_id AND hg.resource_id=d.task_id "
                        + "AND sg.resource_version=d.authorization_version "
                        + "AND hg.resource_version=d.authorization_version "
                        + "AND sg.action_code='COMPARE' AND hg.action_code='COMPARE' "
                        + "AND sg.purpose='TASK_WORK' AND hg.purpose='TASK_WORK'",
                (rs, row) -> new Facts(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9).toInstant()),
                delegationId, taskId, issuer, issuer, Timestamp.from(now), fingerprint,
                task.organization(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows.size() != 1) throw new IllegalStateException("Worker authority is not current and exact");
        Facts facts = rows.getFirst();
        if (!task.organization().equals(facts.organization()) || !task.dataScope().equals(facts.scope())
                || task.authorizationVersion() != facts.authorizationVersion())
            throw new IllegalStateException("Worker Task scope changed");
        Instant expiry = List.of(task.expiresAt(), facts.delegationExpiry(), facts.workloadExpiry(),
                        facts.serviceGrantExpiry(), facts.humanGrantExpiry())
                .stream().min(Instant::compareTo).orElseThrow();
        var currentTask = directory.find(taskId)
                .orElseThrow(() -> new IllegalStateException("Task disappeared"));
        if (!task.equals(currentTask)) throw new IllegalStateException("Task changed during authorization");
        grants.assertUnchanged(generation);
        Instant finishedAt = clock.instant();
        if (!expiry.isAfter(finishedAt) || !fingerprint.equals(fingerprint(request, finishedAt)))
            throw new IllegalStateException("Worker authority expired or certificate changed");
        return new Authorized(facts.service(), facts.human(), taskId, task.version(), generation, expiry);
    }

    /** Invoke after intent validation; bind the result to the exact earlier authority snapshot. */
    void recheck(HttpServletRequest request, String taskId, String delegationId, Authorized authorized) {
        Authorized current = require(request, taskId, delegationId);
        if (!current.equals(authorized)) throw new IllegalStateException("Worker authority snapshot changed");
    }

    private static String fingerprint(HttpServletRequest request, Instant now) {
        // The trusted TLS container validates the chain; neither headers nor a worker JWT establish trust.
        if (request == null || !request.isSecure())
            throw new IllegalStateException("Trusted client TLS required");
        Object attribute = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0 || chain[0] == null)
            throw new IllegalStateException("Trusted client certificate required");
        try {
            chain[0].checkValidity(Date.from(now));
            List<String> usage = chain[0].getExtendedKeyUsage();
            if (usage == null || !usage.contains("1.3.6.1.5.5.7.3.2"))
                throw new IllegalStateException("Client authentication certificate required");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].getEncoded()));
        } catch (Exception invalid) {
            throw new IllegalStateException("Client certificate is invalid", invalid);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
