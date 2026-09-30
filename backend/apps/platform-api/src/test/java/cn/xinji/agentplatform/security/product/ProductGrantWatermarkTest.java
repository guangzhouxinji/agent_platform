package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

class ProductGrantWatermarkTest {
    private static final String ISSUER = "https://identity.example.org";
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test void disabledMemberCannotBeReactivatedByRestoringAnOlderPrimary() throws Exception {
        JdbcDataSource primarySource = source("disabled-member-primary");
        JdbcDataSource authoritySource = source("disabled-member-authority");
        try (var connection = primarySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate primary = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,?)", ISSUER, "member",
                "named-member", "encoded", "ACTIVE", 1, Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")));
        ProductGrantWatermark watermark = new ProductGrantWatermark(primary, authority, ISSUER);
        ProductSubjects subjects = new ProductSubjects(primary,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)),
                new BCryptPasswordEncoder(), Clock.fixed(Instant.parse("2026-09-27T01:00:00Z"), ZoneOffset.UTC),
                ISSUER, Duration.ofMinutes(30), 16);
        watermark.change(() -> {
            subjects.disable("member", "maintainer");
            return null;
        });
        watermark.assertCurrent();

        primary.update("DELETE FROM id2_subject_event WHERE issuer=? AND operation='DISABLE'", ISSUER);
        primary.update("UPDATE id2_subject SET status='ACTIVE',credential_version=1,disabled_at=NULL "
                + "WHERE issuer=? AND subject_id='member'", ISSUER);
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductGrantWatermark(primary, authority, ISSUER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void independentAuthorityRejectsRestoredGrantAndUnresolvedIntentAcrossInstances() throws Exception {
        JdbcDataSource primarySource = source("primary-watermark");
        JdbcDataSource authoritySource = source("independent-watermark");
        try (var connection = primarySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
        }
        try (var connection = authoritySource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/identity-authority/V001__grant_watermark.sql")));
        }
        JdbcTemplate primary = new JdbcTemplate(primarySource);
        JdbcTemplate authority = new JdbcTemplate(authoritySource);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER, EMPTY);
        primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,?)", ISSUER, "member", "named-member",
                "encoded", "ACTIVE", 1, Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")));
        ProductGrantWatermark first = new ProductGrantWatermark(primary, authority, ISSUER);
        ProductGrantWatermark second = new ProductGrantWatermark(primary, authority, ISSUER);
        ProductGrants grants = new ProductGrants(primary,
                new TransactionTemplate(new DataSourceTransactionManager(primarySource)),
                Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC));
        ProductGrants.Scope scope = new ProductGrants.Scope(ISSUER, "member", "organization", "CONSULTATION",
                "task-1", "dataset/record/version", "TASK", "task-1", 1, "READ", "TASK_WORK");
        var issued = first.change(() -> grants.issue(scope, Instant.parse("2026-09-28T00:00:00Z"),
                0, "maintainer", "issue-1", "a".repeat(64)));
        second.assertCurrent();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch pending = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var inFlight = executor.submit(() -> first.change(() -> {
                pending.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return "completed";
            }));
            assertThat(pending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(second::assertCurrent).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> second.change(() -> "concurrent"))
                    .isInstanceOf(IllegalStateException.class);
            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEqualTo("completed");
        }
        second.assertCurrent();
        assertThatThrownBy(() -> second.change(() -> { throw new IllegalArgumentException("Rejected"); }))
                .isInstanceOf(IllegalArgumentException.class);
        second.assertCurrent();
        var revoked = second.change(() -> grants.revoke(issued.grantId(), 1, "maintainer", "revoke-1",
                "b".repeat(64)));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        first.assertCurrent();

        // A restored primary projection cannot resurrect the revoked grant.
        primary.update("UPDATE id2_grant SET status='ACTIVE',grant_version=1 WHERE grant_id=?", issued.grantId());
        assertThatThrownBy(first::assertCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductGrantWatermark(primary, authority, ISSUER))
                .isInstanceOf(IllegalStateException.class);
        primary.update("UPDATE id2_grant SET status='REVOKED',grant_version=2 WHERE grant_id=?", issued.grantId());
        first.assertCurrent();

        authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unresolved' WHERE issuer=?",
                ISSUER);
        assertThatThrownBy(second::assertCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductGrantWatermark(primary, authority, ISSUER))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JdbcDataSource source(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        return source;
    }
}
