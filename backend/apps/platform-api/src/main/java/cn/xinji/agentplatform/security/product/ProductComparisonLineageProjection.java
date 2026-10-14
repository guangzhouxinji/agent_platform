package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** Read-only references, never copied machine results, execution permits or model dispatch. */
final class ProductComparisonLineageProjection {
    record View(String taskId, String comparisonId, String inputSha, String previousComparisonId, String parentInputSha,
                String configurationVersion, String configurationSha, Instant expiresAt, List<RowKey> expectedRows,
                List<RowKey> invalidated, List<RowKey> recompute, List<CarryForwardFrom> carried,
                List<ProductComparisonParentEvidenceReader.Ancestor> ancestry) { }
    private final ProductComparisonParentEvidenceReader parents;
    private final ProductComparisonModelAdmission metadata;
    private final ProductComparisonLineageFacts facts;
    private final ProductComparisonMatrixCalculation calculation;
    ProductComparisonLineageProjection(ProductComparisonParentEvidenceReader parents, ProductComparisonModelAdmission metadata,
            ProductComparisonLineageFacts facts, ProductComparisonMatrixCalculation calculation) {
        this.parents=parents; this.metadata=metadata; this.facts=facts; this.calculation=calculation;
    }
    View read(String task, String comparison, Jwt jwt) {
        return parents.project(task,comparison,jwt,(e,materials,until) -> {
            var approval=binding(e.current());
            Snapshot after=null;
            for (var candidate:materials.candidates()) {
                var loaded=new ProductComparisonWorkerMaterials.Loaded(e.current(),materials.target(),materials.targetPatent(),candidate,null,materials.expiresAt());
                var snapshot=facts.capture(loaded,approval.configuration(),ProductComparisonJson.hash(calculation.request(loaded,approval.configuration()))).snapshot();
                if (after!=null && !after.equals(snapshot)) throw unavailable();
                after=snapshot;
            }
            if (after==null) throw unavailable();
            String configSha=ProductComparisonJson.hash(facts.configurationJson(approval.configuration()));
            if (!configSha.equals(after.facts().get(new FactKey(Type.CONFIGURATION,"global")))) throw unavailable();
            Instant expiry=until.isBefore(approval.expiresAt())?until:approval.expiresAt();
            var view=aggregate(e,after,approval.configuration().version(),configSha,expiry);
            return new ProductComparisonParentEvidenceReader.Checked<>(view,expiry,() -> {
                if (!approval.equals(binding(e.current()))) throw unavailable();
            });
        });
    }
    ProductComparisonModelAdmission.Approval binding(ProductComparisonInputs.View input) {
        var a=metadata.approval();
        Set<String> candidates=new HashSet<>();
        for (var c:input.input().candidates()) if (!candidates.add(c.candidateId())) throw unavailable();
        if (!a.taskId().equals(input.input().taskId()) || !a.inputSha().equals(input.inputSha()) || !a.candidates().equals(candidates)
                || !"https".equalsIgnoreCase(a.configuration().endpoint().getScheme())
                || !"OPENAI_CHAT_COMPLETIONS".equals(a.configuration().protocol())) throw unavailable();
        return a;
    }
    static View aggregate(ProductComparisonParentEvidenceReader.Evidence evidence, Snapshot after, String version, String configSha, Instant expiry) {
        var current=evidence.current(); var parent=evidence.parent();
        if (!after.taskId().equals(current.input().taskId()) || !after.comparisonId().equals(current.input().comparisonId())
                || !after.inputSha().equals(current.inputSha()) || !parent.input().comparisonId().equals(after.previousComparisonId())
                || !parent.input().taskId().equals(after.taskId()) || after.inputSha().equals(parent.inputSha())) throw unavailable();
        Set<RowKey> invalidated=new HashSet<>(), carriedKeys=new HashSet<>();
        Set<String> candidates=new HashSet<>(), reportIds=new HashSet<>();
        List<CarryForwardFrom> carried=new ArrayList<>();
        if (evidence.reports().size()>20) throw unavailable();
        for (var report:evidence.reports()) {
            if (!report.path().getLast().comparisonId().equals(parent.input().comparisonId()) || !report.path().getLast().inputSha().equals(parent.inputSha())
                    || !candidates.add(report.original().candidateId()) || !reportIds.add(report.original().reportId())) throw unavailable();
            List<Snapshot> path=new ArrayList<>(report.path());path.add(after);
            var plan=PatentComparisonLineage.planAncestral(path,report.original());
            invalidated.addAll(plan.invalidated());
            for (var ref:plan.carried()) { if (!carriedKeys.add(ref.key())) throw unavailable(); carried.add(ref); }
        }
        Set<RowKey> recompute=new HashSet<>(after.expectedRows()); recompute.removeAll(carriedKeys);
        carried.sort(Comparator.comparing(CarryForwardFrom::key,ORDER));
        return new View(after.taskId(),after.comparisonId(),after.inputSha(),after.previousComparisonId(),parent.inputSha(),version,configSha,expiry,
                sorted(after.expectedRows()),sorted(invalidated),sorted(recompute),List.copyOf(carried),evidence.ancestry());
    }
    private static final Comparator<RowKey> ORDER=Comparator.comparing(RowKey::candidateId).thenComparing(RowKey::claimBranch,(a,b) -> {
        for (int i=0;i<Math.min(a.size(),b.size());i++) {int c=Integer.compare(a.get(i),b.get(i));if(c!=0)return c;}
        return Integer.compare(a.size(),b.size());
    }).thenComparing(RowKey::featureId);
    private static List<RowKey> sorted(Set<RowKey> rows) { return rows.stream().sorted(ORDER).toList(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("LINEAGE_CONFIGURATION_OR_BINDING_UNAVAILABLE"); }
}
