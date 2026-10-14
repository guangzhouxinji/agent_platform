package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline shared-instance installation. No fallback, accounts, schema or data are created here. */
public final class ProductIdentityBootstrapSharedInstance {
    private ProductIdentityBootstrapSharedInstance() { }

    /** Both owner URLs are mandatory and exact; all five pre-created identities are distinct. */
    public record Settings(String topology, String primaryUrl, String authorityUrl,
                           String primarySchema, String authoritySchema, String primaryOwner,
                           String authorityOwner, String primaryRuntime, String authorityRuntime, String backup) {
        public Settings {
            if (!"shared-instance".equals(topology) || primaryUrl == null
                    || !primaryUrl.startsWith("jdbc:dm://") || !primaryUrl.equals(authorityUrl))
                throw new IllegalArgumentException("Explicit shared-instance with exact DM8 owner URLs required");
            primarySchema = identifier(primarySchema);
            authoritySchema = identifier(authoritySchema);
            primaryOwner = identifier(primaryOwner);
            authorityOwner = identifier(authorityOwner);
            primaryRuntime = identifier(primaryRuntime);
            authorityRuntime = identifier(authorityRuntime);
            backup = identifier(backup);
            AuthorityDataSourceSettings.resolve(topology, primaryUrl, primarySchema, primaryOwner,
                    authorityUrl, authoritySchema, authorityOwner, "");
            AuthorityDataSourceSettings.resolve(topology, primaryUrl, primarySchema, primaryRuntime,
                    authorityUrl, authoritySchema, authorityRuntime, "");
            if (Set.of(primaryOwner, authorityOwner, primaryRuntime, authorityRuntime, backup).size() != 5)
                throw new IllegalArgumentException("Owner, runtime and backup identities must be independent");
        }

        void verify(Connection connection, String schema, String user) throws SQLException {
            if (!primaryUrl.equals(connection.getMetaData().getURL())
                    || !schema.equalsIgnoreCase(connection.getSchema())
                    || !user.equalsIgnoreCase(connection.getMetaData().getUserName()))
                throw new IllegalArgumentException("Actual shared-instance JDBC URL/schema/user mismatch");
        }

        /** Installation owns grants. Read exact direct privileges; never repair or reissue DCL. */
        private void verifyInstalledAuthorityPrivileges(Connection connection) throws SQLException {
            for (String table : new String[] {"ID2_AUTHORITY_WATERMARK", "ID2_MAINTAINER",
                    "ID2_MAINTENANCE_AUDIT"}) {
                Set<String> present = new HashSet<>();
                try (var grants = connection.getMetaData().getTablePrivileges(null, authoritySchema, table)) {
                    while (grants.next()) {
                        if (!authoritySchema.equalsIgnoreCase(grants.getString("TABLE_SCHEM"))
                                || !table.equalsIgnoreCase(grants.getString("TABLE_NAME"))) continue;
                        String grantee = grants.getString("GRANTEE");
                        if (!authorityRuntime.equalsIgnoreCase(grantee) && !backup.equalsIgnoreCase(grantee))
                            continue;
                        if (!"NO".equalsIgnoreCase(grants.getString("IS_GRANTABLE")))
                            throw new IllegalStateException("Installed authority privileges must not be grantable");
                        String privilege = grants.getString("PRIVILEGE");
                        if (privilege != null)
                            present.add(grantee.toUpperCase(Locale.ROOT) + ":" + privilege.toUpperCase(Locale.ROOT));
                    }
                }
                Set<String> required = "ID2_MAINTENANCE_AUDIT".equals(table)
                        ? Set.of(authorityRuntime + ":INSERT", backup + ":SELECT")
                        : Set.of(authorityRuntime + ":SELECT", authorityRuntime + ":UPDATE", backup + ":SELECT");
                if (!present.containsAll(required))
                    throw new IllegalStateException("Existing authority runtime and backup privileges required");
            }
        }
    }

    /** Runtime and backup connections prove the configured identities before read-only privilege verification or registration. */
    public static void bootstrap(Settings settings, DataSource primaryOwner, DataSource authorityOwner,
                                 DataSource primaryRuntime, DataSource authorityRuntime, DataSource backup,
                                 ProductIdentityBootstrap.Manifest manifest, String trustedCaFingerprint)
            throws Exception {
        if (settings == null) throw new IllegalArgumentException("Managed shared-instance settings required");
        try (var primary = primaryOwner.getConnection(); var authority = authorityOwner.getConnection();
             var runtime = primaryRuntime.getConnection(); var service = authorityRuntime.getConnection();
             var reader = backup.getConnection()) {
            settings.verify(primary, settings.primarySchema(), settings.primaryOwner());
            settings.verify(authority, settings.authoritySchema(), settings.authorityOwner());
            settings.verify(runtime, settings.primarySchema(), settings.primaryRuntime());
            settings.verify(service, settings.authoritySchema(), settings.authorityRuntime());
            settings.verify(reader, settings.authoritySchema(), settings.backup());
            settings.verifyInstalledAuthorityPrivileges(authority);
        }
        // Managed installation owns object grants. Shared bootstrap verifies them read-only;
        // it registers the normal maintainer without reissuing completed DCL.
        ProductIdentityBootstrap.bootstrapSharedInstance(new JdbcTemplate(primaryOwner),
                new JdbcTemplate(authorityOwner), manifest, trustedCaFingerprint, settings);
    }

    /** CLI: ProductIdentityBootstrapSharedInstance --shared-instance <manifest.json>. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--shared-instance".equals(args[0]))
            throw new IllegalArgumentException("Provide --shared-instance and one deployment manifest path");
        Settings settings = new Settings(requiredEnv("PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY"),
                requiredEnv("PRODUCT_IDENTITY_DB_URL"), requiredEnv("PRODUCT_IDENTITY_AUTHORITY_DB_URL"),
                requiredEnv("PRODUCT_IDENTITY_DB_SCHEMA"), requiredEnv("PRODUCT_IDENTITY_AUTHORITY_SCHEMA"),
                requiredEnv("PRODUCT_IDENTITY_DB_DEPLOY_USER"),
                requiredEnv("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER"),
                requiredEnv("PRODUCT_IDENTITY_DB_SERVICE_USER"),
                requiredEnv("PRODUCT_IDENTITY_AUTHORITY_SERVICE_USER"),
                requiredEnv("PRODUCT_IDENTITY_AUTHORITY_BACKUP_USER"));
        var manifest = new ObjectMapper().readValue(Path.of(args[1]).toFile(), ProductIdentityBootstrap.Manifest.class);
        bootstrap(settings,
                source(settings.primaryUrl(), settings.primarySchema(), settings.primaryOwner(),
                        "PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD"),
                source(settings.authorityUrl(), settings.authoritySchema(), settings.authorityOwner(),
                        "PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD"),
                source(settings.primaryUrl(), settings.primarySchema(), settings.primaryRuntime(),
                        "PRODUCT_IDENTITY_DB_SERVICE_PASSWORD"),
                source(settings.authorityUrl(), settings.authoritySchema(), settings.authorityRuntime(),
                        "PRODUCT_IDENTITY_AUTHORITY_SERVICE_PASSWORD"),
                source(settings.authorityUrl(), settings.authoritySchema(), settings.backup(),
                        "PRODUCT_IDENTITY_AUTHORITY_BACKUP_PASSWORD"), manifest,
                requiredEnv("PRODUCT_IDENTITY_TRUST_SHA256"));
        System.out.println("Product identity initialization committed; member activation and grant remain separate.");
    }

    private static DataSource source(String url, String schema, String user, String passwordEnv) {
        var source = new DriverManagerDataSource(url, user, requiredEnv(passwordEnv));
        source.setSchema(schema);
        return source;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing deployment setting: " + name);
        return value;
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{2,63}"))
            throw new IllegalArgumentException("Explicit pre-created database identifier required");
        return value.toUpperCase(Locale.ROOT);
    }
}
