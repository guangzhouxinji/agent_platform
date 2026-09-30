package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.util.Base64URL;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSessionJwtTest {
    @Test void currentSessionIsRequiredAfterCryptographicVerification() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product-jwt-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/dm8/V032__product_identity.sql")));
        }
        JdbcTemplate db = new JdbcTemplate(source);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String issuer = "https://identity.example.org";
        Instant now = Instant.now();
        ProductSigningKeys keys = new ProductSigningKeys(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), issuer,
                Base64.getEncoder().encodeToString(secret), Duration.ofMinutes(5), Clock.systemUTC());
        String kid = keys.rotate("maintenance-unit-test");
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at,disabled_at) VALUES(?,?,?,?,?,?,?,?)",
                issuer, "internal-test-subject",
                "login", "encoded", "ACTIVE", 1, Timestamp.from(now), null);
        String jwt = new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(issuer).subject("internal-test-subject").audience(java.util.List.of("https://api.example.org"))
                .issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(now.plusSeconds(300))
                .id(jti).claim("sid", sid).build())).getTokenValue();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, issuer,
                "internal-test-subject", "client-id", kid, Timestamp.from(now),
                Timestamp.from(now.plusSeconds(300)), "ACTIVE", null);

        JdbcDataSource authoritySource = new JdbcDataSource();
        authoritySource.setURL("jdbc:h2:mem:jwt-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        ProductGrantWatermark watermark = new ProductGrantWatermark(db, authority, issuer);

        var decoder = new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys, db, watermark,
                issuer, "https://api.example.org");
        assertThat(decoder.decode(jwt).getSubject()).isEqualTo("internal-test-subject");
        String[] parts = jwt.split("\\.");
        String tampered = parts[0] + "." + Base64URL.encode("{\"sub\":\"other\"}") + "." + parts[2];
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(RuntimeException.class);
        db.update("UPDATE id2_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP WHERE sid=?", sid);
        assertThatThrownBy(() -> decoder.decode(jwt)).isInstanceOf(RuntimeException.class);
        db.update("UPDATE id2_session SET status='ACTIVE',revoked_at=NULL,issued_at=?,expires_at=? WHERE sid=?",
                Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(1)), sid);
        assertThatThrownBy(() -> decoder.decode(jwt)).isInstanceOf(RuntimeException.class);
    }
}
