package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductGrantWatermarkDm8Test {
    @Test void twoIsolatedDm8DatabasesRejectMissingAndRestoredAuthority() throws Exception {
        String primaryUrl = System.getenv("DM8_JDBC_URL");
        String authorityUrl = System.getenv("IDENTITY_AUTHORITY_DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        String authorityAdmin = System.getenv("IDENTITY_AUTHORITY_DM8_USER");
        String authorityPassword = System.getenv("IDENTITY_AUTHORITY_DM8_PASSWORD");
        Assumptions.assumeTrue(primaryUrl != null && authorityUrl != null && admin != null && password != null
                && authorityAdmin != null && authorityPassword != null
                && !primaryUrl.equals(authorityUrl), "Two isolated DM8 instances are required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "IW" + HexFormat.of().formatHex(random).toUpperCase();
        String serviceUser = "IS" + HexFormat.of().formatHex(random).toUpperCase();
        String backupUser = "IB" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        String issuer = "https://isolated-issuer.example.org";
        boolean primaryCreated = false;
        boolean authorityCreated = false;
        boolean serviceCreated = false;
        boolean backupCreated = false;
        try {
            createUser(primaryUrl, admin, password, schema, secret);
            primaryCreated = true;
            createUser(authorityUrl, authorityAdmin, authorityPassword, schema, secret);
            authorityCreated = true;
            createRestrictedUser(authorityUrl, authorityAdmin, authorityPassword, serviceUser, secret);
            serviceCreated = true;
            createRestrictedUser(authorityUrl, authorityAdmin, authorityPassword, backupUser, secret);
            backupCreated = true;
            try (var primaryConnection = DriverManager.getConnection(primaryUrl, schema, secret);
                 var authorityConnection = DriverManager.getConnection(authorityUrl, schema, secret)) {
                try {
                    ScriptUtils.executeSqlScript(primaryConnection, new FileSystemResource(
                            Path.of("db/dm8/V032__product_identity.sql")));
                    ScriptUtils.executeSqlScript(authorityConnection, new FileSystemResource(
                            Path.of("db/identity-authority/V001__grant_watermark.sql")));
                    var primary = new JdbcTemplate(new DriverManagerDataSource(primaryUrl, schema, secret));
                    var authority = new JdbcTemplate(new DriverManagerDataSource(authorityUrl, schema, secret));
                    authority.execute("GRANT SELECT,UPDATE ON id2_authority_watermark TO " + serviceUser);
                    authority.execute("GRANT SELECT,UPDATE ON id2_maintainer TO " + serviceUser);
                    authority.execute("GRANT INSERT ON id2_maintenance_audit TO " + serviceUser);
                    for (String table : new String[] {"id2_authority_watermark", "id2_maintainer",
                            "id2_maintenance_audit"}) authority.execute("GRANT SELECT ON " + table + " TO " + backupUser);
                    var backup = new JdbcTemplate(new DriverManagerDataSource(authorityUrl, backupUser, secret));
                    try (var scoped = DriverManager.getConnection(authorityUrl, serviceUser, secret)) {
                        scoped.setSchema(schema);
                        try (var query = scoped.createStatement();
                             var rows = query.executeQuery("SELECT COUNT(*) FROM id2_maintainer")) {
                            rows.next();
                            assertThat(rows.getInt(1)).isZero();
                        }
                    }
                    assertThatThrownBy(() -> backup.update("UPDATE " + schema
                            + ".id2_authority_watermark SET generation=1"))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                    assertThatThrownBy(() -> new ProductGrantWatermark(primary, authority, issuer))
                            .isInstanceOf(IllegalStateException.class);
                    authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,"
                                    + "history_sha) VALUES(?,0,'READY',NULL,?)", issuer,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
                    var guard = new ProductGrantWatermark(primary, authority, issuer);
                    authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='interrupted' "
                            + "WHERE issuer=?", issuer);
                    assertThatThrownBy(guard::assertCurrent).isInstanceOf(IllegalStateException.class);
                } finally {
                    for (String table : new String[] {"id2_maintenance_audit", "id2_maintainer",
                            "id2_authority_watermark"}) {
                        try (var drop = authorityConnection.createStatement()) { drop.execute("DROP TABLE " + table); }
                        catch (SQLException absent) { /* A failed migration may leave some tables absent. */ }
                    }
                    for (String table : new String[] {"id2_grant_event", "id2_current_epoch", "id2_grant",
                            "id2_session", "id2_key_event", "id2_signing_key", "id2_key_control",
                            "id2_subject_event", "id2_subject"}) {
                        try (var drop = primaryConnection.createStatement()) { drop.execute("DROP TABLE " + table + " CASCADE"); }
                        catch (SQLException absent) { /* A failed migration may leave some tables absent. */ }
                    }
                }
            }
        } finally {
            if (backupCreated) dropUser(authorityUrl, authorityAdmin, authorityPassword, backupUser);
            if (serviceCreated) dropUser(authorityUrl, authorityAdmin, authorityPassword, serviceUser);
            if (authorityCreated) dropUser(authorityUrl, authorityAdmin, authorityPassword, schema);
            if (primaryCreated) dropUser(primaryUrl, admin, password, schema);
        }
    }

    private static void createUser(String url, String admin, String password, String schema, String secret)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, admin, password);
             var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }

    private static void dropUser(String url, String admin, String password, String schema) throws SQLException {
        try (var connection = DriverManager.getConnection(url, admin, password);
             var command = connection.createStatement()) {
            command.execute("DROP USER " + schema);
        }
    }

    private static void createRestrictedUser(String url, String admin, String password, String name, String secret)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, admin, password);
             var command = connection.createStatement()) {
            command.execute("CREATE USER " + name + " IDENTIFIED BY \"" + secret + "\"");
        }
    }
}
