package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
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

class ProductWorkerDelegationsTest {
    @Test void exactHumanAndServiceGrantsBindRevocableDelegationAndIndependentWatermark() throws Exception {
        String issuer = "https://worker-delegation.example.org";
        var primary = new JdbcDataSource();
        primary.setURL("jdbc:h2:mem:delegate-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var independent = new JdbcDataSource();
        independent.setURL("jdbc:h2:mem:delegate-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = primary.getConnection(); var authority = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql",
                    "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql",
                    "V046__product_workload_subject.sql",
                    "V047__product_task_worker_delegation.sql"})
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(authority, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        var db = new JdbcTemplate(primary);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(primary));
        var clock = Clock.systemUTC();
        Instant expiry = Instant.now().plusSeconds(7200);
        Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,?)",
                issuer, "human", "human", now);
        db.update("INSERT INTO id2_key_control VALUES(?,NULL,0)", issuer);
        db.update("INSERT INTO id2_signing_key VALUES(?,?,?,? ,?,'ACTIVE',?,NULL)",
                "key", issuer, new byte[] {1}, new byte[] {1}, new byte[12], now);
        db.update("INSERT INTO id2_session VALUES('session','jti',?,?,?,'key',?,?,'ACTIVE',NULL)",
                issuer, "human", "client", now, Timestamp.from(expiry));
        db.update("INSERT INTO con3_organization VALUES('org','Organization','ACTIVE',1,?)", now);
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                        + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,"
                        + "created_at) VALUES('task','org',?,'human','IN_PROGRESS',1,?,'policy',1,?,?)",
                issuer, Timestamp.from(expiry), "a".repeat(64), now);
        db.update("INSERT INTO con3_data_scope VALUES('scope','task','org',1,'TASK_METADATA')");
        var workloads = new ProductWorkloadSubjects(db, tx, watermark, clock, issuer);
        var worker = workloads.register("b".repeat(64), "org", ProductWorkloadSubjects.Kind.AGENT,
                expiry, "register-service", "maintainer#certificate");
        var grants = new ProductGrants(db, tx, clock);
        Instant grantExpiry = expiry.minusSeconds(300);
        var humanScope = new ProductGrants.Scope(issuer, "human", "org", "CONSULTATION",
                "task", "scope:1", "TASK", "task", 1, "WRITE", "TASK_WORK");
        watermark.change(() -> grants.issue(humanScope, grantExpiry, 0,
                "maintainer#certificate", "human-write", "c".repeat(64)));
        watermark.change(() -> grants.issueWorkload(issuer, worker.subjectId(), "org", "task",
                grantExpiry, 0, "maintainer#certificate", "service-write", "d".repeat(64)));
        var directory = new JdbcProductTaskDirectory(db, clock);
        var access = new ProductConsultationAccess(db, watermark, issuer, clock);
        var delegations = new ProductWorkerDelegations(db, tx, watermark, access, directory, clock, issuer);
        Jwt jwt = Jwt.withTokenValue("signed-in-route").header("alg", "RS256")
                .issuer(issuer).subject("human").claim("jti", "jti").claim("sid", "session")
                .issuedAt(Instant.now()).expiresAt(grantExpiry).build();
        Instant delegatedUntil = grantExpiry.minusSeconds(300);
        var issued = delegations.issue("task", worker.subjectId(), delegatedUntil, jwt, "delegate-one");
        assertThat(delegations.issue("task", worker.subjectId(), delegatedUntil, jwt,
                "delegate-one")).isEqualTo(issued);
        assertThatThrownBy(() -> delegations.issue("other", worker.subjectId(), delegatedUntil,
                jwt, "cross-task")).isInstanceOf(IllegalStateException.class);
        assertThat(delegations.revoke("task", issued.delegationId(), 1, jwt,
                "revoke-one").status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> delegations.issue("task", worker.subjectId(), delegatedUntil,
                jwt, "delegate-one")).isInstanceOf(IllegalStateException.class);
        watermark.assertCurrent();
        db.update("UPDATE con3_worker_delegation SET status='ACTIVE' WHERE delegation_id=?",
                issued.delegationId());
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
    }
}
