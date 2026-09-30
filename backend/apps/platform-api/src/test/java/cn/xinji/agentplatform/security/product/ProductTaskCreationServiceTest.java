package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductTaskCreationServiceTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void currentOrganizationGrantCreatesTaskAndReplayFailsClosed() throws Exception {
        JdbcDataSource primary = source();
        JdbcDataSource independent = source();
        script(primary, "db/dm8/V032__product_identity.sql");
        script(primary, "db/dm8/V034__product_consultation_initial_task.sql");
        script(primary, "db/dm8/V041__product_task_authorization_version.sql");
        script(primary, "db/dm8/V058__product_task_organization_create.sql");
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        var db = new JdbcTemplate(primary);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status," 
                + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                ISSUER, "member", "member", "encoded", "ACTIVE", 1);
        db.update("INSERT INTO con3_organization(organization_id,name,status,version,created_at) "
                + "VALUES('org','Organization','ACTIVE',1,CURRENT_TIMESTAMP)");
        Instant now = Instant.now();
        db.update("INSERT INTO id2_key_control(issuer,active_kid,version) VALUES(?,?,?)", ISSUER, null, 0);
        db.update("INSERT INTO id2_signing_key(kid,issuer,public_spki,private_pkcs8_encrypted,nonce,status," 
                + "created_at) VALUES(?,?,?,?,?,'ACTIVE',?)", "key", ISSUER, new byte[] {1},
                new byte[] {2}, new byte[12], Timestamp.from(now));
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, ISSUER,
                "member", "client", "key", Timestamp.from(now), Timestamp.from(now.plusSeconds(7200)),
                "ACTIVE", null);
        Jwt jwt = Jwt.withTokenValue("verified-by-resource-server").header("alg", "RS256")
                .issuer(ISSUER).subject("member").claim("jti", jti).claim("sid", sid).build();
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(primary));
        var watermark = new ProductGrantWatermark(db, authority, ISSUER);
        var grants = new ProductGrants(db, transactions, Clock.systemUTC());
        var directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
        var access = new ProductConsultationAccess(db, watermark, ISSUER, Clock.systemUTC());
        var service = new ProductTaskCreationService(db, directory, access, grants, watermark,
                transactions, new ProductRetentionPolicy("approved", 1, "a".repeat(64),
                Duration.ofHours(2)), new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), ISSUER);
        Instant expires = now.plusSeconds(1800);
        var input = new ProductTaskCreationService.Input("org", expires, "approved", 1);
        assertThatThrownBy(() -> service.create(jwt, "no-grant", input))
                .isInstanceOf(ResponseStatusException.class);
        var createGrant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                "member", "org", "CONSULTATION", "org", "org:1", "ORGANIZATION", "org", 1,
                "CREATE", "TASK_WORK"), now.plusSeconds(3600), 0, "maintainer", "issue-org", "b".repeat(64)));
        var result = service.create(jwt, "create-1", input);
        assertThat(result).isEqualTo(service.create(jwt, "create-1", input));
        assertThat(directory.find(result.taskId()).orElseThrow().dataScope()).isEqualTo(result.dataScope());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task_create_receipt", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_event WHERE operation='TASK_CREATED'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.create(jwt, "create-1", new ProductTaskCreationService.Input(
                "org", now.plusSeconds(1700), "approved", 1))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(jwt, "cross", new ProductTaskCreationService.Input(
                "elsewhere", expires, "approved", 1))).isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThatThrownBy(() -> service.create(jwt, "create-1", input))
                .isInstanceOf(ResponseStatusException.class);
        db.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThat(service.create(jwt, "create-1", input)).isEqualTo(result);
        assertThatThrownBy(() -> service.create(jwt, "too-long", new ProductTaskCreationService.Input(
                "org", now.plusSeconds(4000), "approved", 1)))
                .isInstanceOf(ResponseStatusException.class);
        watermark.change(() -> grants.revoke(createGrant.grantId(), 1, "maintainer", "revoke-org", "c".repeat(64)));
        assertThatThrownBy(() -> service.create(jwt, "create-1", input))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class)).isEqualTo(1);
    }

    @Test void emptySchemaCanReverseTheMigration() throws Exception {
        JdbcDataSource primary = source();
        script(primary, "db/dm8/V032__product_identity.sql");
        script(primary, "db/dm8/V034__product_consultation_initial_task.sql");
        script(primary, "db/dm8/V041__product_task_authorization_version.sql");
        script(primary, "db/dm8/V058__product_task_organization_create.sql");
        script(primary, "db/dm8/U058__product_task_organization_create.sql");
    }

    private static JdbcDataSource source() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
