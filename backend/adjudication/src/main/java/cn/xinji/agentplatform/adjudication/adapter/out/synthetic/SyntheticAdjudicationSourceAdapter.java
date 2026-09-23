package cn.xinji.agentplatform.adjudication.adapter.out.synthetic;

import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.*;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.*;
import java.util.*;

public final class SyntheticAdjudicationSourceAdapter implements SourcePort {
    private final Map<String, Source> cases = new HashMap<>();
    private final Map<String, MaterialData> materials = new HashMap<>();
    private final Map<String, String> materialCaseIds = new HashMap<>();
    public SyntheticAdjudicationSourceAdapter() {
        cases.put("V0-SYN-ADJ-CASE-001", source("case", "V0-SYN-ADJ-CASE-001", "v1", "case"));
        addMaterial("V0-SYN-ADJ-MAT-001", "V0-SYN-ADJ-CASE-001", data(true, List.of("申请人主张存在合成侵权行为"), List.of("待核实技术特征"), List.of(), List.of()));
        addMaterial("V0-SYN-ADJ-MAT-002", "V0-SYN-ADJ-CASE-001", data(true, List.of("被申请人否认使用相关技术"), List.of("待核实产品来源"), List.of("专利比对材料"), List.of("发生日期存在两种记载")));
        addMaterial("V0-SYN-ADJ-MAT-003", "V0-SYN-ADJ-CASE-001", data(false, List.of(), List.of(), List.of("来源不可用"), List.of()));
        for (int caseIndex = 2; caseIndex <= 20; caseIndex++) {
            String caseId = String.format("V0-SYN-ADJ-CASE-%03d", caseIndex); cases.put(caseId, source("case", caseId, caseIndex % 5 == 0 ? "v2" : "v1", "case-file"));
        }
        String[] products = {"工业视觉检测设备", "柔性薄膜涂覆设备", "智能仓储调度系统", "低噪声离心风机", "冷链温控终端"};
        for (int index = 4; index <= 80; index++) {
            boolean available = index % 17 != 0; List<String> missing = index % 9 == 0 ? List.of("权利要求对照表", "被控产品技术资料") : List.of();
            List<String> conflicts = index % 11 == 0 ? List.of("产品型号在采购合同与检测记录中不一致") : List.of();
            String materialId = String.format("V0-SYN-ADJ-MAT-%03d", index);
            String caseId = String.format("V0-SYN-ADJ-CASE-%03d", (index - 1) / 4 + 1);
            addMaterial(materialId, caseId, data(available,
                    List.of("申请方主张被控" + products[index % products.length] + "包含涉案技术特征", "被申请方对技术特征对应关系提出异议"),
                    List.of("需核实样品型号、销售时间与技术特征来源"), available ? missing : List.of("来源当前不可访问"), conflicts));
        }
    }
    @Override public Optional<Source> findCase(String id, String version) { return Optional.ofNullable(cases.get(id)).filter(value -> value.version().equals(version)); }
    @Override public Optional<Source> findMaterial(String id) { return materials.containsKey(id) ? Optional.of(source("material", id, "v1", "material")) : Optional.empty(); }
    @Override public Optional<MaterialData> materialData(String id) { return Optional.ofNullable(materials.get(id)); }
    @Override public List<LegalData> legalCandidates(MaterialInspection inspection) { return List.of(new LegalData("V0-SYN-ADJ-LAW-001", source("legal", "V0-SYN-ADJ-LAW-001", "v1", "article:10"), "article:10", List.of("仅合成候选，不构成法律适用"))); }
    @Override public Source template() { return source("template", "V0-SYN-ADJ-TPL-001", "v1", "structure"); }
    @Override public List<Section> draftSections(MaterialInspection inspection, List<Citation> citations) { return List.of(new Section("facts", "合成草稿：待核实事项和来源映射已保留。", inspection.inputs()), new Section("legal", "合成法规候选引用数：" + citations.size(), citations.stream().map(Citation::legalSource).toList())); }
    public void markUnavailable(String id) { MaterialData prior = materials.get(id); if (prior != null) materials.put(id, new MaterialData(false, prior.partyStatements(), prior.unresolvedFacts(), prior.missingItems(), prior.conflicts())); }
    @Override public List<Source> cases() { return cases.values().stream().sorted(Comparator.comparing(Source::id)).toList(); }
    public List<Source> materials() { return materials.keySet().stream().sorted().map(id -> source("material", id, "v1", "material")).toList(); }
    @Override public List<MaterialCatalogItem> materialItems() { return materials.keySet().stream().sorted().map(this::materialItem).flatMap(Optional::stream).toList(); }
    public Optional<MaterialCatalogItem> materialItem(String id) {
        MaterialData material = materials.get(id);
        Source caseRef = cases.get(materialCaseIds.get(id));
        return material == null || caseRef == null ? Optional.empty() : Optional.of(new MaterialCatalogItem(source("material", id, "v1", "material"), caseRef, material));
    }
    public List<LegalData> legalCandidates() { return legalCandidates(null); }
    private void addMaterial(String materialId, String caseId, MaterialData material) { materials.put(materialId, material); materialCaseIds.put(materialId, caseId); }
    private static MaterialData data(boolean available, List<String> statements, List<String> unresolved, List<String> missing, List<String> conflicts) { return new MaterialData(available, statements, unresolved, missing, conflicts); }
    private static Source source(String kind, String id, String version, String location) { return new Source("synthetic-adjudication-" + kind, id, version, location); }
    @Override public String dataMode() { return "SYNTHETIC_V0"; }
    @Override public String catalogVersion() { return "synthetic-adjudication-source-v2"; }
    @Override public String limitation() { return "内部开发夹具；不得作为真实案件、材料、法规或正式决定。"; }
}
