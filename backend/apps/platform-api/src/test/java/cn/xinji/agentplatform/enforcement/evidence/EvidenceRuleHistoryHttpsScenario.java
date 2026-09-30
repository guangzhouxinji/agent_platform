package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class EvidenceRuleHistoryHttpsScenario {
    private static final String ISSUER = "https://enf-evidence-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    EvidenceRuleHistoryHttpsScenario(ProductGrants grants, ProductGrantWatermark watermark,
            JdbcTemplate db, ObjectMapper json) {
        this.grants = grants;
        this.watermark = watermark;
        this.db = db;
        this.json = json;
    }

    void run(HttpClient http, String base, String token, String member, JsonNode input,
            String reviewBody, String publishUrl, String publishedUrl, String oldReadGrant)
            throws Exception {
        issue(member, 3, "EDIT_RULE", 2);
        var nextDraft = input.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextDraft.path("matters").get(0))
                .put("expectedMaterial", "line-prefix:The submitted item is labeled");
        assertThat(json.readTree(request(http, "POST", base + "/v2/enforcement/evidence-rules", token,
                json.writeValueAsString(nextDraft), "draft-3").body()).path("revision").asLong())
                .isEqualTo(3);
        revoke(oldReadGrant);
        String draftRead = issue(member, 3, "READ_RULE", 4);
        assertThat(json.readTree(request(http, "GET", base + "/v2/enforcement/evidence-rules/RULE-001/versions/3",
                token, null, null).body()).path("state").asText()).isEqualTo("DRAFT");
        assertThat(request(http, "GET", base + "/v2/enforcement/evidence-rules/RULE-001/versions/3/publication",
                token, null, null).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isEqualTo(1);
        System.out.println("ENF_V2_EVIDENCE_RULE_HISTORY published-v2=immutable draft-v3=separate publications=1");
        issue(member, 3, "PUBLISH_RULE", 2);
        var nextReview = ((com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(reviewBody))
                .put("reviewRef", "REVIEW-002");
        assertThat(json.readTree(request(http, "POST", publishUrl, token,
                json.writeValueAsString(nextReview), "publish-4").body()).path("revision").asLong())
                .isEqualTo(4);
        revoke(draftRead);
        String currentRead = issue(member, 4, "READ_RULE", 6);
        assertThat(json.readTree(request(http, "GET", base + "/v2/enforcement/evidence-rules/RULE-001/versions/4",
                token, null, null).body()).path("state").asText()).isEqualTo("ACTIVE");
        assertThat(json.readTree(request(http, "GET", base + "/v2/enforcement/evidence-rules/RULE-001/versions/4/publication",
                token, null, null).body()).path("reviewRef").asText()).isEqualTo("REVIEW-002");
        revoke(currentRead);
        issue(member, 2, "READ_RULE", 8);
        assertThat(json.readTree(request(http, "GET", publishedUrl, token, null, null).body())
                .path("state").asText()).isEqualTo("DISABLED");
        assertThat(json.readTree(request(http, "GET", publishedUrl + "/publication", token, null, null)
                .body()).path("reviewRef").asText()).isEqualTo("REVIEW-001");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(5);
        System.out.println("ENF_V2_EVIDENCE_RULE_HISTORY published-v2=unchanged published-v4=ACTIVE publications=2 receipts=5");
    }

    private String issue(String member, long revision, String action, long expectedVersion) {
        var grant = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                member, ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE",
                "ENF-EVIDENCE-V2-ACCEPTANCE-01", "EVIDENCE_RULE", "RULE-001", revision,
                action, "RULE_MANAGEMENT"), Instant.now().plusSeconds(3600), expectedVersion,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(grant.status()).isEqualTo("ACTIVE");
        return grant.grantId();
    }

    private void revoke(String grantId) {
        watermark.change(() -> grants.revoke(grantId, 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
    }

    private static HttpResponse<String> request(HttpClient client, String method, String url,
            String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
