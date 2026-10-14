package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact controlled-test inputs through existing grant services; never a business approval. */
@EnabledIfEnvironmentVariable(named = "ENF_LEGAL_PREP_OUTPUT", matches = ".+")
class EnforcementLegalAcceptanceGrantFixtureTest {
    @Test void prepareExistingProductTargetAndExactFunctionalGrants() throws Exception {
        var source = new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),
                required("ENF_ACCEPTANCE_BUSINESS_USER"), required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authoritySource = new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),
                required("ENF_ACCEPTANCE_AUTHORITY_USER"), required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var c = super.getConnection();
                try { c.setSchema(required("ENF_ACCEPTANCE_AUTHORITY_SCHEMA")); return c; }
                catch (java.sql.SQLException e) { c.close(); throw e; }
            }
        };
        var db = new JdbcTemplate(source);
        initializeMissingTargetTables(source, db);
        var issuer = required("ENF_ACCEPTANCE_ISSUER");
        var subject = required("ENF_ACCEPTANCE_SUBJECT");
        if ("PROCEDURE_INPUT".equals(System.getenv("ENF_LEGAL_PREP_MODE"))) {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            String clientId = "enforcement-procedure-input-20260930";
            Integer clientCount = db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND status='ACTIVE'", Integer.class, clientId);
            if (clientCount == null || clientCount == 0) {
                var clients = new ProductClients(db, new ProductRegisteredClients(
                        new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db), db), tx, Clock.systemUTC());
                clients.register(new ProductClients.Input(clientId, "Enforcement procedure technical input",
                        "https://127.0.0.1:5175/app/enforcement/procedure"),
                        "ENF17-PROCEDURE-INPUT-CLIENT-20260930", "codex-functional-validation");
            }
            var grants = new ProductGrants(db, tx, Clock.systemUTC());
            var watermark = new ProductGrantWatermark(db, new JdbcTemplate(authoritySource), issuer);
            String org = required("ENF_ACCEPTANCE_ORGANIZATION");
            long revision = Long.parseLong(required("ENF_LEGAL_PREP_RULE_VERSION"));
            for (String action : List.of("EDIT_RULE", "READ_RULE", "TRIAL_RULE", "PUBLISH_RULE"))
                issue(db, grants, watermark, issuer, subject, org, "PROCEDURE_RULE",
                        "ENF17-TARGET-PROCEDURE-20260930", revision, action, "RULE_MANAGEMENT");
            for (String caseId : List.of("ENF17-TECH-CASE-01", "ENF17-TECH-MISSING-01")) {
                issue(db, grants, watermark, issuer, subject, org, "CASE", caseId, 1,
                        "READ_CASE", "RULE_MANAGEMENT");
                for (String action : List.of("READ_CASE", "CHECK_CASE"))
                    issue(db, grants, watermark, issuer, subject, org, "CASE", caseId, 1,
                            action, "PROCEDURE_CHECK");
            }
            issue(db, grants, watermark, issuer, subject, org, "PROCEDURE_REPORT",
                    "ENF17-TARGET-REPORT-20260930", 1, "READ_REPORT", "PROCEDURE_CHECK");
            System.out.println("ENF17_PROCEDURE_INPUT_GRANTS_READY; NO_REPORT_WRITTEN");
            return;
        }
        String targetType = System.getenv().getOrDefault("ENF_LEGAL_PREP_TARGET_TYPE", "CONDUCT_REPORT");
        if (!List.of("CONDUCT_REPORT", "PROCEDURE_REPORT").contains(targetType))
            throw new IllegalArgumentException("Unsupported product target type");
        String targetQuery = "PROCEDURE_REPORT".equals(targetType)
                ? "SELECT report_id,revision,organization_id,payload FROM enf6_procedure_report ORDER BY created_at DESC"
                : "SELECT analysis_id,revision,organization_id,payload FROM enf7_conduct_report ORDER BY created_at DESC";
        var targets = db.query(targetQuery, (rs, row) -> new Target(rs.getString(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4)));
        if (targets.isEmpty()) throw new IllegalStateException("Runtime migrations ready; no persisted final-product conduct report input");
        var target = targets.getFirst();
        var json = new ObjectMapper().findAndRegisterModules();
        var report = json.readTree(target.payload());
        String anchor = "PROCEDURE_REPORT".equals(targetType) ? "items[0]" : report.path("elements").isArray() && !report.path("elements").isEmpty()
                ? "elements[0]" : "outcome";
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var grants = new ProductGrants(db, tx, Clock.systemUTC());
        var watermark = new ProductGrantWatermark(db, new JdbcTemplate(authoritySource), issuer);
        for (String action : List.of("SEARCH_LEGAL_BASIS", "READ_LEGAL_BASIS", "READ"))
            issue(db, grants, watermark, issuer, subject, target.organization(),
                    targetType, target.id(), target.version(), action, "LEGAL_REFERENCE");
        for (String purpose : List.of("LEGAL_REFERENCE", "LEGAL_BASIS"))
            for (String action : List.of("SEARCH", "READ_VERBATIM", "SELECT"))
                issue(db, grants, watermark, issuer, subject, target.organization(), "LEGAL_SOURCE",
                        System.getenv().getOrDefault("ENF_LEGAL_PREP_SOURCE_ID", "CNIPA_PATENT_IMPLEMENTING_RULES_2023"),
                        Long.parseLong(System.getenv().getOrDefault("ENF_LEGAL_PREP_SOURCE_REVISION", "1")), action, purpose);
        issue(db, grants, watermark, issuer, subject, target.organization(), "LEGAL_SOURCE",
                "PROJECT-MISSING-LEGAL-SOURCE", 1, "SEARCH", "LEGAL_REFERENCE");
        String reference = System.getenv("ENF_LEGAL_PREP_REFERENCE_ID");
        if (reference != null && !reference.isBlank()) {
            long version = Long.parseLong(required("ENF_LEGAL_PREP_REFERENCE_VERSION"));
            {
                for (String action : List.of("READ", "READ_HISTORY", "UNBIND", "RECHECK"))
                    issue(db, grants, watermark, issuer, subject, target.organization(),
                            "LEGAL_REFERENCE", reference, version, action, "LEGAL_BASIS");
                for (String action : List.of("CONFIRM", "RETURN"))
                    issue(db, grants, watermark, issuer, subject, target.organization(),
                            "LEGAL_REFERENCE", reference, version, action, "HUMAN_LEGAL_REVIEW");
            }
            if ("true".equals(System.getenv("ENF_LEGAL_PREP_REVOKE_READ"))) {
                String grant = db.queryForObject("SELECT current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? "
                        + "AND domain_code='ENFORCEMENT' AND resource_type='LEGAL_REFERENCE' AND resource_id=? AND action_code='READ' AND purpose='LEGAL_BASIS'",
                        String.class, issuer, subject, target.organization(), reference);
                Long grantVersion = db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?", Long.class, grant);
                watermark.change(() -> grants.revoke(grant, grantVersion, "codex-functional-validation",
                        UUID.randomUUID().toString(), "e".repeat(64)));
                System.out.println("ENF17_ACTUAL_READ_GRANT_WITHDRAWN");
            }
        }
        Path output = Path.of(required("ENF_LEGAL_PREP_OUTPUT"));
        Files.createDirectories(output.getParent());
        json.writeValue(output.toFile(), Map.of("targetType", targetType, "targetId", target.id(), "targetRevision", target.version(),
                "organization", target.organization(), "targetAnchor", anchor,
                "inputNature", "FUNCTIONAL_VALIDATION", "preparedAt", Instant.now().toString()));
        if (reference != null && !reference.isBlank()) {
            var versions = db.query("SELECT payload FROM enf9_legal_reference WHERE reference_id=? ORDER BY revision",
                    (rs, row) -> { try { return json.readTree(rs.getString(1)); }
                        catch (Exception failure) { throw new IllegalStateException(failure); } }, reference);
            var receipts = db.queryForList("SELECT request_key,operation,object_revision FROM enf9_legal_receipt WHERE object_id=? ORDER BY created_at", reference);
            var events = db.queryForList("SELECT request_key,operation,object_revision FROM enf9_legal_event WHERE object_id=? ORDER BY created_at", reference);
            json.writeValue(output.resolveSibling(output.getFileName() + ".db.json").toFile(),
                    Map.of("referenceId", reference, "versions", versions, "receipts", receipts, "events", events,
                            "target", json.readTree(target.payload()), "inputNature", "FUNCTIONAL_VALIDATION"));
        }
    }
    private void issue(JdbcTemplate db, ProductGrants grants, ProductGrantWatermark watermark,
            String issuer, String subject, String org, String type, String id, long version,
            String action, String purpose) {
        var scopes = db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch "
                + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' "
                + "AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",
                (rs, row) -> new Epoch(rs.getLong(1), rs.getString(2)), issuer, subject, org, type, id, action, purpose);
        long epoch = scopes.isEmpty() ? 0 : scopes.getFirst().version();
        if (!scopes.isEmpty() && scopes.getFirst().grant() != null) {
            String old = scopes.getFirst().grant();
            var usable = db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? "
                    + "AND resource_version=? AND status='ACTIVE' AND expires_at>?", Integer.class,
                    old, version, java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
            if (usable != null && usable == 1) return;
            Long oldVersion = db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?", Long.class, old);
            epoch = watermark.change(() -> grants.revoke(old, oldVersion, "codex-functional-validation",
                    UUID.randomUUID().toString(), "e".repeat(64))).epoch();
        }
        long current = epoch;
        watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, subject, org, "ENFORCEMENT",
                "ENF17-FUNCTIONAL", "FUNCTIONAL_VALIDATION", type, id, version, action, purpose),
                Instant.now().plusSeconds(7200), current, "codex-functional-validation",
                UUID.randomUUID().toString(), "f".repeat(64)));
    }
    private void initializeMissingTargetTables(DriverManagerDataSource source, JdbcTemplate db) throws Exception {
        Path workspace = Path.of("").toAbsolutePath();
        while (workspace != null && !Files.isDirectory(workspace.resolve("db/dm8"))) workspace = workspace.getParent();
        if (workspace == null) throw new IllegalStateException("Migration workspace unavailable");
        String[][] migrations = {
            {"ENF4_EVIDENCE_RULE", "V050__enforcement_v2_evidence_rules.sql"},
            {"ENF4_EVIDENCE_PUBLICATION", "V051__enforcement_v2_evidence_publication.sql"},
            {"ENF4_ORGANIZATION", "V052__enforcement_v2_evidence_organizations.sql"},
            {"ENF6_PROCEDURE_REPORT", "V059__enforcement_v2_procedure.sql"},
            {"ENF7_CONDUCT_REPORT", "V060__enforcement_v2_conduct.sql"},
            {"ENF9_LEGAL_REFERENCE", "V075__enforcement_v2_legal_reference.sql"}
        };
        for (String[] migration : migrations) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?", Integer.class, migration[0]);
            if (count != null && count == 0) {
                try (var c = source.getConnection()) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
                            new org.springframework.core.io.FileSystemResource(workspace.resolve("db/dm8/" + migration[1])));
                }
                System.out.println("ENF17_RUNTIME_MIGRATION_APPLIED " + migration[1]);
            }
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
    private record Target(String id, long version, String organization, String payload) { }
    private record Epoch(long version, String grant) { }
}
