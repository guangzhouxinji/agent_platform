package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Offline, read-only verification of an isolated Task restoration. */
public final class ProductTaskRecoveryCheck {
    record Connections(String primaryUrl, String primaryUser, String primaryPassword, String primarySchema,
                       String authorityUrl, String authorityUser, String authorityPassword,
                       String authoritySchema, String issuer, Path evidenceRoot, long evidenceMaxBytes,
                       String topology) {
        Connections(String primaryUrl, String primaryUser, String primaryPassword, String primarySchema,
                    String authorityUrl, String authorityUser, String authorityPassword,
                    String authoritySchema, String issuer, Path evidenceRoot, long evidenceMaxBytes) {
            this(primaryUrl, primaryUser, primaryPassword, primarySchema, authorityUrl, authorityUser,
                    authorityPassword, authoritySchema, issuer, evidenceRoot, evidenceMaxBytes, "independent");
        }
    }

    private ProductTaskRecoveryCheck() { }

    public static void main(String[] args) {
        if (args.length == 1 && "--source-waiting".equals(args[0])) {
            sourceWaiting();
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Recovery check takes no arguments");
        try { verify(new Connections(required("PRODUCT_TASK_RECOVERY_DB_URL"),
                required("PRODUCT_TASK_RECOVERY_DB_USER"), required("PRODUCT_TASK_RECOVERY_DB_PASSWORD"),
                required("PRODUCT_TASK_RECOVERY_DB_SCHEMA"),
                optional("PRODUCT_TASK_RECOVERY_AUTHORITY_DB_URL"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_USER"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_PASSWORD"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_SCHEMA"), required("PRODUCT_TASK_RECOVERY_ISSUER"),
                Path.of(required("PRODUCT_TASK_RECOVERY_EVIDENCE_ROOT")),
                positiveLong("PRODUCT_TASK_RECOVERY_EVIDENCE_MAX_BYTES"),
                optional("PRODUCT_TASK_RECOVERY_AUTHORITY_TOPOLOGY")));
        } catch (ProductGrantRecoveryEvidence.Refused refused) {
            try {
                System.out.println(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(refused.evidence()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException unavailable) {
                throw new IllegalStateException("Grant recovery diagnostic cannot be encoded");
            }
            throw refused; // A diagnostic is never a successful recovery exit.
        }
    }

    /** Source semantics only. The native runner must bind this result to its live source and fence. */
    private static void sourceWaiting() {
        String prefix = "PRODUCT_HUMAN_SOURCE_";
        var connections = new Connections(required(prefix + "DB_URL"), required(prefix + "DB_USER"),
                required(prefix + "DB_PASSWORD"), required(prefix + "DB_SCHEMA"),
                optional(prefix + "AUTHORITY_DB_URL"), required(prefix + "AUTHORITY_USER"),
                required(prefix + "AUTHORITY_PASSWORD"), required(prefix + "AUTHORITY_SCHEMA"),
                required(prefix + "ISSUER"), Path.of(required(prefix + "EVIDENCE_ROOT")),
                positiveLong(prefix + "EVIDENCE_MAX_BYTES"), optional(prefix + "AUTHORITY_TOPOLOGY"));
        var human = new cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention.Policy(
                required(prefix + "HUMAN_POLICY_REF"), positiveLong(prefix + "HUMAN_POLICY_VERSION"),
                required(prefix + "HUMAN_POLICY_SHA256"));
        var task = sourcePolicy(prefix + "TASK_POLICY_");
        var facts = sourcePolicy(prefix + "FACTS_POLICY_");
        var until = java.time.Instant.parse(required(prefix + "BACKUP_UNTIL"));
        var clock=java.time.Clock.systemUTC();
        var result = verifyHumanSource(connections, human, task, facts, until, clock);
        try {
            String output=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(java.util.Map.of("mode", "SOURCE_ONLY_WAITING",
                            "businessActivation", false, "sourceAndFenceBindingProven", false, "summary", result));
            requireCurrentSourceSummary(result,clock);
            System.out.println(output);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Source integrity summary cannot be encoded", failure);
        }
    }

    private static ProductRetentionPolicy sourcePolicy(String prefix) {
        return new ProductRetentionPolicy(required(prefix + "REF"), positiveLong(prefix + "VERSION"),
                required(prefix + "SHA256"), java.time.Duration.parse(required(prefix + "MAX_AGE")));
    }

    static ProductHumanWaitingRecoveryVerifier.Summary verifyHumanSource(Connections connections,
            cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanRetention.Policy humanPolicy,
            ProductRetentionPolicy taskPolicy, ProductRetentionPolicy factsPolicy,
            java.time.Instant backupUntil, java.time.Clock clock) {
        var authoritySettings = authoritySettings(connections);
        JdbcTemplate primary = new JdbcTemplate(source(connections.primaryUrl(), connections.primaryUser(),
                connections.primaryPassword(), schema(connections.primarySchema())));
        JdbcTemplate authority = new JdbcTemplate(source(authoritySettings.jdbcUrl(), authoritySettings.username(),
                authoritySettings.password(), authoritySettings.schema()));
        var files = new ProductSourceEvidenceFiles(connections.evidenceRoot(), connections.evidenceMaxBytes());
        files.verified(humanPolicy.reference(), humanPolicy.evidenceSha256());
        files.verified(taskPolicy.reference(), taskPolicy.evidenceSha256());
        files.verified(factsPolicy.reference(), factsPolicy.evidenceSha256());
        var admission = new ProductGrantRecoveryEvidence(primary, authority, connections.issuer());
        admission.requireReady();
        var grants = new ProductGrantWatermark(primary, authority, connections.issuer());
        long generation = grants.currentGeneration();
        var sources = new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primary.getDataSource())));
        sources.assertAllCurrent();
        var verifier = new ProductHumanWaitingRecoveryVerifier(primary, authority,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), clock,
                humanPolicy, taskPolicy, factsPolicy, files);
        var result = verifier.verify(backupUntil);
        new ProductTaskRecoveryVerifier(primary, grants).verify();
        sources.assertAllCurrent();
        grants.assertUnchanged(generation);
        admission.requireReady();
        var checked = verifier.verify(backupUntil);
        if (!result.equals(checked)) throw new IllegalStateException("Source waiting integrity changed");
        files.verified(humanPolicy.reference(), humanPolicy.evidenceSha256());
        files.verified(taskPolicy.reference(), taskPolicy.evidenceSha256());
        files.verified(factsPolicy.reference(), factsPolicy.evidenceSha256());
        sources.assertAllCurrent();
        grants.assertUnchanged(generation);
        requireCurrentSourceSummary(checked,clock);
        return checked;
    }

    // A short current-use scope limits this inspection, not the separately approved save window.
    static void requireCurrentSourceSummary(ProductHumanWaitingRecoveryVerifier.Summary summary,java.time.Clock clock) {
        var now=clock.instant();
        if (!now.isBefore(summary.validUntil()) || !now.isBefore(summary.executionUntil()))
            throw new IllegalStateException("Source inspection deadline expired");
    }

    static void verify(Connections connections) {
        var authoritySettings = authoritySettings(connections);
        JdbcTemplate primary = new JdbcTemplate(source(connections.primaryUrl(),
                connections.primaryUser(), connections.primaryPassword(), schema(connections.primarySchema())));
        JdbcTemplate authority = new JdbcTemplate(source(authoritySettings.jdbcUrl(),
                authoritySettings.username(), authoritySettings.password(), authoritySettings.schema()));
        var grantAdmission = new ProductGrantRecoveryEvidence(primary, authority, connections.issuer());
        grantAdmission.requireReady();
        var watermark = new ProductGrantWatermark(primary, authority, connections.issuer());
        new ProductTaskRecoveryVerifier(primary, watermark).verify();
        new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primary.getDataSource())))
                .assertAllCurrent();
        ProductSourceCaptureIdentity.verify(primary,new com.fasterxml.jackson.databind.ObjectMapper());
        new ProductSourceRecoveryFiles(primary, connections.evidenceRoot(),
                connections.evidenceMaxBytes()).verify();
        new ProductPatentSearchRecoveryVerifier(primary, connections.evidenceRoot(),
                connections.evidenceMaxBytes()).verify();
        new ProductModelInvocationRecoveryVerifier(primary, connections.evidenceRoot(),
                connections.evidenceMaxBytes()).verify();
        new ProductModelTurnRecoveryVerifier(primary, connections.evidenceRoot(),
                connections.evidenceMaxBytes()).verify();
        new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primary.getDataSource())))
                .assertAllCurrent();
        watermark.assertCurrent();
        grantAdmission.requireReady();
    }

    // The same topology contract is used by application startup and the offline read-only check.
    static AuthorityDataSourceSettings authoritySettings(Connections connections) {
        return AuthorityDataSourceSettings.resolve(connections.topology(), connections.primaryUrl(),
                connections.primarySchema(), connections.primaryUser(), connections.authorityUrl(),
                connections.authoritySchema(), connections.authorityUser(), connections.authorityPassword());
    }

    private static String optional(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }
    private static DriverManagerDataSource source(String url, String user, String password, String schema) {
        return new DriverManagerDataSource(url, user, password) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try {
                    connection.setSchema(schema);
                    connection.setReadOnly(true);
                    return connection;
                } catch (SQLException failure) {
                    connection.close();
                    throw failure;
                }
            }
        };
    }

    private static String schema(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{2,63}"))
            throw new IllegalArgumentException("Invalid recovery schema");
        return value.toUpperCase(Locale.ROOT);
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static long positiveLong(String key) {
        try {
            long value = Long.parseLong(required(key));
            if (value > 0) return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid " + key, invalid);
        }
        throw new IllegalArgumentException("Invalid " + key);
    }
}
