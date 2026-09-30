package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class ProductConsultationAccessTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void exactCurrentGrantPermitsAndCrossScopeRevocationAndRollbackDeny() throws Exception {
        JdbcDataSource primarySource = source();
        JdbcDataSource authoritySource = source();
        try (var connection = primarySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate primary = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                ISSUER, "member", "enrolled-member", "encoded", "ACTIVE", 1);
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        ProductSigningKeys keys = new ProductSigningKeys(primary,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)), ISSUER,
                Base64.getEncoder().encodeToString(master), Duration.ofMinutes(5), Clock.systemUTC());
        String kid = keys.rotate("maintainer");
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String token = new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer(ISSUER).subject("member").audience(java.util.List.of("https://api.example.org"))
                .issuedAt(now).notBefore(now.minusSeconds(1)).expiresAt(now.plusSeconds(3600))
                .id(jti).claim("sid", sid).build())).getTokenValue();
        primary.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, ISSUER,
                "member", "client-id", kid, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)),
                "ACTIVE", null);
        ProductGrantWatermark watermark = new ProductGrantWatermark(primary, authority, ISSUER);
        ProductGrants grants = new ProductGrants(primary,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)), Clock.systemUTC());
        var scope = new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION", "task-1",
                "dataset/record/version", "TASK", "task-1", 1, "READ", "TASK_WORK");
        var receipt = watermark.change(() -> grants.issue(scope, Instant.now().plusSeconds(3600), 0,
                "maintainer", "issue", "a".repeat(64)));
        var access = new ProductConsultationAccess(primary, watermark, ISSUER, Clock.systemUTC());
        Jwt jwt = new ProductIssuerConfiguration(new MockEnvironment()).jwtDecoder(keys, primary, watermark,
                ISSUER, "https://api.example.org").decode(token);
        var facts = new ProductConsultationAccess.ResourceFacts("organization", "task-1",
                "dataset/record/version", "TASK", "task-1", 1);
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isTrue();
        assertThat(access.permits(jwt, new ProductConsultationAccess.ResourceFacts("organization", "task-2",
                facts.dataScope(), "TASK", "task-1", 1), ProductAccessPolicy.Action.READ)).isFalse();
        assertThat(access.permits(jwt, new ProductConsultationAccess.ResourceFacts("organization", "task-1",
                "other/version", "TASK", "task-1", 1), ProductAccessPolicy.Action.READ)).isFalse();
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.WRITE)).isFalse();
        primary.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id=?",
                ISSUER, "member");
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isFalse();
        primary.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id=?",
                ISSUER, "member");
        primary.update("UPDATE id2_session SET status='REVOKED' WHERE sid=?", sid);
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isFalse();
        primary.update("UPDATE id2_session SET status='ACTIVE' WHERE sid=?", sid);
        watermark.change(() -> grants.revoke(receipt.grantId(), 1, "maintainer", "revoke", "b".repeat(64)));
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isFalse();
        primary.update("UPDATE id2_grant SET status='ACTIVE',grant_version=1 WHERE grant_id=?", receipt.grantId());
        assertThatThrownBy(() -> access.permits(jwt, facts, ProductAccessPolicy.Action.READ))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JdbcDataSource source() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }
}
