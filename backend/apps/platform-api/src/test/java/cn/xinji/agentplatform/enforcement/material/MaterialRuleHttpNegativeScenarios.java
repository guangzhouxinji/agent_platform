package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class MaterialRuleHttpNegativeScenarios {
    private MaterialRuleHttpNegativeScenarios() { }

    static void run(HttpClient http, String base, String token, String actor, String sourceText,
                    ObjectMapper json, JdbcTemplate db, ProductGrants grants,
                    ProductGrantWatermark watermark, String ruleId) throws Exception {
        String source = "PROJECT-DEV-HTTP-OTHER";
        grant(grants, watermark, actor, "ENFORCEMENT_SOURCE", source, "CONFIGURE", "SOURCE_MANAGEMENT");
        grant(grants, watermark, actor, "ENFORCEMENT_SOURCE", source, "SYNC", "SAVE");
        var sourceBody = json.createObjectNode().put("id", source).put("organization", "ENF-HTTP-DEV")
                .put("rightsRef", "docs/02#40").put("epoch", 1)
                .put("credentialRef", MaterialPageHttpsSource.CREDENTIAL_REF)
                .put("expiresAt", Instant.now().plusSeconds(3600).toString());
        sourceBody.putArray("uses").add("QUERY").add("SAVE").add("DISPLAY");
        assertThat(post(http, base + "/v2/enforcement/sources", json.writeValueAsString(sourceBody),
                token, "other-http-source").statusCode()).isEqualTo(200);
        var missing = json.createObjectNode().put("sourceId", source).put("recordId", "OBS-MISSING")
                .put("sourceVersion", "1").put("text", "");
        assertThat(post(http, base + "/v2/enforcement/observations", json.writeValueAsString(missing),
                token, "other-http-missing").statusCode()).isEqualTo(409);
        var cross = json.createObjectNode().put("sourceId", source).put("recordId", "OBS-CROSS")
                .put("sourceVersion", "1").put("text", sourceText);
        var saved = post(http, base + "/v2/enforcement/observations", json.writeValueAsString(cross),
                token, "other-http-cross");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        String observationId = json.readTree(saved.body()).get("resultId").asText();
        grant(grants, watermark, actor, "OBSERVATION", observationId, "READ", "DISPLAY");
        int receipts = count(db, "enf3_receipt");
        int events = count(db, "enf3_event");
        int clues = count(db, "enf3_clue");
        String trial = base + "/v2/enforcement/discovery-rules/" + ruleId + "/trial/" + observationId;
        var result = get(http, trial, token);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(json.readTree(result.body()).get("outcome").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(watermark.independentDatabase().update(
                "UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?", source)).isEqualTo(1);
        var withdrawn = get(http, trial, token);
        assertThat(withdrawn.statusCode()).isEqualTo(403);
        assertThat(withdrawn.body()).doesNotContain(sourceText);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='other-http-missing'",
                Integer.class)).isZero();
        assertThat(count(db, "enf3_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf3_event")).isEqualTo(events);
        assertThat(count(db, "enf3_clue")).isEqualTo(clues);
        System.out.printf("ENF_V2_M02_HTTP cross=NOT_APPLICABLE missing=409 withdrawn=403 "
                + "receipts=%d events=%d clues=%d%n", receipts, events, clues);
    }

    private static HttpResponse<String> post(HttpClient http, String url, String body,
                                             String token, String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient http, String url, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String actor,
                              String type, String id, String action, String purpose) {
        watermark.change(() -> grants.issue(new ProductGrants.Scope("https://enf-http.example.org", actor,
                "ENF-HTTP-DEV", "ENFORCEMENT", "ENF-V2-MATERIAL", "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                type, id, 1, action, purpose), Instant.now().plusSeconds(3600), 0,
                "enf-http-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
