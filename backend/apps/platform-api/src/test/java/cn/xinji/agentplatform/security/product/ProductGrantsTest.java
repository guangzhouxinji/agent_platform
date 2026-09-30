package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductGrantsTest {
    @Test void issueRevokeAndNewGenerationPreserveHistoryAndRejectStaleWrites() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product-grants;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    Path.of("db/dm8/V032__product_identity.sql")));
        }
        JdbcTemplate db = new JdbcTemplate(source);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at,disabled_at) VALUES (?,?,?,?,?,?,?,?)", "https://issuer.example.org",
                "member-1", "registered-member", "encoded-credential", "ACTIVE", 1,
                java.sql.Timestamp.from(Instant.parse("2026-09-27T00:00:00Z")), null);
        ProductGrants grants = new ProductGrants(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"), ZoneOffset.UTC));
        ProductGrants.Scope scope = new ProductGrants.Scope("https://issuer.example.org", "member-1",
                "organization-1", "CONSULTATION", "task-1", "dataset-1/record-1/version-1", "TASK", "task-1", 1,
                "READ", "TASK_WORK");
        String hash = "a".repeat(64);
        Instant expiry = Instant.parse("2026-09-28T00:00:00Z");

        var issued = grants.issue(scope, expiry, 0, "security-maintainer", "request-1", hash);
        assertThat(issued).isEqualTo(grants.issue(scope, expiry, 0, "security-maintainer", "request-1", hash));
        assertThatThrownBy(() -> grants.issue(scope, expiry, 0, "security-maintainer", "request-2", hash))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grants.revoke(issued.grantId(), 2, "security-maintainer", "request-3", hash))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(1);

        var revoked = grants.revoke(issued.grantId(), 1, "security-maintainer", "request-3", hash);
        assertThat(revoked.epoch()).isEqualTo(2);
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked).isEqualTo(grants.revoke(issued.grantId(), 1,
                "security-maintainer", "request-3", hash));
        assertThatThrownBy(() -> grants.issue(scope, expiry, 0,
                "security-maintainer", "request-1", hash)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grants.revoke(issued.grantId(), 1,
                "security-maintainer", "request-4", hash)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> grants.issue(scope, expiry, 2, "security-maintainer", "request-3", hash))
                .isInstanceOf(IllegalStateException.class);

        var renewed = grants.issue(scope, expiry, 2, "security-maintainer", "request-5", hash);
        assertThat(renewed.grantId()).isNotEqualTo(issued.grantId());
        assertThat(renewed.epoch()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT status FROM id2_grant WHERE grant_id=?", String.class,
                issued.grantId())).isEqualTo("REVOKED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event", Integer.class)).isEqualTo(3);
        assertThatThrownBy(() -> grants.issue(scope, expiry, 3, "security-maintainer", "request-5",
                "b".repeat(64))).isInstanceOf(IllegalStateException.class);
        var anotherDomain = new ProductGrants.Scope(scope.issuer(), scope.subject(), scope.organization(),
                "PATENT", scope.taskScope(), scope.dataScope(), scope.resourceType(), scope.resourceId(),
                scope.resourceVersion(), scope.action(), scope.purpose());
        var independent = grants.issue(anotherDomain, expiry, 0, "security-maintainer", "request-6", hash);
        assertThat(independent.epoch()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT authorization_epoch FROM id2_current_epoch WHERE domain_code=?",
                Long.class, "CONSULTATION")).isEqualTo(3);
        assertThatThrownBy(() -> grants.issue(new ProductGrants.Scope(scope.issuer(), scope.subject(),
                scope.organization(), scope.domain(), scope.taskScope(), "", scope.resourceType(),
                scope.resourceId(), scope.resourceVersion(), scope.action(), scope.purpose()), expiry,
                3, "security-maintainer", "request-7", hash)).isInstanceOf(IllegalArgumentException.class);
        var sourceScope = new ProductGrants.Scope(scope.issuer(), scope.subject(), scope.organization(),
                scope.domain(), scope.taskScope(), "source-scope:1", "SOURCE", "source-1", 1,
                "QUERY", "SOURCE_QUERY");
        assertThatThrownBy(() -> grants.issue(sourceScope, expiry, 0,
                "security-maintainer", "request-source", hash)).isInstanceOf(IllegalArgumentException.class);
        var scopedSource = new ProductGrants.Scope(scope.issuer(), scope.subject(), scope.organization(),
                scope.domain(), "task-1", "scope-1:1", "SOURCE_SCOPE", "scope-1", 1,
                "QUERY", "SOURCE_QUERY");
        assertThatThrownBy(() -> grants.issue(scopedSource, expiry, 0,
                "security-maintainer", "request-source-scope", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE resource_type='SOURCE'",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE resource_type='SOURCE_SCOPE'",
                Integer.class)).isZero();
    }
}
