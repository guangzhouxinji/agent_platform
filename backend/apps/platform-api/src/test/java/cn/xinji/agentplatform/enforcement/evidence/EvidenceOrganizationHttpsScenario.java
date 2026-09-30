package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;

final class EvidenceOrganizationHttpsScenario {
    private static final String ISSUER = "https://enf-evidence-http.example.org";
    private static final String ORG = "ORG-ENF-PROJECT";
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final JwtDecoder decoder;
    private final Path root;

    EvidenceOrganizationHttpsScenario(ProductGrants grants, ProductGrantWatermark watermark, JdbcTemplate db,
            ObjectMapper json, MaterialRepository materials, MaterialFiles files, JwtDecoder decoder, Path root) {
        this.grants = grants;
        this.watermark = watermark;
        this.db = db;
        this.json = json;
        this.materials = materials;
        this.files = files;
        this.decoder = decoder;
        this.root = root;
    }

    String run(HttpClient http, String base, String token, String member,
               String reviewerToken, String reviewer) throws Exception {
        var refs = prepareVerifiedMaterials();
        var caseInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("version", "1").put("caseType", "PROJECT_DOCUMENT_REVIEW")
                .put("organization", ORG).put("eventRef", "PROJECT-EVENT-001");
        var members = caseInput.putArray("materials");
        refs.forEach(ref -> members.addObject().put("id", ref.id()).put("version", ref.version())
                .put("sha256", ref.sha256()));
        json.writeValue(root.resolve("case.json").toFile(), caseInput);
        var current = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, decoder.decode(token), ISSUER, Clock.systemUTC());
        current.prepareSource("EVIDENCE-HTTP-SOURCE", 1);
        current.activateSource("EVIDENCE-HTTP-SOURCE", 1);
        issueExact(member, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE", 2);
        var organizeGrant = issueExact(member, "CASE", "PROJECT-CASE-001", 1,
                "ORGANIZE", "ANALYZE", 0);
        for (var ref : refs) {
            issueExact(member, "EVIDENCE", ref.id(), 3, "READ", "ANALYZE", 0);
            issueExact(member, "EVIDENCE", ref.id(), 3, "ANALYZE", "ANALYZE", 0);
        }
        var evidenceSet = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("caseVersion", "1");
        var exactRefs = evidenceSet.putArray("materials");
        refs.forEach(ref -> exactRefs.addObject().put("id", ref.id())
                .put("version", ref.version()).put("sha256", ref.sha256()));
        var organization = json.createObjectNode().put("id", "ORG-CASE-001")
                .put("ruleId", "RULE-001").put("ruleRevision", 2);
        organization.set("evidenceSet", evidenceSet);
        String url = base + "/v2/enforcement/evidence-organizations";
        String body = json.writeValueAsString(organization);
        assertThat(request(http, "POST", url, token, body, "organize-1").statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", url, token, body, "organize-1").statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class)).isEqualTo(1);
        var readRevisionOne = issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 1,
                "READ", "DISPLAY", 0);
        var view = request(http, "GET", url + "/ORG-CASE-001/versions/1", token, null, null);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(json.readTree(view.body()).path("matters").get(0).path("coverage").asText())
                .isEqualTo("CONFLICT");
        assertThat(json.readTree(view.body()).path("unrelated").get(0).asText())
                .isEqualTo(refs.get(2).id());
        assertThat(json.readTree(view.body()).path("links").toString()).contains("IRRELEVANT_CANDIDATE");
        var wrong = organization.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.path("evidenceSet")
                .path("materials").get(0)).put("sha256", "0".repeat(64));
        assertThat(request(http, "POST", url, token, json.writeValueAsString(wrong),
                "organize-bad").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(1);
        try (var entries = Files.list(root.resolve("files"))) {
            assertThat(entries.filter(path -> path.getFileName().toString().endsWith(".blob")).count())
                    .isEqualTo(3);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(3);
        var correctGrant = issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 1,
                "CORRECT", "ANALYZE", 0);
        var correction = json.createObjectNode().put("expectedVersion", 1)
                .put("matterId", "EVENT_TIME").put("evidenceId", refs.getFirst().id())
                .put("locator", "line 4").put("relation", "UNVERIFIED")
                .put("reason", "A and B event times conflict");
        String correctionUrl = url + "/ORG-CASE-001/revisions";
        String correctionBody = json.writeValueAsString(correction);
        assertThat(request(http, "POST", correctionUrl, token, correctionBody, "correct-1")
                .statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", correctionUrl, token, correctionBody, "correct-1")
                .statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class)).isEqualTo(2);
        watermark.change(() -> grants.revoke(readRevisionOne.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var correctedRead = issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 2,
                "READ", "DISPLAY", 2);
        var corrected = request(http, "GET", url + "/ORG-CASE-001/versions/2", token, null, null);
        assertThat(corrected.statusCode()).isEqualTo(200);
        assertThat(json.readTree(corrected.body()).path("links").toString()).contains("HUMAN_CORRECTION");
        issueExact(reviewer, "EVIDENCE_RULE", "RULE-001", 2, "READ_RULE", "RULE_MANAGEMENT", 0);
        issueExact(reviewer, "CASE", "PROJECT-CASE-001", 1, "READ", "ANALYZE", 0);
        ProductGrants.Receipt reviewerB = null;
        for (var ref : refs) {
            var exact = issueExact(reviewer, "EVIDENCE", ref.id(), 3, "READ", "ANALYZE", 0);
            if (ref.id().equals(refs.get(1).id())) reviewerB = exact;
        }
        assertThat(reviewerB).isNotNull();
        var reviewerRead = issueExact(reviewer, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 2,
                "READ", "DISPLAY", 0);
        issueExact(reviewer, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 2, "REVIEW", "REVIEW", 0);
        var review = json.createObjectNode().put("expectedVersion", 2).put("decision", "CONFIRMED")
                .put("reason", "Project method review of two distinct times");
        String reviewUrl = url + "/ORG-CASE-001/review";
        String reviewBody = json.writeValueAsString(review);
        assertThat(request(http, "POST", reviewUrl, reviewerToken, reviewBody, "review-1")
                .statusCode()).isEqualTo(200);
        assertThat(request(http, "POST", reviewUrl, reviewerToken, reviewBody, "review-1")
                .statusCode()).isEqualTo(200);
        watermark.change(() -> grants.revoke(reviewerRead.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        issueExact(reviewer, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 3, "READ", "DISPLAY", 2);
        var reviewed = request(http, "GET", url + "/ORG-CASE-001/versions/3", reviewerToken, null, null);
        assertThat(reviewed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reviewed.body()).path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(json.readTree(reviewed.body()).path("reviewedBy").asText()).isEqualTo(reviewer);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(3);
        var withdrawnB = reviewerB;
        watermark.change(() -> grants.revoke(withdrawnB.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "GET", url + "/ORG-CASE-001/versions/3", reviewerToken,
                null, null).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_event", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT review_state FROM enf4_organization "
                + "WHERE organization_id='ORG-CASE-001' AND revision=3", String.class)).isEqualTo("CONFIRMED");
        watermark.change(() -> grants.revoke(correctGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var excludeGrant = issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 3,
                "CORRECT", "ANALYZE", 2);
        var exclusion = json.createObjectNode().put("expectedVersion", 3)
                .put("evidenceId", refs.get(1).id()).put("reason", "Exclude B after conflicting time review");
        String exclusionUrl = url + "/ORG-CASE-001/exclusions";
        String exclusionBody = json.writeValueAsString(exclusion);
        var firstExclusion = request(http, "POST", exclusionUrl, token, exclusionBody, "exclude-b");
        if (firstExclusion.statusCode() != 200)
            throw new AssertionError("exclude first command status=" + firstExclusion.statusCode());
        var replayExclusion = request(http, "POST", exclusionUrl, token, exclusionBody, "exclude-b");
        if (replayExclusion.statusCode() != 200)
            throw new AssertionError("exclude replay status=" + replayExclusion.statusCode());
        watermark.change(() -> grants.revoke(correctedRead.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        var rerunRead = issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 4,
                "READ", "DISPLAY", 4);
        var rerun = json.readTree(request(http, "GET", url + "/ORG-CASE-001/versions/4",
                token, null, null).body());
        assertThat(rerun.path("state").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(rerun.path("evidenceSet").toString()).doesNotContain(refs.get(1).id());
        assertThat(rerun.path("links").toString()).doesNotContain(refs.get(1).id());
        assertThat(rerun.path("matters").toString()).contains("PRESENT").doesNotContain("CONFLICT");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(3);
        var excludedOriginal = materials.material(refs.get(1).id()).orElseThrow();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(files.read(excludedOriginal.fileKey())))).isEqualTo(refs.get(1).sha256());
        try (var entries = Files.list(root.resolve("files"))) {
            assertThat(entries.filter(path -> path.getFileName().toString().endsWith(".blob")).count())
                    .isEqualTo(3);
        }
        issueExact(reviewer, "EVIDENCE", refs.get(1).id(), 3, "READ", "ANALYZE", 2);
        assertThat(json.readTree(request(http, "GET", url + "/ORG-CASE-001/versions/3",
                reviewerToken, null, null).body()).path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(4);
        watermark.change(() -> grants.revoke(excludeGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", exclusionUrl, token, exclusionBody, "exclude-b")
                .statusCode()).isEqualTo(403);
        assertThat(request(http, "POST", correctionUrl, token, correctionBody, "correct-1")
                .statusCode()).isEqualTo(403);
        watermark.change(() -> grants.revoke(organizeGrant.grantId(), 1, "enf-evidence-maintainer",
                UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(request(http, "POST", url, token, body, "organize-1").statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(4);
        EvidenceBrowserE2e.run(base, () -> watermark.change(() -> grants.revoke(rerunRead.grantId(), 1,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64))));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_event", Integer.class)).isEqualTo(4);
        issueExact(member, "EVIDENCE_ORGANIZATION", "ORG-CASE-001", 2,
                "READ", "DISPLAY", 6);
        return url;
    }

    private List<EvidenceTrialSource.Ref> prepareVerifiedMaterials() throws Exception {
        MaterialAccess setupAccess = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                                          long version, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) { }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var service = new MaterialProductService(materials, files, setupAccess, Clock.systemUTC());
        String actor = "enf-evidence-operator";
        service.registerSource(actor, "http-source", new MaterialProductService.SourceInput(
                "EVIDENCE-HTTP-SOURCE", ORG, "PROJECT-CASE-001", 1,
                Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"),
                "approved-http-source"));
        var positive = service.observe(actor, "http-positive", new MaterialProductService.ObservationInput(
                "EVIDENCE-HTTP-SOURCE", "POS", "1", "record positive", null));
        var negative = service.observe(actor, "http-negative", new MaterialProductService.ObservationInput(
                "EVIDENCE-HTTP-SOURCE", "NEG", "1", "missing", null));
        service.draftRule(actor, "http-discovery-draft", new MaterialProductService.RuleInput(
                "EVIDENCE-HTTP-DISCOVERY", "EVIDENCE-HTTP-SOURCE", "record", "project-method"));
        service.ruleState(actor, "http-discovery-publish", "EVIDENCE-HTTP-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var collection = new MaterialCollectionService(materials, files, setupAccess, Clock.systemUTC());
        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        for (String label : List.of("a", "b", "c")) {
            var observed = service.observe(actor, "http-observe-" + label,
                    new MaterialProductService.ObservationInput("EVIDENCE-HTTP-SOURCE", "RECORD-" + label,
                            "1", "record " + label, null));
            var clue = service.discover(actor, "http-clue-" + label,
                    observed.resultId(), "EVIDENCE-HTTP-DISCOVERY");
            byte[] bytes = Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1",
                    "record-" + label + ".txt"));
            var registered = service.registerMaterial(actor, "http-material-" + label,
                    new MaterialProductService.MaterialInput(clue.resultId(), bytes,
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collection.attest(actor, "http-collect-" + label, registered.resultId(), 1,
                    "RECORD-" + label, "original collected");
            collection.verify("named-evidence-verifier", "http-verify-" + label,
                    registered.resultId(), 2, "digest checked");
            var saved = materials.material(registered.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(saved.id(), "3", saved.sha256()));
        }
        return refs;
    }

    private ProductGrants.Receipt issueExact(String member, String type, String id, long version,
                                             String action, String purpose, long expectedEpoch) {
        var receipt = watermark.change(() -> grants.issue(new ProductGrants.Scope(ISSUER,
                member, ORG, "ENFORCEMENT", "ENF-V2-EVIDENCE", "ENF-EVIDENCE-V2-ACCEPTANCE-01",
                type, id, version, action, purpose), Instant.now().plusSeconds(3600), expectedEpoch,
                "enf-evidence-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
        assertThat(receipt.status()).isEqualTo("ACTIVE");
        return receipt;
    }

    private static HttpResponse<String> request(HttpClient http, String method, String url,
            String token, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
