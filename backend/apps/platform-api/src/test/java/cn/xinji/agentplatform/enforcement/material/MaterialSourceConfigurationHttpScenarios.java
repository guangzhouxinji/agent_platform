package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialSourceConfigurationHttpScenarios {
    private MaterialSourceConfigurationHttpScenarios() { }

    static void run(HttpClient http, String base, String token, String source,
                    ObjectMapper json, JdbcTemplate db) throws Exception {
        String url = base + "/v2/enforcement/sources/" + source;
        var first = get(http, url, token);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).get("configVersion").asLong()).isEqualTo(1);
        assertThat(first.body()).contains(MaterialPageHttpsSource.CREDENTIAL_REF)
                .doesNotContain("m01-private-source-token");
        String body = json.writeValueAsString(new MaterialProductService.SourceConfigurationInput(1,
                MaterialPageHttpsSource.CREDENTIAL_REF, true));
        var updated = post(http, url + "/configuration", token, body, "config-http-v2");
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("resultVersion").asLong()).isEqualTo(2);
        int receipts = db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class);
        int events = db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class);
        assertThat(post(http, url + "/configuration", token, body, "config-http-stale").statusCode())
                .isEqualTo(409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class)).isEqualTo(receipts);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class)).isEqualTo(events);
        assertThat(json.readTree(get(http, url, token).body()).get("configVersion").asLong()).isEqualTo(2);
        var sync = post(http, url + "/syncs", token, "", "config-http-sync");
        assertThat(sync.statusCode()).as(sync.body()).isEqualTo(200);
        assertThat(json.readTree(sync.body()).get("processed").asInt()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE source_id=? "
                + "AND config_version=2 AND state='SUCCEEDED'", Integer.class, source)).isEqualTo(1);
        System.out.println("ENF_V2_M01_CONFIG_HTTP login=PKCE revision=1->2 stale=409 "
                + "attemptVersion=2 forbiddenReceipt=0");
    }

    private static HttpResponse<String> get(HttpClient http, String url, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String url, String token,
                                             String body, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
