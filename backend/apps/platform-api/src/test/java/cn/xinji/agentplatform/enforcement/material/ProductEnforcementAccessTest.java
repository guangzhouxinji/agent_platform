package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
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
import org.springframework.transaction.support.TransactionTemplate;

class ProductEnforcementAccessTest {
    private static final String ISSUER = "https://enf-issuer.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void namedGrantSourceWatermarkRevocationAndUnavailabilityFailClosed() throws Exception {
        JdbcDataSource businessSource = source();
        JdbcDataSource authoritySource = source();
        try (var connection = businessSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/identity-authority/V002__enforcement_source_watermark.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/identity-authority/V005__enforcement_delete_watermark.sql")));
        }
        var business = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        authority.update("INSERT INTO enf3_source_watermark VALUES('PROJECT-DEV-OBSERVATION',1,'READY')");
        authority.update("INSERT INTO enf3_delete_watermark VALUES('PROJECT-DEV-OBSERVATION',1,0,'READY',?)",
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        business.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                ISSUER, "member", "named-developer", "encoded", "ACTIVE", 1);
        Instant now = Instant.now();
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        String kid = new ProductSigningKeys(business,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), ISSUER,
                Base64.getEncoder().encodeToString(master), Duration.ofMinutes(5), Clock.systemUTC())
                .rotate("maintainer");
        business.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, ISSUER,
                "member", "client", kid, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)),
                "ACTIVE", null);
        var jwt = Jwt.withTokenValue("verified-by-resource-server").header("alg", "RS256")
                .issuer(ISSUER).subject("member").claim("jti", jti).claim("sid", sid)
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        var watermark = new ProductGrantWatermark(business, authority, ISSUER);
        var grants = new ProductGrants(business,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        var scope = new ProductGrants.Scope(ISSUER, "member", "ORG-ENF", "ENFORCEMENT", "SOURCE",
                "PROJECT-DEV-OBSERVATION", "ENFORCEMENT_SOURCE", "PROJECT-DEV-OBSERVATION", 1, "SYNC", "SAVE");
        var grant = watermark.change(() -> grants.issue(scope, now.plusSeconds(3600), 0,
                "maintainer", "issue-enf", "a".repeat(64)));
        var access = new ProductEnforcementAccess(business, authority, watermark, jwt, ISSUER, Clock.systemUTC());
        access.require("member", "ORG-ENF", "SOURCE", "PROJECT-DEV-OBSERVATION", 1, "SYNC", "SAVE");
        access.requireSource("PROJECT-DEV-OBSERVATION", "SAVE", 1);
        assertThatThrownBy(() -> access.require("member", "ORG-OTHER", "SOURCE",
                "PROJECT-DEV-OBSERVATION", 1, "SYNC", "SAVE")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> access.require("member", "ORG-ENF", "SOURCE",
                "PROJECT-DEV-OBSERVATION", 1, "PUBLISH_RULE", "SAVE"))
                .isInstanceOf(SecurityException.class);
        authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id='PROJECT-DEV-OBSERVATION'");
        assertThatThrownBy(() -> access.requireSource("PROJECT-DEV-OBSERVATION", "SAVE", 1))
                .isInstanceOf(SecurityException.class);
        authority.update("UPDATE enf3_source_watermark SET state='READY' WHERE source_id='PROJECT-DEV-OBSERVATION'");
        watermark.change(() -> grants.revoke(grant.grantId(), 1, "maintainer", "revoke-enf", "b".repeat(64)));
        assertThatThrownBy(() -> access.require("member", "ORG-ENF", "SOURCE",
                "PROJECT-DEV-OBSERVATION", 1, "SYNC", "SAVE")).isInstanceOf(SecurityException.class);
        authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=? WHERE issuer=?",
                UUID.randomUUID().toString(), ISSUER);
        assertThatThrownBy(() -> access.requireSource("PROJECT-DEV-OBSERVATION", "SAVE", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }
}
