package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductWorkloadSubjectsTest {
    @Test void restoredOldSchemaCannotPassCurrentTaskRecoveryGate() throws Exception {
        String issuer = "https://old-schema.example.org";
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:old-worker-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var independent = new JdbcDataSource();
        independent.setURL("jdbc:h2:mem:old-worker-authority-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var authority = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql",
                    "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql",
                    "V046__product_workload_subject.sql",
                    "V047__product_task_worker_delegation.sql"})
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(authority, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        var db = new JdbcTemplate(source);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        assertThatThrownBy(() -> new ProductTaskRecoveryVerifier(db, watermark).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CON3_EXECUTION_RECONCILIATION");
    }

    @Test void certificateSubjectIsSeparateFromHumanLoginAndRevocationIsCurrent() throws Exception {
        String issuer = "https://workload-issuer.example.org";
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:workload-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var independent = new JdbcDataSource();
        independent.setURL("jdbc:h2:mem:workload-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var authorityConnection = independent.getConnection()) {
            for (String name : new String[] {"V032__product_identity.sql",
                    "V034__product_consultation_initial_task.sql",
                    "V041__product_task_authorization_version.sql",
                    "V042__product_consultation_goal.sql",
                    "V046__product_workload_subject.sql"})
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8", name)));
            ScriptUtils.executeSqlScript(authorityConnection,
                    new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        var db = new JdbcTemplate(source);
        var authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var service = new ProductWorkloadSubjects(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), watermark,
                Clock.systemUTC(), issuer);
        String fingerprint = "a".repeat(64);
        Instant expiry = Instant.now().plusSeconds(3600);
        Instant grantExpiry = expiry.minusSeconds(300);
        var registered = service.register(fingerprint, "organization", ProductWorkloadSubjects.Kind.AGENT,
                expiry, "register-worker", "maintainer#certificate");
        assertThat(service.register(fingerprint, "organization", ProductWorkloadSubjects.Kind.AGENT,
                expiry, "register-worker", "maintainer#certificate")).isEqualTo(registered);
        assertThat(db.queryForObject("SELECT password_hash FROM id2_subject WHERE subject_id=?",
                String.class, registered.subjectId())).isNull();
        assertThat(db.queryForObject("SELECT subject_kind FROM id2_subject WHERE subject_id=?",
                String.class, registered.subjectId())).isEqualTo("SERVICE");
        var now = java.sql.Timestamp.from(Instant.now());
        var taskExpiry = java.sql.Timestamp.from(expiry);
        db.update("INSERT INTO con3_organization VALUES('organization','Organization','ACTIVE',1,?)", now);
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,"
                        + "version,expires_at,retention_ref,retention_version,retention_evidence_sha,"
                        + "created_at) VALUES('task-1','organization',?,?,'IN_PROGRESS',1,?,'policy',1,?,?)",
                issuer, registered.subjectId(), taskExpiry, "c".repeat(64), now);
        db.update("INSERT INTO con3_data_scope VALUES('scope-1','task-1','organization',1,'TASK_METADATA')");
        var grants = new ProductGrants(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
        var granted = watermark.change(() -> grants.issueWorkload(issuer, registered.subjectId(),
                "organization", "task-1", grantExpiry, 0, "maintainer#certificate", "worker-task-grant",
                "d".repeat(64)));
        assertThat(granted.status()).isEqualTo("ACTIVE");
        assertThat(db.queryForObject("SELECT data_scope FROM id2_grant WHERE grant_id=?",
                String.class, granted.grantId())).isEqualTo("scope-1:1");
        assertThatThrownBy(() -> watermark.change(() -> grants.issueWorkload(issuer,
                registered.subjectId(), "organization", "task-2", grantExpiry, 0,
                "maintainer#certificate", "cross-task-grant", "e".repeat(64))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> watermark.change(() -> grants.issue(new ProductGrants.Scope(
                issuer, registered.subjectId(), "organization", "CONSULTATION", "task-1",
                "scope-1:1", "TASK", "task-1", 1, "READ", "TASK_WORK"), expiry, 0,
                "maintainer#certificate", "generic-service-grant", "f".repeat(64))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.register(fingerprint, "other", ProductWorkloadSubjects.Kind.AGENT,
                expiry, "register-worker", "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class);
        watermark.assertCurrent();
        assertThat(service.revoke(registered.subjectId(), "organization", 1, "revoke-worker",
                "maintainer#certificate")).isEqualTo(2);
        assertThatThrownBy(() -> watermark.change(() -> grants.issueWorkload(issuer,
                registered.subjectId(), "organization", "task-1", grantExpiry, 2,
                "maintainer#certificate", "revoked-worker-grant", "e".repeat(64))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.revoke(registered.subjectId(), "organization", 1, "revoke-worker",
                "maintainer#certificate")).isEqualTo(2);
        assertThatThrownBy(() -> service.register(fingerprint, "organization",
                ProductWorkloadSubjects.Kind.AGENT, expiry, "register-worker",
                "maintainer#certificate")).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_workload_request SET request_sha=? WHERE request_id=?",
                "0".repeat(64), "register-worker");
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_workload_request SET request_sha=? WHERE request_id=?",
                requestDigest(fingerprint, "organization", expiry), "register-worker");
        watermark.assertCurrent();
        db.update("UPDATE id2_subject SET status='ACTIVE',credential_version=1,disabled_at=NULL "
                + "WHERE subject_id=?", registered.subjectId());
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE id2_subject SET status='DISABLED',credential_version=2 "
                + "WHERE subject_id=?", registered.subjectId());
        watermark.assertCurrent();
        db.update("UPDATE id2_workload SET status='ACTIVE' WHERE subject_id=?", registered.subjectId());
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
    }

    private static String requestDigest(String fingerprint, String organization, Instant expiry) throws Exception {
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        for (String field : new String[] {fingerprint, organization, "AGENT", expiry.toString()})
            hash.update((field.length() + ":" + field).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash.digest());
    }
}
