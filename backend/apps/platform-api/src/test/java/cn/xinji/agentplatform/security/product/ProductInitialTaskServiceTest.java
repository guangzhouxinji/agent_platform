package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
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

class ProductInitialTaskServiceTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void initialTaskUsesPersistedScopeAndCurrentGrantWithRollbackProtection() throws Exception {
        JdbcDataSource primarySource = source();
        JdbcDataSource authoritySource = source();
        script(primarySource, "db/dm8/V032__product_identity.sql");
        script(primarySource, "db/dm8/V034__product_consultation_initial_task.sql");
        script(primarySource, "db/dm8/V041__product_task_authorization_version.sql");
        script(authoritySource, "db/identity-authority/V001__grant_watermark.sql");
        JdbcTemplate db = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                ISSUER, "member", "accountable-member", "encoded", "ACTIVE", 1);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(primarySource));
        ProductGrantWatermark watermark = new ProductGrantWatermark(db, authority, ISSUER);
        var directory = new JdbcProductTaskDirectory(db, Clock.systemUTC());
        var grants = new ProductGrants(db, transactions, Clock.systemUTC());
        var service = new ProductInitialTaskService(directory, grants, watermark, transactions,
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), ISSUER,
                new ProductRetentionPolicy("retention-approval", 1, "a".repeat(64), Duration.ofHours(2)));
        var input = new ProductInitialTaskService.Input("organization", "Named organization", "member",
                "retention-approval", 1, Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.create(new ProductInitialTaskService.Input("organization", "Named",
                "member", "", 1, input.expiresAt()), "bad", "maintainer", "organization"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new ProductInitialTaskService.Input("organization", "Named",
                "member", "retention-approval", 1, Instant.now().plusSeconds(10800)),
                "too-long", "maintainer", "organization")).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class)).isZero();
        var result = service.create(input, "initial-1", "maintainer", "organization");
        assertThat(result).isEqualTo(service.create(input, "initial-1", "maintainer", "organization"));
        assertThatThrownBy(() -> service.create(input, "initial-2", "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_task", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_data_scope", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con3_event", Integer.class)).isEqualTo(1);
        var task = directory.find(result.taskId()).orElseThrow();
        assertThat(task.dataScope()).isEqualTo(result.dataScope());
        assertThat(task.authorizationVersion()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT retention_evidence_sha FROM con3_task WHERE task_id=?",
                String.class, task.id())).isEqualTo("a".repeat(64));

        // A real resource route supplies the directory facts, never client-supplied scope.
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("INSERT INTO id2_key_control(issuer,active_kid,version) VALUES(?,?,?)", ISSUER, null, 0);
        db.update("INSERT INTO id2_signing_key(kid,issuer,public_spki,private_pkcs8_encrypted,nonce,status,"
                        + "created_at) VALUES(?,?,?,?,?,'ACTIVE',?)", "key", ISSUER, new byte[] {1},
                new byte[] {2}, new byte[12], Timestamp.from(now));
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, ISSUER, "member",
                "client", "key", Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)), "ACTIVE", null);
        Jwt jwt = Jwt.withTokenValue("already-verified-by-resource-chain").header("alg", "RS256")
                .issuer(ISSUER).subject("member").claim("jti", jti).claim("sid", sid).build();
        var access = new ProductConsultationAccess(db, watermark, ISSUER, Clock.systemUTC());
        var facts = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(),
                task.dataScope(), "TASK", task.id(), task.version());
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isTrue();
        assertThat(access.permits(jwt, new ProductConsultationAccess.ResourceFacts(task.organization(),
                UUID.randomUUID().toString(), task.dataScope(), "TASK", task.id(), task.version()),
                ProductAccessPolicy.Action.READ)).isFalse();
        assertThat(access.permits(jwt, new ProductConsultationAccess.ResourceFacts(task.organization(),
                task.id(), "other:1", "TASK", task.id(), task.version()),
                ProductAccessPolicy.Action.READ)).isFalse();
        db.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isFalse();
        assertThatThrownBy(() -> service.create(input, "initial-1", "maintainer", "organization"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id=?", ISSUER, "member");
        assertThat(service.create(input, "initial-1", "maintainer", "organization")).isEqualTo(result);
        db.update("UPDATE con3_task SET version=version+1 WHERE task_id=?", task.id());
        var revised = directory.find(task.id()).orElseThrow();
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.authorizationVersion()).isEqualTo(1);
        assertThat(access.permits(jwt, new ProductConsultationAccess.ResourceFacts(revised.organization(),
                revised.id(), revised.dataScope(), "TASK", revised.id(), revised.authorizationVersion()),
                ProductAccessPolicy.Action.READ)).isTrue();
        watermark.change(() -> grants.revoke(result.grantId(), 1, "maintainer", "revoke", "b".repeat(64)));
        assertThat(access.permits(jwt, facts, ProductAccessPolicy.Action.READ)).isFalse();
        db.update("UPDATE id2_grant SET status='ACTIVE',grant_version=1 WHERE grant_id=?", result.grantId());
        assertThatThrownBy(() -> access.permits(jwt, facts, ProductAccessPolicy.Action.READ))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_grant SET status='REVOKED',grant_version=2 WHERE grant_id=?", result.grantId());
        script(primarySource, "db/dm8/U041__product_task_authorization_version.sql");
        script(primarySource, "db/dm8/U034__product_consultation_initial_task.sql");
    }

    private static JdbcDataSource source() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
