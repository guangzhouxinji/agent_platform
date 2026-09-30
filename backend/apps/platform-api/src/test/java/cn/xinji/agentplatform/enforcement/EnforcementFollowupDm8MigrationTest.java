package cn.xinji.agentplatform.enforcement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnforcementFollowupDm8MigrationTest {
    @Test void documentTemplateAndLegalReferenceMigrationsRunOnIsolatedDm8() throws Exception {
        String url = required("DM8_JDBC_URL");
        String user = required("DM8_JDBC_USER");
        String password = required("DM8_JDBC_PASSWORD");
        byte[] random = new byte[8]; new SecureRandom().nextBytes(random);
        String schema = "EF" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        create(url, user, password, schema, secret);
        try (var connection = DriverManager.getConnection(url, schema, secret)) {
            script(connection, "V074__enforcement_document_template.sql");
            script(connection, "V075__enforcement_v2_legal_reference.sql");
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'ENF8_%' OR TABLE_NAME LIKE 'ENF9_%'")) {
                rows.next(); assertEquals(10, rows.getInt(1));
            }
            script(connection, "U075__enforcement_v2_legal_reference.sql");
            script(connection, "U074__enforcement_document_template.sql");
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'ENF8_%' OR TABLE_NAME LIKE 'ENF9_%'")) {
                rows.next(); assertEquals(0, rows.getInt(1));
            }
        } finally { remove(url, user, password, schema); }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static void script(java.sql.Connection connection, String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("db/dm8").resolve(name))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Migration unavailable: " + name);
        ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("db/dm8").resolve(name)));
    }
    private static void create(String url, String user, String password, String schema, String secret) throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }
    private static void remove(String url, String user, String password, String schema) throws SQLException {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("DROP USER " + schema + " CASCADE");
        }
    }
}
