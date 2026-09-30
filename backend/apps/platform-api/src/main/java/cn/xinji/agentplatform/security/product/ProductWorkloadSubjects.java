package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Certificate-bound service subjects, maintained under the independent grant authority. */
public final class ProductWorkloadSubjects {
    public enum Kind { AGENT, SEARCH_JOB, FILE_JOB }
    public record Registration(String subjectId, long version) { }
    private record Existing(String subjectId, String digest, long version, String status) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductGrantWatermark watermark;
    private final Clock clock;
    private final String issuer;

    public ProductWorkloadSubjects(JdbcTemplate db, TransactionTemplate transactions,
            ProductGrantWatermark watermark, Clock clock, String issuer) {
        this.db = db;
        this.transactions = transactions;
        this.watermark = watermark;
        this.clock = clock;
        this.issuer = issuer;
        db.queryForObject("SELECT COUNT(*) FROM id2_workload", Integer.class);
    }

    public Registration register(String certificateSha, String organization, Kind kind, Instant expiresAt,
            String requestId, String maintainer) {
        if (certificateSha == null || !certificateSha.matches("[0-9a-f]{64}")
                || blank(organization) || organization.length() > 200 || kind == null
                || expiresAt == null || !expiresAt.isAfter(clock.instant())
                || !validRequest(requestId) || blank(maintainer) || maintainer.length() > 200)
            throw new IllegalArgumentException("Exact service certificate and registration required");
        String digest = sha(certificateSha, organization, kind.name(), expiresAt.toString());
        watermark.assertCurrent();
        Existing replay = existing(requestId);
        if (replay != null) return replayRegistration(replay, digest);
        return watermark.change(() -> transactions.execute(status -> {
            Existing current = existing(requestId);
            if (current != null) return replayRegistration(current, digest);
            Instant now = clock.instant();
            String subject = UUID.randomUUID().toString();
            db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                            + "credential_version,created_at,subject_kind) "
                            + "VALUES(?,?,?,NULL,'ACTIVE',1,?,'SERVICE')",
                    issuer, subject, "workload:" + subject, Timestamp.from(now));
            db.update("INSERT INTO id2_workload VALUES(?,?,?,? ,?,'TASK_WORK','ACTIVE',1,?,?)",
                    issuer, subject, certificateSha, organization, kind.name(),
                    Timestamp.from(expiresAt), Timestamp.from(now));
            db.update("INSERT INTO id2_subject_event VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), issuer, subject, "SERVICE_REGISTER", 0, 1,
                    maintainer, Timestamp.from(now));
            event(subject, "REGISTER", 0, 1, maintainer, certificateSha, now);
            db.update("INSERT INTO id2_workload_request VALUES(?,?,?,?,?,?,?)", requestId, digest,
                    issuer, subject, "REGISTER", 1, Timestamp.from(now));
            return new Registration(subject, 1);
        }));
    }

    public long revoke(String subject, String organization, long expectedVersion,
            String requestId, String maintainer) {
        if (blank(subject) || expectedVersion < 1 || !validRequest(requestId)
                || blank(organization) || organization.length() > 200
                || blank(maintainer) || maintainer.length() > 200)
            throw new IllegalArgumentException("Exact service revocation required");
        String digest = sha(subject, organization, Long.toString(expectedVersion));
        watermark.assertCurrent();
        Existing replay = existing(requestId);
        if (replay != null) return replayRevocation(replay, subject, digest);
        return watermark.change(() -> transactions.execute(status -> {
            Existing current = existing(requestId);
            if (current != null) return replayRevocation(current, subject, digest);
            List<String> fingerprints = db.query("SELECT certificate_sha FROM id2_workload "
                            + "WHERE issuer=? AND subject_id=? AND organization=? "
                            + "AND status='ACTIVE' AND version=? FOR UPDATE",
                    (rs, row) -> rs.getString(1), issuer, subject, organization, expectedVersion);
            if (fingerprints.size() != 1) throw new IllegalStateException("Service subject changed");
            Instant now = clock.instant();
            if (db.update("UPDATE id2_workload SET status='REVOKED',version=version+1 "
                            + "WHERE issuer=? AND subject_id=? AND organization=? "
                            + "AND status='ACTIVE' AND version=?",
                    issuer, subject, organization, expectedVersion) != 1)
                throw new IllegalStateException("Service subject changed");
            if (db.update("UPDATE id2_subject SET status='DISABLED',disabled_at=?,"
                            + "credential_version=credential_version+1 WHERE issuer=? AND subject_id=? "
                            + "AND subject_kind='SERVICE' AND status='ACTIVE'",
                    Timestamp.from(now), issuer, subject) != 1)
                throw new IllegalStateException("Service identity changed");
            long next = expectedVersion + 1;
            db.update("INSERT INTO id2_subject_event VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), issuer, subject, "SERVICE_DISABLE", 1, 2,
                    maintainer, Timestamp.from(now));
            event(subject, "REVOKE", expectedVersion, next, maintainer, fingerprints.getFirst(), now);
            db.update("INSERT INTO id2_workload_request VALUES(?,?,?,?,?,?,?)", requestId, digest,
                    issuer, subject, "REVOKE", next, Timestamp.from(now));
            return next;
        }));
    }

    private Existing existing(String requestId) {
        return db.query("SELECT r.subject_id,r.request_sha,r.result_version,w.status "
                        + "FROM id2_workload_request r JOIN id2_workload w "
                        + "ON w.issuer=r.issuer AND w.subject_id=r.subject_id WHERE r.request_id=?",
                (rs, row) -> new Existing(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4)), requestId).stream().findFirst().orElse(null);
    }

    private Registration replayRegistration(Existing row, String digest) {
        if (!digest.equals(row.digest()) || row.version() != 1 || !"ACTIVE".equals(row.status()))
            throw new IllegalStateException("Service registration replay is no longer current");
        return new Registration(row.subjectId(), row.version());
    }

    private long replayRevocation(Existing row, String subject, String digest) {
        if (!subject.equals(row.subjectId()) || !digest.equals(row.digest())
                || !"REVOKED".equals(row.status()))
            throw new IllegalStateException("Service revocation replay differs");
        return row.version();
    }

    private void event(String subject, String operation, long before, long after,
            String maintainer, String fingerprint, Instant now) {
        db.update("INSERT INTO id2_workload_event VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), issuer, subject, operation, before, after,
                maintainer, fingerprint, Timestamp.from(now));
    }

    private static boolean validRequest(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String sha(String... fields) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String field : fields)
                hash.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
