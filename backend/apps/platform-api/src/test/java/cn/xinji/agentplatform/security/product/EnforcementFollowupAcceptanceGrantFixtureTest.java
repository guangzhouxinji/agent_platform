package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

/** Provisions explicit FUNCTIONAL_VALIDATION grants; it is input setup, not product acceptance evidence. */
@EnabledIfEnvironmentVariable(named = "ENF_ACCEPTANCE_BUSINESS_URL", matches = ".+")
class EnforcementFollowupAcceptanceGrantFixtureTest {
    @Test void issuesExactDocumentTemplateFunctionalValidationGrants() {
        String businessUrl = required("ENF_ACCEPTANCE_BUSINESS_URL");
        var businessSource = new DriverManagerDataSource(businessUrl,
                required("ENF_ACCEPTANCE_BUSINESS_USER"), required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        String authoritySchema = required("ENF_ACCEPTANCE_AUTHORITY_SCHEMA");
        var authoritySource = new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),
                required("ENF_ACCEPTANCE_AUTHORITY_USER"), required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection();
                try { connection.setSchema(authoritySchema); return connection; }
                catch (java.sql.SQLException failure) { connection.close(); throw failure; }
            }
        };
        var business = new JdbcTemplate(businessSource);
        var watermark = new ProductGrantWatermark(business, new JdbcTemplate(authoritySource),
                required("ENF_ACCEPTANCE_ISSUER"));
        var grants = new ProductGrants(business,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(businessSource));
        var clients = new ProductClients(business,
                new ProductRegisteredClients(new JdbcRegisteredClientRepository(business), business),
                transaction, Clock.systemUTC());
        ensureClient(business, clients, "enforcement-document-template-20260929",
                "https://127.0.0.1:5174/app/enforcement/document-templates");
        ensureClient(business, clients, "enforcement-legal-reference-20260929",
                "https://127.0.0.1:5174/app/enforcement/legal-references");
        String subject = required("ENF_ACCEPTANCE_SUBJECT");
        String organization = required("ENF_ACCEPTANCE_ORGANIZATION");
        String template = required("ENF_ACCEPTANCE_TEMPLATE_ID");
        if ("true".equals(cn.xinji.agentplatform.enforcement.testing.FixtureEnvironment.optional("ENF_ACCEPTANCE_REVOKE_TEMPLATE_DOWNLOAD", "AGENT_GRANT_", System.getenv()))) {
            var selected = business.queryForMap("SELECT g.grant_id,g.grant_version FROM id2_grant g "
                    + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.issuer=? "
                    + "AND g.subject_id=? AND g.organization=? AND g.resource_type='DOCUMENT_TEMPLATE' "
                    + "AND g.resource_id=? AND g.action_code='DOWNLOAD' AND g.purpose='DOCUMENT_TEMPLATE'",
                    required("ENF_ACCEPTANCE_ISSUER"), subject, organization, template);
            String grantId = String.valueOf(selected.get("GRANT_ID"));
            long version = ((Number) selected.get("GRANT_VERSION")).longValue();
            var receipt = watermark.change(() -> grants.revoke(grantId, version,
                    "codex-functional-validation", UUID.randomUUID().toString(), "d".repeat(64)));
            assertThat(receipt.status()).isEqualTo("REVOKED");
            return;
        }
        List<Grant> expected = List.of(
                new Grant("*", 1, "READ"), new Grant(template, 1, "CREATE"),
                new Grant(template, 1, "READ"), new Grant(template, 1, "PREVIEW"),
                new Grant(template, 1, "PUBLISH"), new Grant(template, 1, "DISABLE"),
                new Grant(template, 1, "DOWNLOAD"));
        for (Grant grant : expected) ensure(business, watermark, grants, subject, organization, grant);
        Integer count = business.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch c "
                + "ON c.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? "
                + "AND g.domain_code='ENFORCEMENT' AND g.resource_type='DOCUMENT_TEMPLATE' "
                + "AND g.purpose='DOCUMENT_TEMPLATE' AND g.status='ACTIVE' AND g.resource_id IN (?,?)",
                Integer.class, required("ENF_ACCEPTANCE_ISSUER"), subject, organization, "*", template);
        assertThat(count).isEqualTo(expected.size());
    }

    private void ensureClient(JdbcTemplate db, ProductClients clients, String id, String redirect) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? "
                + "AND redirect_uri=? AND status='ACTIVE' AND version=1", Integer.class, id, redirect);
        if (count != null && count == 1) return;
        clients.register(new ProductClients.Input(id, "Enforcement V2 functional validation", redirect),
                UUID.randomUUID().toString(), "codex-functional-validation");
    }

    private void ensure(JdbcTemplate db, ProductGrantWatermark watermark, ProductGrants grants,
                        String subject, String organization, Grant grant) {
        String issuer = required("ENF_ACCEPTANCE_ISSUER");
        List<Epoch> rows = db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch "
                        + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' "
                        + "AND resource_type='DOCUMENT_TEMPLATE' AND resource_id=? AND action_code=? "
                        + "AND purpose='DOCUMENT_TEMPLATE'",
                (rs, row) -> new Epoch(rs.getLong(1), rs.getString(2)), issuer, subject, organization,
                grant.resourceId(), grant.action());
        if (!rows.isEmpty() && rows.getFirst().currentGrantId() != null) {
            Integer usable = db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? "
                    + "AND status='ACTIVE' AND expires_at>?", Integer.class, rows.getFirst().currentGrantId(),
                    java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
            if (usable != null && usable == 1) return;
        }
        long epoch = rows.isEmpty() ? 0 : rows.getFirst().epoch();
        if (!rows.isEmpty() && rows.getFirst().currentGrantId() != null) {
            String expiredGrant = rows.getFirst().currentGrantId();
            Long version = db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",
                    Long.class, expiredGrant);
            var revoked = watermark.change(() -> grants.revoke(expiredGrant, version,
                    "codex-functional-validation", UUID.randomUUID().toString(), "e".repeat(64)));
            epoch = revoked.epoch();
        }
        long issueEpoch = epoch;
        watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, subject, organization,
                "ENFORCEMENT", "ENF-V2-FOLLOWUP", "FUNCTIONAL_VALIDATION", "DOCUMENT_TEMPLATE",
                grant.resourceId(), grant.version(), grant.action(), "DOCUMENT_TEMPLATE"),
                Instant.now().plusSeconds(7200), issueEpoch, "codex-functional-validation",
                UUID.randomUUID().toString(), "f".repeat(64)));
    }

    private static String required(String name) {
        String value = cn.xinji.agentplatform.enforcement.testing.FixtureEnvironment.optional(name, "AGENT_GRANT_", System.getenv());
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private record Grant(String resourceId, long version, String action) { }
    private record Epoch(long epoch, String currentGrantId) { }
}
