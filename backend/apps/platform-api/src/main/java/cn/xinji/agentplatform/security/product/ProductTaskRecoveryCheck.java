package cn.xinji.agentplatform.security.product;

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
                       String authoritySchema, String issuer, Path evidenceRoot, long evidenceMaxBytes) { }

    private ProductTaskRecoveryCheck() { }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Recovery check takes no arguments");
        verify(new Connections(required("PRODUCT_TASK_RECOVERY_DB_URL"),
                required("PRODUCT_TASK_RECOVERY_DB_USER"), required("PRODUCT_TASK_RECOVERY_DB_PASSWORD"),
                required("PRODUCT_TASK_RECOVERY_DB_SCHEMA"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_DB_URL"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_USER"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_PASSWORD"),
                required("PRODUCT_TASK_RECOVERY_AUTHORITY_SCHEMA"), required("PRODUCT_TASK_RECOVERY_ISSUER"),
                Path.of(required("PRODUCT_TASK_RECOVERY_EVIDENCE_ROOT")),
                positiveLong("PRODUCT_TASK_RECOVERY_EVIDENCE_MAX_BYTES")));
    }

    static void verify(Connections connections) {
        if (connections.primaryUrl().equals(connections.authorityUrl()))
            throw new IllegalArgumentException("Independent authority database required");
        JdbcTemplate primary = new JdbcTemplate(source(connections.primaryUrl(),
                connections.primaryUser(), connections.primaryPassword(), schema(connections.primarySchema())));
        JdbcTemplate authority = new JdbcTemplate(source(connections.authorityUrl(),
                connections.authorityUser(), connections.authorityPassword(),
                schema(connections.authoritySchema())));
        var watermark = new ProductGrantWatermark(primary, authority, connections.issuer());
        new ProductTaskRecoveryVerifier(primary, watermark).verify();
        new ProductSourceWatermark(primary, authority,
                new TransactionTemplate(new DataSourceTransactionManager(primary.getDataSource())))
                .assertAllCurrent();
        new ProductSourceRecoveryFiles(primary, connections.evidenceRoot(),
                connections.evidenceMaxBytes()).verify();
        watermark.assertCurrent();
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
