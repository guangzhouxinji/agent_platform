package cn.xinji.agentplatform.security.product;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/** Isolated physical Task schema export/import used only by the DM8 integration exercise. */
final class ProductTaskDm8Archive implements AutoCloseable {
    private final String container;
    private final String file;
    private final String url;
    private final String admin;
    private final String adminPassword;
    private final String target;
    private boolean created;

    ProductTaskDm8Archive(String container, String url, String admin, String adminPassword,
            String source, String target, String secret) throws Exception {
        this.container = container;
        this.url = url;
        this.admin = admin;
        this.adminPassword = adminPassword;
        this.target = target;
        this.file = source.toLowerCase() + ".dmp";
        if (!source.matches("CS[0-9A-F]{16}") || !target.matches("CR[0-9A-F]{16}")
                || !container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))
            throw new IllegalArgumentException("Isolated archive targets required");
        try {
            try (var connection = DriverManager.getConnection(url, admin, adminPassword);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE USER " + target + " IDENTIFIED BY \"" + secret + "\"");
                created = true;
                statement.execute("GRANT RESOURCE TO " + target);
            }
            run(List.of("/opt/dmdbms/bin/dexp", source + "/" + secret + "@127.0.0.1:5236",
                    "SCHEMAS=(" + source + ")", "FILE=" + file, "DIRECTORY=/tmp",
                    "ENCRYPT=Y", "ENCRYPT_PASSWORD=" + secret, "NOLOGFILE=Y"));
            run(List.of("/opt/dmdbms/bin/dimp", admin + "/" + adminPassword + "@127.0.0.1:5236",
                    "SCHEMAS=(" + source + ")", "REMAP_SCHEMA=(" + source + ":" + target + ")",
                    "FILE=" + file, "DIRECTORY=/tmp", "ENCRYPT_PASSWORD=" + secret, "NOLOGFILE=Y"));
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    String target() { return target; }

    private void run(List<String> command) throws IOException, InterruptedException {
        var args = new java.util.ArrayList<>(List.of("docker", "exec", container));
        args.addAll(command);
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("DM8 archive command failed: "
                + command.getFirst().substring(command.getFirst().lastIndexOf('/') + 1)
                + ", output bytes=" + output.length());
    }

    @Override public void close() throws Exception {
        try {
            new ProcessBuilder("docker", "exec", container, "rm", "-f", "/tmp/" + file)
                    .inheritIO().start().waitFor();
        } finally {
            if (created) try (var connection = DriverManager.getConnection(url, admin, adminPassword);
                             var statement = connection.createStatement()) {
                statement.execute("DROP USER " + target + " CASCADE");
            } catch (SQLException failure) {
                throw new IllegalStateException("Restored temporary schema cleanup failed", failure);
            }
        }
    }
}
