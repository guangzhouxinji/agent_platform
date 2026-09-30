package cn.xinji.agentplatform.security.product;

import com.nimbusds.jwt.SignedJWT;
import java.sql.Timestamp;
import java.text.ParseException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists OAuth authorization and its current access-token session atomically. */
public final class ProductAuthorizationService implements OAuth2AuthorizationService {
    private final OAuth2AuthorizationService delegate;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final String issuer;
    private final boolean dm8;

    public ProductAuthorizationService(OAuth2AuthorizationService delegate, JdbcTemplate db,
                                       TransactionTemplate transactions, String issuer) {
        this.delegate = delegate;
        this.db = db;
        this.transactions = transactions;
        this.issuer = issuer;
        this.dm8 = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().startsWith("jdbc:dm:")));
    }

    @Override public void save(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            var access = authorization.getAccessToken();
            SignedJWT jwt = access == null ? null : parse(access.getToken().getTokenValue());
            String sid = jwt == null ? null : claim(jwt, "sid");
            String jti = jwt == null ? null : claim(jwt, "jti");
            if (jwt != null) {
                if (!issuer.equals(claim(jwt, "iss")) || !authorization.getPrincipalName().equals(claim(jwt, "sub"))
                        || sid == null || jti == null || jwt.getHeader().getKeyID() == null)
                    throw new IllegalStateException("Incomplete access token identity");
                if (!access.isInvalidated()) {
                    List<String> subjects = db.query("SELECT status FROM id2_subject "
                                    + "WHERE issuer=? AND subject_id=? FOR UPDATE", (rs, row) -> rs.getString(1),
                            issuer, authorization.getPrincipalName());
                    if (subjects.size() != 1 || !"ACTIVE".equals(subjects.getFirst()))
                        throw new IllegalStateException("Subject is no longer active");
                }
            }
            delegate.save(authorization);
            if (jwt == null) return;
            if (access.isInvalidated()) {
                db.update("UPDATE id2_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP "
                        + "WHERE issuer=? AND sid=? AND jti=? AND status='ACTIVE'", issuer, sid, jti);
                return;
            }
            Integer existing = db.queryForObject("SELECT COUNT(*) FROM id2_session WHERE sid=? AND jti=?",
                    Integer.class, sid, jti);
            if (existing != null && existing == 0) {
                db.update("INSERT INTO id2_session(sid,jti,issuer,subject_id,client_id,kid,issued_at,"
                                + "expires_at,status,revoked_at) VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti,
                        issuer, authorization.getPrincipalName(), authorization.getRegisteredClientId(),
                        jwt.getHeader().getKeyID(), Timestamp.from(access.getToken().getIssuedAt()),
                        Timestamp.from(access.getToken().getExpiresAt()), "ACTIVE", null);
            }
        });
    }

    @Override public void remove(OAuth2Authorization authorization) {
        transactions.executeWithoutResult(status -> {
            var access = authorization.getAccessToken();
            if (access != null) {
                SignedJWT jwt = parse(access.getToken().getTokenValue());
                db.update("UPDATE id2_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP "
                                + "WHERE issuer=? AND sid=? AND jti=? AND status='ACTIVE'", issuer,
                        claim(jwt, "sid"), claim(jwt, "jti"));
            }
            delegate.remove(authorization);
        });
    }

    @Override public OAuth2Authorization findById(String id) { return delegate.findById(id); }
    @Override public OAuth2Authorization findByToken(String token, OAuth2TokenType type) {
        if (dm8 && type != null && "code".equals(type.getValue())) {
            List<String> ids = db.query("SELECT id FROM oauth2_authorization "
                            + "WHERE CAST(authorization_code_value AS VARCHAR(4000))=?",
                    (rs, row) -> rs.getString(1), token);
            return ids.isEmpty() ? null : delegate.findById(ids.getFirst());
        }
        return delegate.findByToken(token, type);
    }

    private static SignedJWT parse(String value) {
        try { return SignedJWT.parse(value); }
        catch (ParseException invalid) { throw new IllegalStateException("Access token must be signed JWT", invalid); }
    }

    private static String claim(SignedJWT jwt, String key) {
        try {
            Object value = jwt.getJWTClaimsSet().getClaim(key);
            return value instanceof String text && !text.isBlank() ? text : null;
        } catch (ParseException invalid) {
            throw new IllegalStateException("Invalid access token claims", invalid);
        }
    }
}
