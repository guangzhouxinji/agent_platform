package cn.xinji.agentplatform.security.product;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

public final class ProductSigningKeys implements JWKSource<SecurityContext> {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final String issuer;
    private final SecretKeySpec wrappingKey;
    private final Clock clock;
    private final Duration verificationWindow;
    private final SecureRandom random = new SecureRandom();

    public ProductSigningKeys(JdbcTemplate db, TransactionTemplate transactions, String issuer,
                              String base64MasterKey, Duration verificationWindow, Clock clock) {
        if (issuer == null || !issuer.startsWith("https://") || issuer.endsWith("/")) {
            throw new IllegalArgumentException("A fixed HTTPS issuer is required");
        }
        byte[] decoded = Base64.getDecoder().decode(base64MasterKey);
        if (decoded.length != 32) throw new IllegalArgumentException("A 256-bit wrapping key is required");
        if (verificationWindow == null || verificationWindow.isNegative() || verificationWindow.isZero()) {
            throw new IllegalArgumentException("A positive token verification window is required");
        }
        this.db = db;
        this.transactions = transactions;
        this.issuer = issuer;
        this.wrappingKey = new SecretKeySpec(decoded, "AES");
        this.clock = clock;
        this.verificationWindow = verificationWindow;
    }

    public String rotate(String maintainer) {
        if (maintainer == null || maintainer.isBlank()) throw new IllegalArgumentException("Maintainer required");
        return transactions.execute(status -> {
            List<Control> controls = db.query("SELECT active_kid, version FROM id2_key_control "
                    + "WHERE issuer = ? FOR UPDATE", (rs, row) -> new Control(rs.getString(1), rs.getLong(2)), issuer);
            if (controls.isEmpty()) {
                db.update("INSERT INTO id2_key_control(issuer,active_kid,version) VALUES(?,?,?)", issuer, null, 0);
                controls = db.query("SELECT active_kid, version FROM id2_key_control "
                        + "WHERE issuer = ? FOR UPDATE", (rs, row) -> new Control(rs.getString(1), rs.getLong(2)), issuer);
            }
            Control current = controls.getFirst();
            String kid = UUID.randomUUID().toString();
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(3072, random);
                var pair = generator.generateKeyPair();
                byte[] nonce = new byte[12];
                random.nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, new GCMParameterSpec(128, nonce));
                cipher.updateAAD((issuer + ":" + kid).getBytes(StandardCharsets.UTF_8));
                byte[] encrypted = cipher.doFinal(pair.getPrivate().getEncoded());
                Instant now = clock.instant();
                db.update("INSERT INTO id2_signing_key(kid,issuer,public_spki,private_pkcs8_encrypted,nonce,"
                                + "status,created_at,verify_until) VALUES(?,?,?,?,?,?,?,?)",
                        kid, issuer, pair.getPublic().getEncoded(), encrypted, nonce, "ACTIVE",
                        Timestamp.from(now), null);
                if (current.activeKid() != null) {
                    db.update("UPDATE id2_signing_key SET status = 'VERIFY_ONLY', verify_until = ? "
                                    + "WHERE kid = ? AND issuer = ? AND status = 'ACTIVE'",
                            Timestamp.from(now.plus(verificationWindow)), current.activeKid(), issuer);
                }
                int changed = db.update("UPDATE id2_key_control SET active_kid = ?, version = ? "
                                + "WHERE issuer = ? AND version = ?", kid, current.version() + 1, issuer, current.version());
                if (changed != 1) throw new IllegalStateException("Signing key control version changed");
                db.update("INSERT INTO id2_key_event(event_id,issuer,kid,operation,before_version,"
                                + "after_version,maintainer_ref,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), issuer, kid, "ROTATE", current.version(),
                        current.version() + 1, maintainer, Timestamp.from(now));
                return kid;
            } catch (Exception failure) {
                throw new IllegalStateException("Signing key rotation failed", failure);
            }
        });
    }

    public void revoke(String kid, String maintainer) {
        if (kid == null || kid.isBlank() || maintainer == null || maintainer.isBlank())
            throw new IllegalArgumentException("Key and maintainer required");
        transactions.executeWithoutResult(status -> {
            List<Control> controls = db.query("SELECT active_kid,version FROM id2_key_control "
                    + "WHERE issuer=? FOR UPDATE", (rs, row) -> new Control(rs.getString(1), rs.getLong(2)), issuer);
            if (controls.size() != 1) throw new IllegalStateException("Key control unavailable");
            Control current = controls.getFirst();
            int changed = db.update("UPDATE id2_signing_key SET status='REVOKED',verify_until=? "
                            + "WHERE issuer=? AND kid=? AND status IN ('ACTIVE','VERIFY_ONLY')",
                    Timestamp.from(clock.instant()), issuer, kid);
            if (changed != 1) throw new IllegalStateException("Key is not current or verifiable");
            db.update("UPDATE id2_session SET status='REVOKED',revoked_at=? "
                            + "WHERE issuer=? AND kid=? AND status='ACTIVE'",
                    Timestamp.from(clock.instant()), issuer, kid);
            changed = db.update("UPDATE id2_key_control SET active_kid=?,version=? "
                            + "WHERE issuer=? AND version=?", kid.equals(current.activeKid()) ? null : current.activeKid(),
                    current.version() + 1, issuer, current.version());
            if (changed != 1) throw new IllegalStateException("Key control version changed");
            db.update("INSERT INTO id2_key_event(event_id,issuer,kid,operation,before_version,"
                            + "after_version,maintainer_ref,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), issuer, kid, "REVOKE", current.version(),
                    current.version() + 1, maintainer, Timestamp.from(clock.instant()));
        });
    }

    @Override public List<JWK> get(JWKSelector selector, SecurityContext context) {
        List<JWK> keys = new ArrayList<>();
        db.query("SELECT kid,public_spki,private_pkcs8_encrypted,nonce,status FROM id2_signing_key "
                + "WHERE issuer = ? AND (status = 'ACTIVE' OR (status = 'VERIFY_ONLY' AND verify_until > ?)) "
                + "ORDER BY status", rs -> {
            String kid = rs.getString(1);
            try {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(rs.getBytes(2)));
                RSAKey.Builder builder = new RSAKey.Builder(publicKey).keyID(kid);
                if ("ACTIVE".equals(rs.getString(5))) {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, rs.getBytes(4)));
                    cipher.updateAAD((issuer + ":" + kid).getBytes(StandardCharsets.UTF_8));
                    RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                            new PKCS8EncodedKeySpec(cipher.doFinal(rs.getBytes(3))));
                    builder.privateKey(privateKey);
                }
                keys.add(builder.build());
            } catch (Exception failure) {
                throw new IllegalStateException("Signing key material is unavailable", failure);
            }
        }, issuer, Timestamp.from(clock.instant()));
        return selector.select(new JWKSet(keys));
    }

    private record Control(String activeKid, long version) { }
}
