package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Shared current human identity reads; locking is used only inside the established lock order. */
final class ProductHumanIdentityLocks {
    record Session(String client, String kid, String subject, Instant expires, String status) { }
    record Identity(long keyVersion, String keyStatus, Instant verifyUntil,
                            long clientVersion, long credentialVersion, Session session) { }

    private final JdbcTemplate db;
    private final String issuer;
    private final Clock clock;
    ProductHumanIdentityLocks(JdbcTemplate db, String issuer, Clock clock) {
        this.db = db; this.issuer = issuer; this.clock = clock;
    }
    Identity read(Jwt jwt, boolean locking) {
        if (locking && (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(db.getDataSource())))
            throw new IllegalStateException("Bound identity transaction required");
        String kid = (String) jwt.getHeaders().get("kid");
        Session before = session(jwt, false);
        long keyVersion = single(db.query("SELECT version FROM id2_key_control WHERE issuer=?" + (locking ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), issuer));
        var key = single(db.query("SELECT status,verify_until FROM id2_signing_key "
                        + "WHERE issuer=? AND kid=?" + (locking ? " FOR UPDATE" : ""), (rs, n) -> new Key(rs.getString(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), issuer, kid));
        long clientVersion = single(db.query("SELECT version FROM id2_client_current "
                        + "WHERE registered_id=? AND status='ACTIVE' AND version=1" + (locking ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), before.client()));
        long credentialVersion = single(db.query("SELECT credential_version FROM id2_subject "
                        + "WHERE issuer=? AND subject_id=? AND subject_kind='HUMAN' "
                        + "AND status='ACTIVE' AND password_hash IS NOT NULL" + (locking ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), issuer, jwt.getSubject()));
        Session current = session(jwt, locking);
        Instant now = clock.instant();
        if (!before.equals(current) || !kid.equals(current.kid()) || !jwt.getSubject().equals(current.subject())
                || !"ACTIVE".equals(current.status()) || !current.expires().isAfter(now)
                || !("ACTIVE".equals(key.status()) || "VERIFY_ONLY".equals(key.status())
                    && key.until() != null && key.until().isAfter(now)))
            throw new IllegalStateException("Current human session required");
        return new Identity(keyVersion, key.status(), key.until(), clientVersion, credentialVersion, current);
    }

    private record Key(String status, Instant until) { }

    private Session session(Jwt jwt, boolean locking) {
        return single(db.query("SELECT client_id,kid,subject_id,expires_at,status FROM id2_session "
                        + "WHERE issuer=? AND sid=? AND jti=?" + (locking ? " FOR UPDATE" : ""),
                (rs, n) -> new Session(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5)), issuer,
                jwt.getClaimAsString("sid"), jwt.getId()));
    }

    private static <T> T single(List<T> rows) {
        if (rows.size() != 1) throw new IllegalStateException("Current human identity unavailable");
        return rows.getFirst();
    }
}
