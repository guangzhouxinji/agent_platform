package cn.xinji.agentplatform.enforcement.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-only safety boundary. No product permissions, fixture bypass, or cleanup operations. */
public final class EnforcementDm8IsolationGuard {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> PRODUCT_PORTS = Set.of(5236,5237,5301,5302,5311,5312,5319,5367,5370);
    private EnforcementDm8IsolationGuard() { }
    public record Scope(String runId, String schema, Path fileRoot, String containerId) {
        public void assertFreshSchema(JdbcTemplate db) throws Exception {
            try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
                require(schema.equalsIgnoreCase(connection.getMetaData().getUserName()), "Connected identity mismatch");
            }
            require(Objects.equals(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES", Integer.class), 0),
                    "Existing tables/records forbidden; refusing import and cleanup");
        }
    }
    public static Scope beforeConnect(String url, String user) throws Exception {
        String run = required("ENF_TEST_RUN_ID");
        String container = required("ENF_TEST_CONTAINER");
        String volume = required("ENF_TEST_VOLUME");
        String schema = required("ENF_TEST_DB_SCHEMA");
        Path root = Path.of(required("ENF_TEST_FILE_ROOT"));
        validateEndpoint(url, user, schema, run);
        require(container.equals("codex-enf-rbc-isolated-" + run.toLowerCase(Locale.ROOT)), "Dedicated container required");
        require(volume.equals(container + "-data"), "Dedicated volume required");
        var inspected = JSON.createObjectNode();
        for (var field : Map.of("id", ".Id", "labels", ".Config.Labels", "ports", ".HostConfig.PortBindings", "mounts", ".Mounts", "running", ".State.Running").entrySet())
            inspected.set(field.getKey(), JSON.readTree(docker("inspect", "--format", "{{json " + field.getValue() + "}}", container)));
        var volumeInfo = JSON.createObjectNode();
        for (var field : Map.of("name", ".Name", "labels", ".Labels").entrySet())
            volumeInfo.set(field.getKey(), JSON.readTree(docker("volume", "inspect", "--format", "{{json " + field.getValue() + "}}", volume)));
        String users = docker("ps", "-a", "--filter", "volume=" + volume, "--format", "{{.ID}}").trim();
        require(!users.isEmpty() && !users.contains("\n") && inspected.path("id").asText().startsWith(users), "Volume must have exactly one container owner");
        validateDocker(url, run, volume, inspected, volumeInfo);
        validateFiles(run, schema, volume, inspected.path("id").asText(), root);
        beginRun(root, run);
        return new Scope(run, schema, root, inspected.path("id").asText());
    }
    public static void validateFiles(String run, String schema, String volume, String containerId, Path root) throws Exception {
        Path expected = Path.of(System.getProperty("java.io.tmpdir"), "enf-owned-" + run, "files").toAbsolutePath().normalize();
        require(root.toAbsolutePath().normalize().equals(expected), "File root outside exact run TEMP boundary");
        require(Files.isDirectory(root) && root.toRealPath().equals(expected), "File root missing or redirected");
        for (Path p = root; p != null && p.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()); p = p.getParent())
            require(!Files.isSymbolicLink(p), "Redirected file parent forbidden");
        JsonNode marker = JSON.readTree(Files.readString(root.resolve(".enf-test-ownership.json"), StandardCharsets.UTF_8));
        require(run.equals(marker.path("runId").asText()) && schema.equals(marker.path("schema").asText())
                && volume.equals(marker.path("volume").asText()) && containerId.equals(marker.path("containerId").asText()), "File ownership marker mismatch");
    }
    public static void beginRun(Path root, String run) throws Exception {
        Files.writeString(root.resolve(".enf-rbc-run-started"), run, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    public static void validateEndpoint(String url, String user, String schema, String run) {
        require(run != null && run.matches("[A-Z0-9_-]{8,80}"), "Explicit run ID required");
        require(schema != null && schema.matches("ENFISO_[A-F0-9]{12}") && schema.equals(user), "Dedicated random schema required");
        require(url != null && url.matches("jdbc:dm://127\\.0\\.0\\.1:[0-9]{4,5}"), "Dedicated loopback endpoint required");
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1));
        require(port >= 1024 && port <= 65535 && !PRODUCT_PORTS.contains(port), "Product/recovery database endpoint forbidden");
    }
    public static void validateDocker(String url, String run, String volume, JsonNode container, JsonNode volumeInfo) {
        require(container.path("running").asBoolean(), "Independent container not running");
        require("enforcement-test".equals(container.path("labels").path("agent-platform.role").asText())
                && run.equals(container.path("labels").path("agent-platform.run-id").asText()), "Container ownership labels mismatch");
        require(volume.equals(volumeInfo.path("name").asText())
                && "enforcement-test".equals(volumeInfo.path("labels").path("agent-platform.role").asText())
                && run.equals(volumeInfo.path("labels").path("agent-platform.run-id").asText()), "Volume ownership labels mismatch");
        JsonNode ports = container.path("ports").path("5236/tcp");
        require(ports.isArray() && ports.size() == 1 && "127.0.0.1".equals(ports.get(0).path("HostIp").asText())
                && url.endsWith(":" + ports.get(0).path("HostPort").asText()), "Port binding mismatch");
        int dataMounts = 0;
        require(container.path("mounts").isArray() && container.path("mounts").size() == 1, "Extra/unproven volume forbidden");
        for (JsonNode mount : container.path("mounts")) {
            require(!"bind".equals(mount.path("Type").asText()), "Shared host bind mount forbidden");
            if ("/opt/dmdbms/data".equals(mount.path("Destination").asText())) {
                require("volume".equals(mount.path("Type").asText()) && volume.equals(mount.path("Name").asText())
                        && mount.path("RW").asBoolean(), "Data mount ownership mismatch");
                dataMounts++;
            }
        }
        require(dataMounts == 1, "Exactly one owned DM8 data volume required");
    }
    private static String docker(String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add("docker"); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Docker inspection timeout"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.exitValue() == 0, "Docker inspection unavailable; refuse connection");
        return output.trim();
    }
    private static String required(String name) {
        String value = System.getenv(name); require(value != null && !value.isBlank(), name + " required; no skip or fallback"); return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
