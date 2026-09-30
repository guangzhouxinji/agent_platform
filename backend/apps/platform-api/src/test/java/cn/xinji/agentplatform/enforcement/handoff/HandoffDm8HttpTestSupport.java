package cn.xinji.agentplatform.enforcement.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

final class HandoffDm8HttpTestSupport {
    private HandoffDm8HttpTestSupport() { }

    static Process second(String[] settings, int port, Path log) throws Exception {
        Path jar = Path.of("backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar")
                .toAbsolutePath();
        assertThat(Files.exists(jar)).isTrue();
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Dloader.path=" + Path.of("backend/apps/platform-api/target/test-classes").toAbsolutePath());
        command.add("-Dloader.main=" + HandoffIndependentHttpNode.class.getName());
        command.add("-cp"); command.add(jar.toString());
        command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        for (String setting : settings) {
            if (setting.startsWith("server.port=") || setting.startsWith("spring.datasource.password=") ||
                    setting.startsWith("product.identity.authority.password=")) continue;
            command.add("--" + setting);
        }
        command.add("--server.port=" + port);
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", value(settings, "spring.datasource.password="));
        builder.environment().put("PRODUCT_IDENTITY_AUTHORITY_PASSWORD",
                value(settings, "product.identity.authority.password="));
        return builder.start();
    }

    private static String value(String[] settings, String prefix) {
        for (String setting : settings) if (setting.startsWith(prefix)) return setting.substring(prefix.length());
        throw new IllegalArgumentException("Missing HANDOFF setting");
    }

    static void migrate(DriverManagerDataSource source, List<String> migrations) throws Exception {
        try (var connection = source.getConnection()) {
            for (String migration : migrations)
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(migration)));
        }
    }

    static void createUser(String url, String password, String owner, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
    }

    static void dropUser(String url, String password, String owner) throws Exception {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + owner); }
    }

    static void dropAuthority(String url, String owner, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, owner, secret);
             var statement = connection.createStatement()) {
            for (String table : List.of("enf3_delete_watermark", "enf3_source_watermark",
                    "id2_maintenance_audit", "id2_maintainer", "id2_authority_watermark"))
                statement.execute("DROP TABLE " + table + " CASCADE");
        }
    }
}
