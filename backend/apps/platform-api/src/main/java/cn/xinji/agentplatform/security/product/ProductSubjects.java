package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Subject lifecycle; maintenance calls must be guarded by the separate mTLS authority. */
public final class ProductSubjects {
    public static final class InvalidActivationException extends RuntimeException { }
    public record Registration(String subjectId, String oneTimeActivation) { }
    private record Subject(String status, String activationSha, Instant activationUntil, long version) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String issuer;
    private final Duration activationLifetime;
    private final int minimumPasswordLength;
    private final SecureRandom random = new SecureRandom();

    public ProductSubjects(JdbcTemplate db, TransactionTemplate transactions, PasswordEncoder passwords,
                           Clock clock, String issuer, Duration activationLifetime, int minimumPasswordLength) {
        if (issuer == null || !issuer.startsWith("https://") || activationLifetime == null
                || activationLifetime.isNegative() || activationLifetime.isZero() || minimumPasswordLength < 1)
            throw new IllegalArgumentException("Versioned identity policy is required");
        this.db = db;
        this.transactions = transactions;
        this.passwords = passwords;
        this.clock = clock;
        this.issuer = issuer;
        this.activationLifetime = activationLifetime;
        this.minimumPasswordLength = minimumPasswordLength;
    }

    public Registration register(String login, String maintainer) {
        if (blank(login) || blank(maintainer)) throw new IllegalArgumentException("Named registration required");
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String activation = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return transactions.execute(status -> {
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,activation_sha,"
                            + "activation_expires_at,status,credential_version,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    issuer, id, login, null, sha(activation), Timestamp.from(now.plus(activationLifetime)),
                    "PENDING", 1, Timestamp.from(now));
            event(id, "REGISTER", 0, 1, maintainer, now);
            return new Registration(id, activation);
        });
    }

    public void activate(String subjectId, String activation, String password) {
        if (blank(subjectId) || blank(activation) || password == null
                || password.length() < minimumPasswordLength)
            throw new IllegalArgumentException("Valid activation and password required");
        transactions.executeWithoutResult(status -> {
            Subject subject = lock(subjectId);
            if (subject == null || !"PENDING".equals(subject.status())
                    || !subject.activationUntil().isAfter(clock.instant())
                    || !MessageDigest.isEqual(subject.activationSha().getBytes(StandardCharsets.US_ASCII),
                            sha(activation).getBytes(StandardCharsets.US_ASCII)))
                throw new InvalidActivationException();
            int changed = db.update("UPDATE id2_subject SET status='ACTIVE',password_hash=?,activation_sha=NULL,"
                            + "activation_expires_at=NULL,credential_version=credential_version+1 "
                            + "WHERE issuer=? AND subject_id=? AND status='PENDING' AND credential_version=?",
                    passwords.encode(password), issuer, subjectId, subject.version());
            if (changed != 1) throw new IllegalStateException("Subject changed");
            event(subjectId, "ACTIVATE", subject.version(), subject.version() + 1, subjectId, clock.instant());
        });
    }

    public void disable(String subjectId, String maintainer) {
        if (blank(subjectId) || blank(maintainer)) throw new IllegalArgumentException("Named maintainer required");
        transactions.executeWithoutResult(status -> {
            Subject subject = lock(subjectId);
            if (subject == null || "DISABLED".equals(subject.status()))
                throw new IllegalStateException("Subject unavailable");
            Instant now = clock.instant();
            int changed = db.update("UPDATE id2_subject SET status='DISABLED',activation_sha=NULL,"
                            + "activation_expires_at=NULL,disabled_at=?,credential_version=credential_version+1 "
                            + "WHERE issuer=? AND subject_id=? AND credential_version=? "
                            + "AND (password_hash IS NOT NULL OR status='PENDING')", Timestamp.from(now),
                    issuer, subjectId, subject.version());
            if (changed != 1) throw new IllegalStateException("Subject changed");
            db.update("UPDATE id2_session SET status='REVOKED',revoked_at=? "
                            + "WHERE issuer=? AND subject_id=? AND status='ACTIVE'", Timestamp.from(now),
                    issuer, subjectId);
            event(subjectId, "DISABLE", subject.version(), subject.version() + 1, maintainer, now);
        });
    }

    private Subject lock(String id) {
        List<Subject> rows = db.query("SELECT status,activation_sha,activation_expires_at,credential_version "
                        + "FROM id2_subject WHERE issuer=? AND subject_id=? FOR UPDATE", (rs, row) ->
                new Subject(rs.getString(1), rs.getString(2), rs.getTimestamp(3) == null ? null
                        : rs.getTimestamp(3).toInstant(), rs.getLong(4)), issuer, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void event(String id, String operation, long before, long after, String actor, Instant when) {
        db.update("INSERT INTO id2_subject_event(event_id,issuer,subject_id,operation,before_version,"
                        + "after_version,actor_ref,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), issuer, id, operation, before, after, actor, Timestamp.from(when));
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
