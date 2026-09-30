package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

final class DevHumanAcceptanceBrowserScenarios {
    static void workbench(Path root, String base, String secret, String receiverSecret, String jobSecret,
                          Path comparisonManifest, Path correctionManifest, JdbcTemplate sql) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", root.resolve(Path.of("scripts",
                "verify-consultation-v2-dev-browser.mjs")).toString());
        builder.directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("V2_BASE_URL", base);
        builder.environment().put("V2_HUMAN_TOKEN", "Bearer xj_human-a." + secret);
        builder.environment().put("V2_RECEIVER_TOKEN", "Bearer xj_human-b." + receiverSecret);
        builder.environment().put("V2_COMPARISON_MANIFEST", comparisonManifest.toString());
        builder.environment().put("V2_CORRECTION_MANIFEST", correctionManifest.toString());
        Process browser = builder.start();
        if (!browser.waitFor(90, TimeUnit.SECONDS)) {
            browser.destroyForcibly();
            throw new AssertionError("V2 browser development integration timed out");
        }
        String output = new String(browser.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(browser.exitValue()).as("Real browser and DM8 integration: " + output).isZero();
        String taskId = output.lines().filter(line -> line.startsWith("V2_BROWSER_TASK=")).findFirst()
                .orElseThrow(() -> new AssertionError("Browser task ID missing: " + output))
                .substring("V2_BROWSER_TASK=".length());
        assertThat(sql.queryForObject("SELECT state FROM con2_task WHERE task_id=?", String.class, taskId))
                .isEqualTo("CANCELLED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(1);
        var tasks = java.util.List.of(taskId, emitted(output, "V2_RETURN_TASK="),
                emitted(output, "V2_CORRECTION_TASK="));
        for (String task : tasks) {
            sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        }
        var reconcile = HttpRequest.newBuilder(URI.create(base + "/v2/internal/consultation/retention/reconcile"))
                .header("Authorization", "Bearer xj_job." + jobSecret)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var result = HttpClient.newHttpClient().send(reconcile, HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.body()).path("removed").asInt())
                .isEqualTo(tasks.size());
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isZero();
    }

    private static String emitted(String output, String prefix) {
        return output.lines().filter(line -> line.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("Browser task ID missing: " + prefix))
                .substring(prefix.length());
    }
}
