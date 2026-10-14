package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling;
import java.util.Comparator;
import java.util.Map;

/** Pure original-command reconstruction; never supplies a current identity or permission. */
final class ProductHumanRequestCommands {
    private ProductHumanRequestCommands() { }

    static ProductHumanRequestApplication.Command fromManifest(ProductHumanRequestStore.Manifest manifest) {
        var refs = manifest.references();
        var comparison = refs.comparison() == null ? null : new ProductHumanComparisonReferences.Selection(
                refs.comparison().id(), refs.comparison().reports().stream().map(ProductHumanRequestReferences.Report::reportId).toList());
        var selections = refs.materials().stream().map(m -> new ProductHumanMaterialReferences.Selection(
                m.source().source().sourceId(), m.documentId(), m.documentVersion(), m.fieldLocators().keySet())).toList();
        return new ProductHumanRequestApplication.Command(manifest.taskVersion(), comparison, selections,
                manifest.reason(), manifest.purpose());
    }

    static ProductHumanRequestStore.Key key(String task, OrdinaryHumanHandling.Actor actor, String id,
            ProductHumanRequestApplication.Command command, ProductComparisonJson json) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Original request key required");
        var canonical = new java.util.LinkedHashMap<String, Object>();
        canonical.put("expectedTaskVersion", command.expectedTaskVersion()); canonical.put("reason", command.reason());
        canonical.put("purpose", command.purpose());
        canonical.put("comparison", command.comparison() == null ? null : Map.of("comparisonId", command.comparison().comparisonId(),
                "reportIds", command.comparison().reportIds().stream().sorted().toList()));
        canonical.put("materials", command.materials().stream().sorted(Comparator.comparing(ProductHumanMaterialReferences.Selection::sourceId)
                .thenComparing(ProductHumanMaterialReferences.Selection::documentId).thenComparing(ProductHumanMaterialReferences.Selection::documentVersion))
                .map(m -> Map.of("sourceId", m.sourceId(), "documentId", m.documentId(), "documentVersion", m.documentVersion(),
                        "fields", m.fields().stream().sorted().toList())).toList());
        return new ProductHumanRequestStore.Key(id, json.sha(Map.of("taskId", task, "actor", actor,
                "requestId", id, "command", canonical)));
    }
}
