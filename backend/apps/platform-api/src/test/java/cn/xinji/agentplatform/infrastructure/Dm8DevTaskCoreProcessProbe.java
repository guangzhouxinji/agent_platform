package cn.xinji.agentplatform.infrastructure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
final class Dm8DevTaskCoreProcessProbe {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private Dm8DevTaskCoreProcessProbe() { }
    static void independentHttpProcessAndRestart(DriverManagerDataSource source, String schema,
                                                          String password, ZoneId zone) throws Exception {
        Path root = WorkspaceTestPaths.root();
        Path jar = root.resolve(Path.of("backend", "apps", "platform-api", "target",
                System.getProperty("v2.process.jar", "agent-platform-api-0.0.1-SNAPSHOT.jar")));
        Assumptions.assumeTrue(Files.isRegularFile(jar), "Build the platform-api jar for process verification");
        ObjectMapper mapper = new ObjectMapper();
        String base;
        try (ServerSocket socket = new ServerSocket(0)) { base = "http://127.0.0.1:" + socket.getLocalPort(); }
        Map<String, String> secrets = new HashMap<>();
        for (String id : java.util.List.of("human-a", "human-b", "agent", "job")) {
            secrets.put(id, id + "-" + java.util.UUID.randomUUID() + java.util.UUID.randomUUID());
        }
        ObjectNode configurationRoot = mapper.createObjectNode();
        ObjectNode development = configurationRoot.putObject("consultation-v2").putObject("development");
        ArrayNode credentials = development.putArray("credentials");
        credential(credentials, "human-a", "dev-con-a", "HUMAN", secrets);
        credential(credentials, "human-b", "dev-con-b", "HUMAN", secrets);
        credential(credentials, "agent", "dev-con-agent", "AGENT", secrets);
        credential(credentials, "job", "dev-con-job", "JOB", secrets);
        String configuration = mapper.writeValueAsString(configurationRoot);
        Path signedInputs = root.resolve(Path.of("data", "consultation", "dev-comparison-input-v1"));
        Path copiedInputs = Files.createTempDirectory("v2-comparison-input-");
        Path comparisonManifest = copiedInputs.resolve("manifest.json");
        Path comparisonPayload = copiedInputs.resolve("input.json");
        Files.copy(signedInputs.resolve("manifest.json"), comparisonManifest);
        Files.copy(signedInputs.resolve("input.json"), comparisonPayload);
        Path signedCorrection = root.resolve(Path.of("data", "consultation", "dev-correction-input-v1"));
        Path copiedCorrection = Files.createTempDirectory("v2-correction-input-");
        Path correctionManifest = copiedCorrection.resolve("manifest.json");
        Path correctionPayload = copiedCorrection.resolve("input.json");
        Files.copy(signedCorrection.resolve("manifest.json"), correctionManifest);
        Files.copy(signedCorrection.resolve("input.json"), correctionPayload);
        Path log = Files.createTempFile("v2-consultation-dm8-", ".log");
        String retainedTaskId;
        String returnedTaskId;
        try {
            Process service = startService(jar, base, source.getUrl(), schema, password, zone,
                    configuration, comparisonManifest, correctionManifest, true, log);
            try {
                awaitService(base, service, log, password);
                String goalJson = mapper.writeValueAsString(Map.of("goal", GOAL));
                var created = request(base, "POST", "/v2/consultation/tasks", secrets, "human-a",
                        goalJson, "process-create-12345");
                assertThat(created.statusCode()).isEqualTo(201);
                String taskId = mapper.readTree(created.body()).path("taskId").asText();
                retainedTaskId = taskId;
                JdbcTemplate sql = new JdbcTemplate(source);
                String attempt = sql.queryForObject("SELECT attempt_id FROM con2_attempt WHERE task_id=?",
                        String.class, taskId);
                ProcessBuilder agentClient = new ProcessBuilder("node", root.resolve(Path.of("scripts",
                        "probe-consultation-v2-agent.mjs")).toString())
                        .directory(root.toFile()).redirectErrorStream(true);
                agentClient.environment().remove("DM8_JDBC_URL");
                agentClient.environment().remove("DM8_JDBC_USER");
                agentClient.environment().remove("DM8_JDBC_PASSWORD");
                agentClient.environment().put("V2_BASE_URL", base);
                agentClient.environment().put("V2_AGENT_TOKEN", "Bearer xj_agent." + secrets.get("agent"));
                agentClient.environment().put("V2_ATTEMPT_ID", attempt);
                agentClient.environment().put("V2_COMMITTED_REF", "message:1");
                Process client = agentClient.start();
                if (!client.waitFor(20, TimeUnit.SECONDS)) {
                    client.destroyForcibly();
                    throw new AssertionError("Independent Agent HTTP probe timed out");
                }
                assertThat(client.exitValue()).as("Independent Agent HTTP probe")
                        .withFailMessage("Independent Agent HTTP probe failed: "
                                + new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                        .isZero();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_checkpoint WHERE task_id=?",
                        Integer.class, taskId)).isEqualTo(1);
                var search = request(base, "POST", "/v2/consultation/tasks/" + taskId + "/text-searches",
                        secrets, "human-a", "{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}",
                        "process-text-search-12345");
                assertThat(search.statusCode()).isEqualTo(200);
                String setId = mapper.readTree(search.body()).path("setId").asText();
                assertThat(mapper.readTree(search.body()).path("members").get(1).path("recordId").asText())
                        .isEqualTo("P2");
                var focus = request(base, "POST", "/v2/consultation/tasks/" + taskId + "/focus",
                        secrets, "human-a", "{\"setId\":\"" + setId + "\",\"ordinal\":2,"
                                + "\"expectedTaskRevision\":1,\"expectedFocusSequence\":0}",
                        "process-text-focus-12345");
                assertThat(focus.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(focus.body()).path("recordId").asText()).isEqualTo("P2");
                var evidence = request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/text-searches/" + setId + "/members/2/evidence-preparation",
                        secrets, "human-a", null, null);
                assertThat(evidence.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(evidence.body()).path("targetText").asText()).isEqualTo(GOAL);
                int receiptsBeforeDrift = sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                        Integer.class, taskId);
                Files.writeString(comparisonPayload, Files.readString(comparisonPayload).replace(GOAL, GOAL + "篡改"));
                var deniedTask = request(base, "GET", "/v2/consultation/tasks/" + taskId,
                        secrets, "human-a", null, null);
                var deniedEvidence = request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/text-searches/" + setId + "/members/2/evidence-preparation",
                        secrets, "human-a", null, null);
                assertThat(deniedTask.statusCode()).isEqualTo(409);
                assertThat(deniedEvidence.statusCode()).isEqualTo(409);
                assertThat(deniedTask.body()).doesNotContain(GOAL);
                assertThat(deniedEvidence.body()).doesNotContain(GOAL);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                        Integer.class, taskId)).isEqualTo(receiptsBeforeDrift);
                Files.copy(signedInputs.resolve("input.json"), comparisonPayload, StandardCopyOption.REPLACE_EXISTING);
                assertThat(request(base, "GET", "/v2/consultation/tasks/" + taskId,
                        secrets, "human-a", null, null).statusCode()).isEqualTo(200);
                Path unavailablePayload = copiedInputs.resolve("input.unavailable");
                Files.move(comparisonPayload, unavailablePayload);
                try {
                    var missingTask = request(base, "GET", "/v2/consultation/tasks/" + taskId,
                            secrets, "human-a", null, null);
                    var missingEvidence = request(base, "GET", "/v2/consultation/tasks/" + taskId
                            + "/text-searches/" + setId + "/members/2/evidence-preparation",
                            secrets, "human-a", null, null);
                    assertThat(missingTask.statusCode()).isEqualTo(409);
                    assertThat(missingEvidence.statusCode()).isEqualTo(409);
                    assertThat(missingTask.body()).doesNotContain(GOAL);
                    assertThat(missingEvidence.body()).doesNotContain(GOAL);
                    assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                            Integer.class, taskId)).isEqualTo(receiptsBeforeDrift);
                } finally {
                    Files.move(unavailablePayload, comparisonPayload);
                }
                assertThat(request(base, "GET", "/v2/consultation/tasks/" + taskId,
                        secrets, "human-a", null, null).statusCode()).isEqualTo(200);
                assertThat(request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/text-searches/" + setId + "/members/2/evidence-preparation",
                        secrets, "agent", null, null).statusCode()).isEqualTo(403);
                assertThat(request(base, "POST", "/v2/consultation/tasks/" + taskId + "/messages",
                        secrets, "human-a", mapper.writeValueAsString(Map.of("message", MESSAGE,
                                "expectedVersion", 1)), "process-feature-append-12345").statusCode()).isEqualTo(200);
                var confirmation = request(base, "POST", "/v2/consultation/tasks/" + taskId
                        + "/feature-confirmations", secrets, "human-a", featurePayload(),
                        "process-feature-confirm-12345");
                assertThat(confirmation.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(confirmation.body()).path("featureRevision").asLong()).isEqualTo(1);
                DevHumanRequestProcessScenarios.created(taskId,
                        (method, path, body, key) -> request(base, method, path, secrets, "human-a", body, key));
                DevHumanAcceptanceProcessScenarios.accepted(taskId,
                        (method, path, body, key, actor) -> request(base, method, path, secrets, actor, body, key));
                returnedTaskId = DevHumanReturnProcessScenarios.returned(
                        (method, path, body, key, actor) -> request(base, method, path, secrets, actor, body, key));
            } finally {
                stop(service);
            }
            service = startService(jar, base, source.getUrl(), schema, password, zone,
                    configuration, comparisonManifest, correctionManifest, false, log);
            try {
                awaitService(base, service, log, password);
                JdbcTemplate sql = new JdbcTemplate(source);
                String taskId = retainedTaskId;
                var restored = request(base, "GET", "/v2/consultation/tasks/" + taskId,
                        secrets, "human-a", null, null);
                assertThat(restored.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(restored.body()).path("checkpoints").size()).isEqualTo(1);
                String setId = sql.queryForObject("SELECT set_id FROM con2_result_set WHERE task_id=?",
                        String.class, taskId);
                var restoredSet = request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/text-searches/" + setId, secrets, "human-a", null, null);
                assertThat(restoredSet.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(restoredSet.body()).path("members").get(1).path("recordId").asText())
                        .isEqualTo("P2");
                var restoredEvidence = request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/text-searches/" + setId + "/members/2/evidence-preparation",
                        secrets, "human-a", null, null);
                assertThat(restoredEvidence.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(restoredEvidence.body()).path("recordId").asText()).isEqualTo("P2");
                var restoredFeature = request(base, "GET", "/v2/consultation/tasks/" + taskId
                        + "/feature-confirmations/1", secrets, "human-a", null, null);
                assertThat(restoredFeature.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(restoredFeature.body()).path("candidates").size()).isEqualTo(3);
                DevHumanRequestProcessScenarios.restored(taskId,
                        (method, path, body, key) -> request(base, method, path, secrets, "human-a", body, key));
                DevHumanAcceptanceProcessScenarios.restored(taskId,
                        (method, path, body, key, actor) -> request(base, method, path, secrets, actor, body, key));
                DevHumanReturnProcessScenarios.restored(returnedTaskId,
                        (method, path, body, key, actor) -> request(base, method, path, secrets, actor, body, key));
                var replayFeature = request(base, "POST", "/v2/consultation/tasks/" + taskId
                        + "/feature-confirmations", secrets, "human-a", featurePayload(),
                        "process-feature-confirm-12345");
                assertThat(replayFeature.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(replayFeature.body()).path("receiptId").asText())
                        .isEqualTo(mapper.readTree(restoredFeature.body()).path("receiptId").asText());
                var replaySearch = request(base, "POST", "/v2/consultation/tasks/" + taskId + "/text-searches",
                        secrets, "human-a", "{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}",
                        "process-text-search-12345");
                assertThat(replaySearch.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(replaySearch.body()).path("setId").asText()).isEqualTo(setId);
                String originalReceipt = sql.queryForObject("SELECT receipt_id FROM con2_receipt "
                        + "WHERE task_id=? AND operation='CREATE'", String.class, taskId);
                var replay = request(base, "POST", "/v2/consultation/tasks", secrets, "human-a",
                        mapper.writeValueAsString(Map.of("goal", GOAL)), "process-create-12345");
                assertThat(replay.statusCode()).isEqualTo(201);
                assertThat(mapper.readTree(replay.body()).path("receiptId").asText()).isEqualTo(originalReceipt);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isEqualTo(2);
                assertThat(request(base, "POST", "/v2/consultation/tasks/" + taskId + "/cancel",
                        secrets, "human-a", "{\"expectedVersion\":2}", "process-cancel-12345")
                        .statusCode()).isEqualTo(200);
                sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                        Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)),
                        taskId);
                sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                        Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)),
                        returnedTaskId);
                assertThat(request(base, "GET", "/v2/consultation/tasks/" + taskId,
                        secrets, "human-a", null, null).statusCode()).isEqualTo(410);
                var purged = request(base, "POST", "/v2/internal/consultation/retention/reconcile",
                        secrets, "job", null, null);
                assertThat(purged.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(purged.body()).path("removed").asInt()).isEqualTo(2);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isZero();
            } finally {
                stop(service);
            }
            if (Boolean.getBoolean("v2.browser.e2e")) {
                service = startService(jar, base, source.getUrl(), schema, password, zone,
                        configuration, comparisonManifest, correctionManifest, true, log);
                try {
                    awaitService(base, service, log, password);
                    DevHumanAcceptanceBrowserScenarios.workbench(root, base, secrets.get("human-a"),
                            secrets.get("human-b"), secrets.get("job"), comparisonManifest,
                            correctionManifest, new JdbcTemplate(source));
                } finally { stop(service); }
            }
        } finally {
            for (int attempt = 0; attempt < 20 && Files.exists(log); attempt++) {
                try { Files.deleteIfExists(log); }
                catch (java.nio.file.FileSystemException busy) { Thread.sleep(100); }
            }
            if (Files.exists(log)) log.toFile().deleteOnExit();
            for (Path temporary : java.util.List.of(comparisonPayload, comparisonManifest, copiedInputs,
                    correctionPayload, correctionManifest, copiedCorrection)) Files.deleteIfExists(temporary);
        }
    }
    private static String featurePayload() {
        return "{\"expectedTaskRevision\":2,\"expectedFeatureRevision\":0,"
                + "\"candidateManifestId\":\"DEV-CON-FEATURE-CANDIDATES-01\","
                + "\"featureIds\":[\"F1\",\"F2\",\"F3\"]}";
    }
    private static Process startService(Path jar, String base, String url, String schema, String password,
                                        ZoneId zone, String configuration, Path comparisonManifest, Path correctionManifest,
                                        boolean humanAccept, Path log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("java", "-Xms64m", "-Xmx512m", "-jar",
                jar.toAbsolutePath().toString());
        Path root = WorkspaceTestPaths.root();
        builder.directory(root.toFile());
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        var env = builder.environment();
        env.remove("DM8_JDBC_URL");
        env.remove("DM8_JDBC_USER");
        env.remove("DM8_JDBC_PASSWORD");
        env.put("SPRING_PROFILES_ACTIVE", "test-synthetic");
        env.put("SERVER_PORT", base.substring(base.lastIndexOf(':') + 1));
        env.put("SPRING_DATASOURCE_URL", url);
        env.put("SPRING_DATASOURCE_USERNAME", schema);
        env.put("SPRING_DATASOURCE_PASSWORD", password);
        env.put("SPRING_DATASOURCE_DRIVER_CLASS_NAME", "dm.jdbc.driver.DmDriver");
        env.put("SPRING_APPLICATION_JSON", configuration);
        env.put("CONSULTATION_STORAGE", "memory");
        env.put("CONSULTATION_V2_DEV_ENABLED", "true");
        env.put("CONSULTATION_V2_TASK_CORE_ENABLED", "true");
        env.put("CONSULTATION_V2_TEXT_RESULT_ENABLED", "true");
        env.put("CONSULTATION_V2_EVIDENCE_READ_ENABLED", "true");
        env.put("CONSULTATION_V2_FEATURE_CONFIRM_ENABLED", "true");
        env.put("CONSULTATION_V2_COMPARISON_RECONFIRM_ENABLED", "true");
        env.put("CONSULTATION_V2_INPUT_PREVIEW_ENABLED", "true");
        env.put("CONSULTATION_V2_PURPOSE_READ_ENABLED", "true");
        env.put("CONSULTATION_V2_PURPOSE_SELECT_ENABLED", "true");
        env.put("CONSULTATION_V2_WORK_STATE_ENABLED", "true");
        env.put("CONSULTATION_V2_HUMAN_REQUEST_ENABLED", "true");
        env.put("CONSULTATION_V2_HUMAN_ACCEPT_ENABLED", Boolean.toString(humanAccept));
        env.put("CONSULTATION_V2_HUMAN_OPINION_ENABLED", Boolean.toString(humanAccept));
        env.put("CONSULTATION_V2_HUMAN_RETURN_ENABLED", Boolean.toString(humanAccept));
        env.put("CONSULTATION_V2_HUMAN_CONTEXT_ENABLED", Boolean.toString(humanAccept));
        env.put("CONSULTATION_V2_CORRECTION_ENABLED", "true");
        env.put("CONSULTATION_V2_CORRECTION_INPUT_MANIFEST", correctionManifest.toString());
        env.put("CONSULTATION_V2_HUMAN_REQUEST_MANIFEST", root.resolve(Path.of("data", "consultation",
                "dev-human-gap-v1", "manifest.json")).toString());
        env.put("CONSULTATION_V2_DB_SESSION_ZONE", zone.getId());
        env.put("CONSULTATION_V2_SYNTHETIC_MANIFEST", root.resolve(Path.of("data", "consultation",
                "dev-synthetic-v1", "manifest.json")).toString());
        env.put("CONSULTATION_V2_TASK_INPUT_MANIFEST", root.resolve(Path.of("data", "consultation",
                "dev-synthetic-v1", "task-input-manifest.json")).toString());
        env.put("CONSULTATION_V2_COMPARISON_INPUT_MANIFEST", comparisonManifest.toString());
        env.put("CONSULTATION_V2_FEATURE_CANDIDATE_MANIFEST", root.resolve(Path.of("data", "consultation",
                "dev-feature-candidates-v1", "manifest.json")).toString());
        return builder.start();
    }
    private static void awaitService(String base, Process service, Path log, String password) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plusSeconds(45);
        while (Instant.now().isBefore(deadline)) {
            if (!service.isAlive()) throw new AssertionError("V2 data service exited before readiness: "
                    + startupFailure(log, password));
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                        .GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) return;
            } catch (Exception ignored) { }
            Thread.sleep(250);
        }
        throw new AssertionError("V2 data service did not become ready: " + startupFailure(log, password));
    }
    private static String startupFailure(Path log, String password) throws Exception {
        var lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
        String causes = lines.stream()
                .filter(line -> line.contains("Caused by:") || line.contains("APPLICATION FAILED")
                        || line.contains("Error creating bean") || line.contains("Description:") || line.contains("required a bean") || line.contains("could not be found") || line.contains("already defined"))
                .map(line -> line.replace(password, "[redacted]"))
                .map(line -> line.substring(0, Math.min(line.length(), 300)))
                .limit(8).reduce((first, next) -> first + " | " + next).orElse("");
        if (!causes.isBlank()) return causes;
        return lines.stream().skip(Math.max(0, lines.size() - 8L))
                .map(line -> line.replace(password, "[redacted]"))
                .map(line -> line.substring(0, Math.min(line.length(), 300)))
                .reduce((first, next) -> first + " | " + next).orElse("no startup output logged");
    }
    private static HttpResponse<String> request(String base, String method, String path,
            Map<String, String> secrets, String keyId, String body, String idem) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer xj_" + keyId + "." + secrets.get(keyId));
        if (idem != null) builder.header("Idempotency-Key", idem);
        if (body != null) builder.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static void credential(ArrayNode items, String key, String principal, String kind,
                                   Map<String, String> secrets) throws Exception {
        ObjectNode item = items.addObject();
        item.put("keyId", key);
        item.put("secretSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secrets.get(key).getBytes(StandardCharsets.UTF_8))));
        item.put("principalRef", principal);
        item.put("organizationRef", "dev-con-isolated");
        item.put("kind", kind);
        item.put("expiresAt", "2027-03-24T00:00:00Z");
    }

    private static void stop(Process service) throws Exception {
        service.destroy();
        if (!service.waitFor(10, TimeUnit.SECONDS)) {
            service.destroyForcibly();
            if (!service.waitFor(10, TimeUnit.SECONDS)) {
                throw new AssertionError("V2 data service did not stop before restart");
            }
        }
    }
}
