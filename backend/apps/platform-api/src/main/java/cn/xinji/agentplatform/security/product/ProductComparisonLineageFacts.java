package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** New-computation material projection only; not persisted proof, current authority or a historical upgrade. */
final class ProductComparisonLineageFacts {
    record Captured(Snapshot snapshot, String requestSha) { }
    private final ProductComparisonJson json;
    private final ProductComparisonMatrixCalculation calculation;
    ProductComparisonLineageFacts(ProductComparisonJson json, ProductComparisonMatrixCalculation calculation) {
        this.json = json; this.calculation = calculation;
    }
    Captured capture(ProductComparisonWorkerMaterials.Loaded loaded, ModelGateway.Configuration configuration, String requestSha) {
        var view = loaded.input(); var input = view.input(); var revision = input.confirmedFeatures().revision();
        var prepared = json.stored(input.currentPreparationSnapshot(), JsonNode.class);
        var material = prepared.path("material");
        var command = json.stored(json.canonical(prepared.path("command")), Prepare.class);
        if (!view.inputSha().equals(json.sha(input)) || !input.materialSha().equals(json.sha(material))
                || !input.taskId().equals(prepared.path("taskId").asText()) || input.taskVersion() != prepared.path("taskVersion").asLong()
                || !input.taskId().equals(revision.taskId()) || command.expectedTaskVersion() != input.taskVersion()
                || !input.command().featureRevisionId().equals(revision.revisionId())
                || input.command().featureRevisionNumber() != revision.revisionNumber()
                || !input.command().packageSha().equals(input.confirmedFeatures().packageSha())
                || !input.confirmedFeatures().packageSha().equals(json.sha(revision))
                || !Objects.equals(input.previousComparisonId(), input.command().previousComparisonId())
                || !"PATENT_FEATURE_MATRIX_V1".equals(input.command().methodVersion())) throw invalid();
        var target = Span.of(loaded.target().evidence(0, loaded.target().text().length()));
        if (!revision.targetSha().equals(target.originalSha())
                || !target.equals(json.stored(json.canonical(material.path("target")), Span.class))) throw invalid();
        if (!material.path("candidates").isArray() || material.path("candidates").size() > 20
                || revision.features().isEmpty() || revision.features().size() > 200
                || input.candidates().size() > 20 || command.candidates().size() > 20) throw invalid();
        var documents = json.stored(json.canonical(material.path("candidates")), MaterialDocument[].class);
        Map<String, MaterialDocument> candidates = new HashMap<>();
        long rowCount = 0;
        for (var document : documents) {
            if (document == null || document.gap() == null || !document.gap().isEmpty()
                    || document.branches() == null || document.branches().isEmpty()
                    || candidates.putIfAbsent(document.key(), document) != null) throw invalid();
            rowCount += (long) document.branches().size() * revision.features().size();
            if (rowCount > 4000) throw invalid();
        }
        Set<String> inputKeys = new HashSet<>(), commandKeys = new HashSet<>();
        for (var candidate : input.candidates()) if (!inputKeys.add(candidate.candidateId())) throw invalid();
        for (var ref : command.candidates()) {
            String key = ProductComparisonJson.hash(ref.sourceId() + "\n" + ref.documentId() + "\n" + ref.documentVersion());
            if (!commandKeys.add(key)) throw invalid();
            var document = candidates.get(key);
            if (document == null || !document.sourceId().equals(ref.sourceId()) || !document.documentId().equals(ref.documentId())
                    || !document.documentVersion().equals(ref.documentVersion())
                    || ref.claimNumbers().size() != new HashSet<>(ref.claimNumbers()).size()
                    || !document.branches().stream().map(Branch::selectedClaim).collect(java.util.stream.Collectors.toSet())
                            .equals(new HashSet<>(ref.claimNumbers()))) throw invalid();
        }
        if (!inputKeys.equals(candidates.keySet()) || !commandKeys.equals(inputKeys)
                || !loaded.candidate().material().equals(candidates.get(loaded.candidate().material().key()))) throw invalid();
        var current = calculation.snapshot(loaded);
        var actualBranches = current.candidates().getFirst().branches().stream().map(branch -> new Branch(branch.selectedClaim(),
                branch.inheritedClaims().stream().map(claim -> new Claim(claim.number(), claim.alternatives(), Span.of(claim.original()))).toList())).toList();
        if (!actualBranches.equals(loaded.candidate().material().branches())) throw invalid();
        Map<FactKey, String> facts = new HashMap<>();
        facts.put(new FactKey(Type.TARGET, "global"), json.sha(target));
        facts.put(new FactKey(Type.GOAL, "global"), json.sha(Map.of("goalId", command.goalId(), "goalRevision", command.goalRevision(), "inputId", command.inputId())));
        facts.put(new FactKey(Type.PURPOSE, "global"), json.sha(command.purpose()));
        facts.put(new FactKey(Type.METHOD, "global"), json.sha(input.command().methodVersion()));
        facts.put(new FactKey(Type.CONFIGURATION, "global"), configurationSha(configuration));
        Set<RowKey> rows = new HashSet<>();
        for (var feature : revision.features())
            if (facts.putIfAbsent(new FactKey(Type.FEATURE, feature.featureId()), json.sha(feature)) != null) throw invalid();
        for (var document : candidates.values()) {
            facts.put(new FactKey(Type.CANDIDATE, document.key()), json.sha(document));
            for (var branch : document.branches()) for (var feature : revision.features()) {
                var path = branch.inheritedClaims().stream().map(Claim::number).toList();
                if (path.isEmpty() || path.getLast() != branch.selectedClaim()
                        || rows.size() >= 4000 || !rows.add(new RowKey(document.key(), path, feature.featureId()))) throw invalid();
            }
        }
        Set<RowKey> verified = new HashSet<>();
        for (var candidate : current.candidates()) for (var branch : candidate.branches()) for (var feature : current.confirmedFeatures())
            verified.add(new RowKey(candidate.id(), branch.inheritedClaims().stream().map(c -> c.number()).toList(), feature.id()));
        Set<RowKey> selected = new HashSet<>();
        for (RowKey row : rows) if (row.candidateId().equals(loaded.candidate().material().key())) selected.add(row);
        if (!selected.equals(verified) || !ProductComparisonJson.hash(calculation.request(loaded, configuration)).equals(requestSha)) throw invalid();
        return new Captured(new Snapshot(input.taskId(), input.comparisonId(), view.inputSha(), input.previousComparisonId(), facts, rows), requestSha);
    }
    private String configurationSha(ModelGateway.Configuration config) {
        return ProductComparisonJson.hash(configurationJson(config));
    }
    String configurationJson(ModelGateway.Configuration config) {
        return json.canonical(Map.ofEntries(Map.entry("id", config.id()), Map.entry("version", config.version()),
                Map.entry("provider", config.provider()), Map.entry("model", config.model()), Map.entry("endpoint", config.endpoint().toString()),
                Map.entry("protocol", config.protocol()), Map.entry("timeout", config.timeout().toString()),
                Map.entry("maxInputBytes", config.maxInputBytes()), Map.entry("maxResponseBytes", config.maxResponseBytes()),
                Map.entry("purposes", config.purposes().stream().sorted().toList()), Map.entry("dataClasses", config.dataClasses().stream().sorted().toList())));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("LINEAGE_COMPUTATION_MATERIAL_BINDING_INVALID"); }
}
