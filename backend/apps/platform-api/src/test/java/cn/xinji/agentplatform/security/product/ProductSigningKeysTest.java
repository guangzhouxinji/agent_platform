package cn.xinji.agentplatform.security.product;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.util.Base64URL;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

class ProductSigningKeysTest {
    @Test
    void persistentRotationPublishesOnlyPublicKeysAndRejectsTamperingAndWrongMasterKey() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product-keys;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/dm8/V032__product_identity.sql")));
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String master = Base64.getEncoder().encodeToString(secret);
        JdbcTemplate db = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        Instant start = Instant.parse("2026-09-27T00:00:00Z");
        ProductSigningKeys keys = new ProductSigningKeys(db, tx, "https://identity.example.test", master,
                Duration.ofMinutes(5), Clock.fixed(start, ZoneOffset.UTC));
        String first = keys.rotate("registered-maintainer");
        RSAKey active = key(keys, first);
        assertThat(active.isPrivate()).isTrue();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(first).build(),
                new JWTClaimsSet.Builder().issuer("https://identity.example.test").subject("subject-1").build());
        signed.sign(new RSASSASigner(active.toRSAPrivateKey()));
        assertThat(signed.verify(new RSASSAVerifier(active.toRSAPublicKey()))).isTrue();
        String[] parts = signed.serialize().split("\\.");
        String altered = parts[0] + "." + Base64URL.encode(new JWTClaimsSet.Builder()
                .issuer("https://identity.example.test").subject("other").build().toString()) + "." + parts[2];
        assertThat(SignedJWT.parse(altered).verify(new RSASSAVerifier(active.toRSAPublicKey()))).isFalse();

        String second = keys.rotate("registered-maintainer");
        assertThat(second).isNotEqualTo(first);
        RSAKey oldVerificationKey = key(keys, first);
        assertThat(oldVerificationKey.isPrivate()).isFalse();
        assertThat(signed.verify(new RSASSAVerifier(oldVerificationKey.toRSAPublicKey()))).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_key_event", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT version FROM id2_key_control WHERE issuer = ?", Long.class,
                "https://identity.example.test")).isEqualTo(2);

        byte[] wrong = new byte[32];
        new SecureRandom().nextBytes(wrong);
        ProductSigningKeys wrongKeys = new ProductSigningKeys(db, tx, "https://identity.example.test",
                Base64.getEncoder().encodeToString(wrong), Duration.ofMinutes(5), Clock.fixed(start, ZoneOffset.UTC));
        assertThatThrownBy(() -> key(wrongKeys, second)).isInstanceOf(IllegalStateException.class);
        ProductSigningKeys afterWindow = new ProductSigningKeys(db, tx, "https://identity.example.test", master,
                Duration.ofMinutes(5), Clock.fixed(start.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        assertThat(afterWindow.get(new JWKSelector(new JWKMatcher.Builder().keyID(first).build()), null)).isEmpty();
        keys.revoke(second, "registered-maintainer");
        assertThat(keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(second).build()), null)).isEmpty();
        assertThat(db.queryForObject("SELECT active_kid FROM id2_key_control WHERE issuer=?", String.class,
                "https://identity.example.test")).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_key_event", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> keys.revoke(second, "registered-maintainer"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RSAKey key(ProductSigningKeys store, String kid) {
        return (RSAKey) store.get(new JWKSelector(new JWKMatcher.Builder().keyID(kid).build()), null).getFirst();
    }
}
