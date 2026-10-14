package cn.xinji.agentplatform.security.product;

import java.util.UUID;
import java.util.ArrayList;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class ProductWatermarkJdbcTest {
    @Test void independentAuthorityRejectsTamperingBeyondFirstFetchBatch() throws Exception {
        var primarySource = new JdbcDataSource();
        primarySource.setURL("jdbc:h2:mem:fetch-primary-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var authoritySource = new JdbcDataSource();
        authoritySource.setURL("jdbc:h2:mem:fetch-authority-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = primarySource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("db/dm8/V032__product_identity.sql"));
        }
        try (var connection = authoritySource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("db/identity-authority/V001__grant_watermark.sql"));
        }
        String issuer = "https://fetch-component.example";
        var primary = new JdbcTemplate(primarySource);
        var authority = new JdbcTemplate(authoritySource);
        var now = java.time.Instant.parse("2026-10-01T00:00:00Z");
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)", issuer,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,?)", issuer, "member",
                "component-member", "encoded", java.sql.Timestamp.from(now));
        var watermark = new ProductGrantWatermark(primary, authority, issuer);
        var grants = new ProductGrants(primary, new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(primarySource)),
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        watermark.change(() -> {
            for (int i = 0; i < 300; i++) {
                var scope = new ProductGrants.Scope(issuer, "member", "organization", "CONSULTATION",
                        "task-" + i, "scope-" + i, "TASK", "task-" + i, 1, "READ", "TASK_WORK");
                grants.issue(scope, now.plusSeconds(3600), 0, "component-maintainer", "grant-" + i,
                        "a".repeat(64));
            }
            return null;
        });
        assertThat(watermark.currentGeneration()).isEqualTo(1);
        String last = primary.queryForObject("SELECT MAX(grant_id) FROM id2_grant", String.class);
        primary.update("UPDATE id2_grant SET status='REVOKED' WHERE grant_id=?", last);
        assertThatThrownBy(watermark::assertCurrent).isInstanceOf(IllegalStateException.class);
    }

    @Test void privateFetchHintDoesNotCapRowsChangeSharedTemplateOrHideLaterChanges() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fetch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var original = new JdbcTemplate(ds);
        original.setQueryTimeout(7);
        original.setIgnoreWarnings(false);
        original.setResultsMapCaseInsensitive(true);
        original.execute("CREATE TABLE history(id INT PRIMARY KEY, value_text VARCHAR(40))");
        original.update("INSERT INTO history SELECT X, 'original' FROM SYSTEM_RANGE(1,4200)");
        var buffered = ProductWatermarkJdbc.buffered(original);
        assertThat(buffered.getDataSource()).isSameAs(ds);
        assertThat(buffered.getFetchSize()).isEqualTo(256);
        assertThat(original.getFetchSize()).isEqualTo(-1);
        assertThat(buffered.getMaxRows()).isEqualTo(original.getMaxRows());
        assertThat(buffered.getQueryTimeout()).isEqualTo(7);
        assertThat(buffered.isIgnoreWarnings()).isFalse();
        assertThat(buffered.isResultsMapCaseInsensitive()).isTrue();
        var all = new ArrayList<String>();
        buffered.query("SELECT value_text FROM history ORDER BY id", rs -> { all.add(rs.getString(1)); });
        assertThat(all).hasSize(4200).containsOnly("original");
        original.update("UPDATE history SET value_text='changed' WHERE id=599");
        all.clear();
        buffered.query("SELECT value_text FROM history ORDER BY id", rs -> { all.add(rs.getString(1)); });
        assertThat(all).hasSize(4200);
        assertThat(all.get(598)).isEqualTo("changed");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        transaction.executeWithoutResult(status -> {
            buffered.update("UPDATE history SET value_text='rolled-back' WHERE id=599");
            assertThat(original.queryForObject("SELECT value_text FROM history WHERE id=599", String.class))
                    .isEqualTo("rolled-back");
            status.setRollbackOnly();
        });
        assertThat(original.queryForObject("SELECT value_text FROM history WHERE id=599", String.class))
                .isEqualTo("changed");
    }
}
