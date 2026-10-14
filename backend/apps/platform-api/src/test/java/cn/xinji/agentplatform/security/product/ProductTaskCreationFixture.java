package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared real main/independent H2 creation setup; identity and grants are controlled test inputs. */
final class ProductTaskCreationFixture {
    static final String ISSUER = "https://identity.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    final JdbcTemplate db, authority;
    final Instant now, expires;
    final Jwt jwt;
    final TransactionTemplate transactions;
    final ProductGrantWatermark watermark;
    final ProductGrants grants;
    final JdbcProductTaskDirectory directory;
    final ProductConsultationAccess access;
    final ProductTaskCreationService service;
    final ProductTaskCreationService.Input input;

    ProductTaskCreationFixture() throws Exception {
        JdbcDataSource primary = ProductTaskCreationServiceTest.source();
        JdbcDataSource independent = ProductTaskCreationServiceTest.source();
        ProductTaskCreationServiceTest.script(primary, "db/dm8/V032__product_identity.sql");
        ProductTaskCreationServiceTest.script(primary, "db/dm8/V034__product_consultation_initial_task.sql");
        ProductTaskCreationServiceTest.script(primary, "db/dm8/V041__product_task_authorization_version.sql");
        ProductTaskCreationServiceTest.script(primary, "db/dm8/V058__product_task_organization_create.sql");
        ProductTaskCreationServiceTest.script(independent, "db/identity-authority/V001__grant_watermark.sql");
        db = new JdbcTemplate(primary);
        authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status," 
                + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                ISSUER, "member", "member", "encoded", "ACTIVE", 1);
        db.update("INSERT INTO con3_organization(organization_id,name,status,version,created_at) "
                + "VALUES('org','Organization','ACTIVE',1,CURRENT_TIMESTAMP)");
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        db.update("INSERT INTO id2_key_control(issuer,active_kid,version) VALUES(?,?,?)", ISSUER, null, 0);
        db.update("INSERT INTO id2_signing_key(kid,issuer,public_spki,private_pkcs8_encrypted,nonce,status," 
                + "created_at) VALUES(?,?,?,?,?,'ACTIVE',?)", "key", ISSUER, new byte[] {1},
                new byte[] {2}, new byte[12], Timestamp.from(now));
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, ISSUER,
                "member", "client", "key", Timestamp.from(now), Timestamp.from(now.plusSeconds(7200)),
                "ACTIVE", null);
        jwt = Jwt.withTokenValue("verified-by-resource-server").header("alg", "RS256")
                .issuer(ISSUER).subject("member").claim("jti", jti).claim("sid", sid).build();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(primary));
        watermark = new ProductGrantWatermark(db, authority, ISSUER);
        grants = new ProductGrants(db, transactions, Clock.systemUTC());
        directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
        access = new ProductConsultationAccess(db, watermark, ISSUER, Clock.systemUTC());
        service = new ProductTaskCreationService(db, directory, access, grants, watermark,
                transactions, new ProductRetentionPolicy("approved", 1, "a".repeat(64),
                Duration.ofHours(2)), new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), ISSUER);
        expires = now.plusSeconds(1800);
        input = new ProductTaskCreationService.Input("org", expires, "approved", 1);
    }
    ProductGrants.Receipt grant() {
        return watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                "member", "org", "CONSULTATION", "org", "org:1", "ORGANIZATION", "org", 1,
                "CREATE", "TASK_WORK"), now.plusSeconds(3600), 0, "maintainer", "issue-org", "b".repeat(64)));
    }
    ProductTaskCreationService using(ProductTaskDirectory selectedDirectory) { return using(selectedDirectory, Clock.systemUTC()); }
    ProductTaskCreationService using(ProductTaskDirectory selectedDirectory, Clock creationClock) {
        return new ProductTaskCreationService(db, selectedDirectory, access, grants, watermark,
                transactions, new ProductRetentionPolicy("approved", 1, "a".repeat(64), Duration.ofHours(2)),
                new ObjectMapper().findAndRegisterModules(), creationClock, ISSUER);
    }
}
