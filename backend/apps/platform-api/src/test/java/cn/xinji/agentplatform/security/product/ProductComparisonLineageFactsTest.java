package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real original/graph/JSON projection; no authentication claim, DB, DDL or model invocation. */
class ProductComparisonLineageFactsTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ProductComparisonJson json = new ProductComparisonJson(mapper);
    final ProductComparisonMatrixCalculation calculation = new ProductComparisonMatrixCalculation(json);
    final ProductComparisonLineageFacts producer = new ProductComparisonLineageFacts(json, calculation);
    final String task = "11111111-1111-4111-8111-111111111111", message = "22222222-2222-4222-8222-222222222222";
    final Instant at = Instant.parse("2026-10-11T00:00:00Z");

    @Test void capturedFactsBindExistingRequestAndContainNoOriginalTextOrGrant() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.", List.of(1)); var config = configuration("model", "https://component.invalid/v1");
        var result = producer.capture(loaded, config, requestSha(loaded, config));
        assertThat(result.requestSha()).isEqualTo(requestSha(loaded, config));
        assertThat(result.snapshot().expectedRows()).containsExactly(new RowKey(loaded.candidate().material().key(), List.of(1), "F1"));
        assertThat(result.snapshot().facts()).hasSize(7);
        assertThat(result.snapshot().facts().values()).allMatch(value -> value.matches("[a-f0-9]{64}"));
        assertThat(json.canonical(result.snapshot())).doesNotContain("confirmed feature", "A valve has a sensor", "Bearer", "authority");
    }
    @Test void alternativeClaimBranchesAreKeptInsteadOfRejectingRepeatedSelectedClaim() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.\n2. A valve has a spring.\n3. A valve according to any one of claims 1 or 2 has a switch.", List.of(3));
        var config = configuration("model", "https://component.invalid/v1");
        assertThat(producer.capture(loaded, config, requestSha(loaded, config)).snapshot().expectedRows())
                .containsExactlyInAnyOrder(new RowKey(loaded.candidate().material().key(), List.of(1, 3), "F1"),
                        new RowKey(loaded.candidate().material().key(), List.of(2, 3), "F1"));
    }
    @Test void featureUserCorrectionChangesFactAndRealRequestNotOnlyEnvelopeCoordinates() throws Exception {
        var before = materials("confirmed feature", "1. A valve has a sensor.", List.of(1));
        var after = materials("corrected feature", "1. A valve has a sensor.", List.of(1)); var config = configuration("model", "https://component.invalid/v1");
        assertThat(producer.capture(before, config, requestSha(before, config)).snapshot().facts().get(new FactKey(Type.FEATURE, "F1")))
                .isNotEqualTo(producer.capture(after, config, requestSha(after, config)).snapshot().facts().get(new FactKey(Type.FEATURE, "F1")));
        assertThat(requestSha(before, config)).isNotEqualTo(requestSha(after, config));
        assertThatThrownBy(() -> producer.capture(after, config, requestSha(before, config))).hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
    }
    @Test void sameConfigurationVersionDoesNotHideEndpointOrModelChange() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.", List.of(1));
        var old = configuration("model", "https://component.invalid/v1"); var changed = configuration("model", "https://component.invalid/v2");
        assertThat(old.version()).isEqualTo(changed.version());
        assertThat(producer.capture(loaded, old, requestSha(loaded, old)).snapshot().facts().get(new FactKey(Type.CONFIGURATION, "global")))
                .isNotEqualTo(producer.capture(loaded, changed, requestSha(loaded, changed)).snapshot().facts().get(new FactKey(Type.CONFIGURATION, "global")));
        assertThatThrownBy(() -> producer.capture(loaded, configuration("other-model", "https://component.invalid/v1"), requestSha(loaded, old)))
                .hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
    }
    @Test void changedTargetAndUnboundInputHashFailBeforeReturningFacts() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.", List.of(1)); var config = configuration("model", "https://component.invalid/v1");
        var changedTarget = new ProductTechnicalSchemeOriginal(task, message, "changed target", ProductComparisonJson.hash("changed target"), 4096);
        var changed = new ProductComparisonWorkerMaterials.Loaded(loaded.input(), changedTarget, null, loaded.candidate(), null, loaded.expiresAt());
        assertThatThrownBy(() -> producer.capture(changed, config, requestSha(loaded, config))).hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
        var input = new ProductComparisonInputs.View(loaded.input().input(), "f".repeat(64), loaded.input().candidateProgress());
        var unbound = new ProductComparisonWorkerMaterials.Loaded(input, loaded.target(), null, loaded.candidate(), null, loaded.expiresAt());
        assertThatThrownBy(() -> producer.capture(unbound, config, requestSha(loaded, config))).hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
    }
    @Test void candidateOriginalDriftCannotBorrowPreparedFingerprint() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.", List.of(1));
        var other = materials("confirmed feature", "1. A valve has a spring.", List.of(1)); var config = configuration("model", "https://component.invalid/v1");
        var drift = new ProductComparisonWorkerMaterials.Loaded(loaded.input(), loaded.target(), null, other.candidate(), null, loaded.expiresAt());
        assertThatThrownBy(() -> producer.capture(drift, config, requestSha(loaded, config))).hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
    }
    @Test void storedBranchSpanDriftFailsEvenWithReboundMaterialAndEnvelopeHashes() throws Exception {
        var loaded = materials("confirmed feature", "1. A valve has a sensor.", List.of(1));
        var old = loaded.candidate().material();
        var changed = new MaterialDocument(old.key(), old.sourceId(), old.documentId(), old.documentVersion(), old.sourceVersion(),
                old.rawSha(), old.originalRef(), old.jsonPointer(), List.of(new Branch(1, List.of(new Claim(1, List.of(),
                        Span.of(loaded.candidate().original().evidence(0, 1)))))), "");
        var tree = mapper.readTree(loaded.input().input().currentPreparationSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("material")).set("candidates", mapper.valueToTree(List.of(changed)));
        var encoded = mapper.valueToTree(loaded.input().input());
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).put("currentPreparationSnapshot", json.canonical(tree));
        ((com.fasterxml.jackson.databind.node.ObjectNode) encoded).put("materialSha", json.sha(tree.path("material")));
        var input = json.stored(json.canonical(encoded), ProductComparisonInputs.Envelope.class);
        var drift = new ProductComparisonWorkerMaterials.Loaded(new ProductComparisonInputs.View(input, json.sha(input), loaded.input().candidateProgress()),
                loaded.target(), null, new ProductComparisonDocuments.Loaded(loaded.candidate().reference(), loaded.candidate().original(), changed, null), null, loaded.expiresAt());
        var config = configuration("model", "https://component.invalid/v1");
        assertThatThrownBy(() -> producer.capture(drift, config, requestSha(drift, config)))
                .hasMessage("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID");
    }
    private String requestSha(ProductComparisonWorkerMaterials.Loaded loaded, ModelGateway.Configuration config) {
        return ProductComparisonJson.hash(calculation.request(loaded, config));
    }
    private ModelGateway.Configuration configuration(String model, String endpoint) {
        return new ModelGateway.Configuration("component-config", "same-version", "component-provider", model, URI.create(endpoint),
                "OPENAI_CHAT_COMPLETIONS", Duration.ofSeconds(30), 65536, 65536, Set.of("PATENT_COMPARISON"), Set.of("APPROVED_COMPARISON_ORIGINALS"));
    }
    private ProductComparisonWorkerMaterials.Loaded materials(String userText, String claims, List<Integer> selection) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(Map.of("claims", claims)); String rawSha = ProductCapturedArchive.digest(raw), version = "RAW_SHA256:" + rawSha;
        var original = new ProductPatentClaimOriginal("component/claim-original.json", raw, rawSha, version, "claims", mapper);
        var target = new ProductTechnicalSchemeOriginal(task, message, "A valve with a sensor.", ProductComparisonJson.hash("A valve with a sensor."), 4096);
        var graph = PatentClaimGraph.parse(original);
        var branches = selection.stream().flatMap(n -> graph.branches(n, 4000).stream()).map(branch -> new Branch(branch.selectedClaim(),
                branch.inheritedClaims().stream().map(claim -> new Claim(claim.number(), claim.alternatives(), Span.of(claim.original()))).toList())).toList();
        var ref = new DocumentRef("component-source", "component-document", version, selection, null, null, null, null);
        String key = ProductComparisonJson.hash(ref.sourceId() + "\n" + ref.documentId() + "\n" + ref.documentVersion());
        var document = new MaterialDocument(key, ref.sourceId(), ref.documentId(), version, "component-source-version", rawSha,
                "component/claim-original.json", "/claims", branches, "");
        var targetSpan = Span.of(target.evidence(0, target.text().length()));
        var prepare = new Prepare(1, "goal", 1, "input", "TECHNICAL_SIMILARITY", new Target(message, null), List.of(ref));
        var material = Map.of("target", targetSpan, "candidates", List.of(document));
        String snapshot = json.canonical(Map.of("taskId", task, "taskVersion", 1, "command", prepare, "material", material));
        var feature = new Feature("F1", 0, 0, target.text().length(), userText, null, null, targetSpan);
        var revision = new Revision("revision", task, 1, null, "preparation", ProductComparisonJson.hash(snapshot), targetSpan.originalSha(),
                "component-issuer", "component-subject", "feature-key", "a".repeat(64), "component confirmation", at, at.plusSeconds(3600), List.of(feature));
        var featureView = new FeatureView(revision, json.sha(revision));
        var create = new ProductComparisonInputs.Create(1, revision.revisionId(), 1, featureView.packageSha(), null, "PATENT_FEATURE_MATRIX_V1");
        var envelope = new ProductComparisonInputs.Envelope("comparison", task, 1, null, create, featureView, "preparation", json.sha(material), snapshot,
                "component-issuer", "component-subject", "input-key", "a".repeat(64), at, at.plusSeconds(3600), List.of(new ProductComparisonInputs.Candidate(key, 0)));
        var input = new ProductComparisonInputs.View(envelope, json.sha(envelope), List.of(new ProductComparisonInputs.Progress(key, "RUNNING")));
        return new ProductComparisonWorkerMaterials.Loaded(input, target, null, new ProductComparisonDocuments.Loaded(ref, original, document, null), null, at.plusSeconds(3600));
    }
}
