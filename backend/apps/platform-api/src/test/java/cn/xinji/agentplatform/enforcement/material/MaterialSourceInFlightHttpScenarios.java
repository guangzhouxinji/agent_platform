package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialSourceInFlightHttpScenarios {
    private MaterialSourceInFlightHttpScenarios() { }

    static void run(MaterialPageHttpsSource server, HttpClient http, String base, String token,
                    String source, JdbcTemplate db, Path files) throws Exception {
        String url = base + "/v2/enforcement/sources/" + source;
        int observations = count(db, "enf3_observation");
        int attempts = count(db, "enf3_sync_attempt");
        long fileCount;
        try (var paths = Files.walk(files)) { fileCount = paths.filter(Files::isRegularFile).count(); }
        server.pauseNextEmptyPage();
        var pending = http.sendAsync(request(url + "/syncs", token, "", "stop-in-flight"),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(server.awaitPausedPage()).isTrue();
            var stopped = http.send(request(url + "/configuration", token,
                    "{\"expectedVersion\":2,\"credentialRef\":\"m01-source\",\"enabled\":false}",
                    "stop-config-v3"), HttpResponse.BodyHandlers.ofString());
            assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
        } finally { server.resumePage(); }
        assertThat(pending.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(503);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE source_id=? "
                + "AND config_version=2 AND state='FAILED' AND failure_code='SOURCE_DISABLED'",
                Integer.class, source)).isEqualTo(1);
        assertThat(count(db, "enf3_sync_attempt")).isEqualTo(attempts + 1);
        assertThat(http.send(request(url + "/syncs", token, "", "stop-next"),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
        assertThat(count(db, "enf3_sync_attempt")).isEqualTo(attempts + 1);
        assertThat(count(db, "enf3_observation")).isEqualTo(observations);
        try (var paths = Files.walk(files)) {
            assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(fileCount);
        }
        var resumed = http.send(request(url + "/configuration", token,
                "{\"expectedVersion\":3,\"credentialRef\":\"m01-source\",\"enabled\":true}",
                "resume-config-v4"), HttpResponse.BodyHandlers.ofString());
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        System.out.println("ENF_V2_M01_STOP_HTTP login=PKCE inFlight=503 next=503 "
                + "attemptVersion=2 cursor=p2-3 observations=UNCHANGED files=UNCHANGED");
    }

    private static HttpRequest request(String url, String token, String body, String key) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
