package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialSyncRecordHttpScenarios {
    private MaterialSyncRecordHttpScenarios() { }

    static void run(MaterialPageHttpsSource server, HttpClient http, String base, String token,
                    String source, ObjectMapper json, JdbcTemplate db) throws Exception {
        int observations = count(db, "enf3_observation");
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        server.failNextRecord();
        String url = base + "/v2/enforcement/sources/" + source;
        var attempt = http.send(HttpRequest.newBuilder(URI.create(url + "/syncs"))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(attempt.statusCode()).as(attempt.body()).isEqualTo(200);
        var result = json.readTree(attempt.body());
        assertThat(result.get("state").asText()).isEqualTo("PARTIAL_FAILED");
        assertThat(result.get("failureCode").asText()).isEqualTo("INVALID_SOURCE_RECORD");
        String attemptId = result.get("attemptId").asText();
        var details = http.send(HttpRequest.newBuilder(URI.create(url + "/sync-attempts/" + attemptId + "/records"))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(details.statusCode()).as(details.body()).isEqualTo(200);
        var records = json.readTree(details.body());
        assertThat(records.size()).isEqualTo(1);
        assertThat(records.get(0).get("recordId").asText()).isEqualTo("OBS-001");
        assertThat(records.get(0).get("sourceVersion").asText()).isEqualTo("1");
        assertThat(records.get(0).get("beforeCursor").asText()).isEqualTo("p2-3");
        assertThat(records.get(0).get("state").asText()).isEqualTo("FAILED");
        assertThat(records.get(0).get("failureCode").asText()).isEqualTo("INVALID_SOURCE_RECORD");
        assertThat(details.body()).doesNotContain("专利产品");
        assertThat(db.queryForObject("SELECT cursor_value FROM enf3_sync_cursor WHERE source_id=?",
                String.class, source)).isEqualTo("p2-3");
        assertThat(count(db, "enf3_observation")).isEqualTo(observations);
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        System.out.println("ENF_V2_M01_RECORD_HTTP login=PKCE record=OBS-001@1 "
                + "failure=INVALID_SOURCE_RECORD cursor=p2-3 forbiddenReceipt=0");
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
