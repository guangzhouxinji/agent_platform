package cn.xinji.agentplatform.enforcement.adapter.out.synthetic;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.domain.ClueAnalysisVersion;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import java.util.*;

public final class SyntheticEnforcementSourceAdapter implements EnforcementSourcePort {
    private final Map<String, ClueData> clues = new HashMap<>();
    private final Map<String, EvidenceData> evidence = new HashMap<>();

    public SyntheticEnforcementSourceAdapter() {
        SourceLocator clue1 = source("V0-SYN-CLUE-001", "v1", "body");
        clues.put(clue1.sourceId(), new ClueData(clue1,
                List.of(subject("投诉人", "合成投诉人甲", clue1), subject("经营者", "合成经营者乙", clue1), subject("权利人", "合成权利人丙", clue1)),
                List.of(value("2026-08-18 在测试门店展示合成商品 X", null, null, clue1)),
                List.of(value("2026-08-18", "2026-08-18", "OCCURRENCE", clue1)),
                List.of(value("某测试门店", "测试地址", null, clue1)),
                List.of(value("V0-SYN-EVD-001", null, null, clue1), value("V0-SYN-EVD-002", null, null, clue1)),
                List.of(), List.of()));

        SourceLocator clue2 = source("V0-SYN-CLUE-002", "v1", "body");
        var timeA = value("2026-08-03", "2026-08-03", "OCCURRENCE", source("V0-SYN-EVD-003", "v1", "field:eventDate"));
        var timeB = value("2026-08-12", "2026-08-12", "OCCURRENCE", source("V0-SYN-EVD-004", "v1", "field:eventDate"));
        clues.put(clue2.sourceId(), new ClueData(clue2,
                List.of(subject("投诉人", "合成投诉人丁", clue2)),
                List.of(value("8 月上旬涉及合成商品", null, null, clue2)),
                List.of(timeA, timeB), List.of(),
                List.of(value("V0-SYN-EVD-003", null, null, clue2), value("V0-SYN-EVD-004", null, null, clue2), value("V0-SYN-EVD-007", null, null, clue2)),
                List.of("COLLECTOR_MISSING", "HISTORICAL_RIGHT_STATUS_MISSING"),
                List.of(new ClueAnalysisVersion.Conflict("occurrenceTime", List.of(timeA, timeB)))));

        evidence.put("V0-SYN-EVD-001", evidence("V0-SYN-EVD-001", "V0-SYN-CLUE-001", "AVAILABLE", "合成事项 A", "SUPPORT", "截图包含带定位的展示陈述", "2026-08-18", "2026-08-19", "2026-08-20", "合成收集人"));
        evidence.put("V0-SYN-EVD-002", evidence("V0-SYN-EVD-002", "V0-SYN-CLUE-001", "AVAILABLE", "合成事项 B", "SUPPORT", "照片提供带定位的商品外观候选信息", "2026-08-18", "2026-08-19", "2026-08-20", "合成收集人"));
        evidence.put("V0-SYN-EVD-003", evidence("V0-SYN-EVD-003", "V0-SYN-CLUE-002", "AVAILABLE", "发生时间", "SUPPORT", "附件 A 记载 2026-08-03", "2026-08-03", null, "2026-08-20", null));
        evidence.put("V0-SYN-EVD-004", evidence("V0-SYN-EVD-004", "V0-SYN-CLUE-002", "AVAILABLE", "发生时间", "CONFLICT", "附件 B 记载 2026-08-12", "2026-08-12", null, "2026-08-20", null));
        evidence.put("V0-SYN-EVD-005", evidence("V0-SYN-EVD-005", "V0-SYN-CLUE-003", "AVAILABLE", "受限事项", "SUPPORT", "该内容不得向无权主体披露", null, null, null, null));
        evidence.put("V0-SYN-EVD-006", evidence("V0-SYN-EVD-006", "V0-SYN-CLUE-001", "AVAILABLE", "合成事项 C", "SUPPORT", "可在测试中变为来源失效", null, null, null, null));
        evidence.put("V0-SYN-EVD-007", evidence("V0-SYN-EVD-007", "V0-SYN-CLUE-002", "UNSUPPORTED_CONTENT", "音视频内容", "MISSING", "当前测试环境无法解析", null, null, "2026-08-20", null));
        String[] subjects = {"宁江瑞衡新材料有限公司", "临川启辰商贸有限公司", "东港澄岳科技有限公司", "海陵知禾供应链有限公司"};
        String[] places = {"临川高新区创新路18号", "宁江经开区云岭路26号", "东港市滨河商业街105号", "海陵产业园启明路9号"};
        for (int index = 3; index <= 30; index++) {
            String clueId = String.format("V0-SYN-CLUE-%03d", index); SourceLocator clue = source(clueId, index % 6 == 0 ? "v2" : "v1", "body.paragraph[1]");
            List<ClueAnalysisVersion.SourcedValue> mentions = new ArrayList<>();
            for (int item = 1; item <= 4; item++) {
                int evidenceIndex = 8 + (index - 3) * 4 + item - 1; String evidenceId = String.format("V0-SYN-EVD-%03d", evidenceIndex); mentions.add(value(evidenceId, null, null, clue));
                String availability = item == 4 && index % 5 == 0 ? "SOURCE_UNAVAILABLE" : item == 3 && index % 7 == 0 ? "UNSUPPORTED_CONTENT" : "AVAILABLE";
                String relation = item == 2 && index % 4 == 0 ? "CONFLICT" : "SUPPORT";
                evidence.put(evidenceId, evidence(evidenceId, clueId, availability, item == 1 ? "经营主体与商品页面" : item == 2 ? "交易与展示时间" : item == 3 ? "权利标识使用情况" : "现场核查记录", relation,
                        "来源记录包含采集时间、页面定位和摘要值，用于拟真流程验证", String.format("2026-%02d-%02d", index % 8 + 1, index % 20 + 1),
                        String.format("2026-%02d-%02d", index % 8 + 1, index % 20 + 2), "2026-09-10", "测试采集员" + (index % 5 + 1)));
            }
            List<String> missing = index % 6 == 0 ? List.of("RIGHT_STATUS_AT_OCCURRENCE_MISSING") : List.of();
            clues.put(clueId, new ClueData(clue,
                    List.of(subject("被投诉经营者", subjects[index % subjects.length], clue), subject("线索提供方", "测试主体" + String.format("%02d", index), clue)),
                    List.of(value("线上商品页面与线下展示记录存在待核实的权利标识使用情况", null, null, clue)),
                    List.of(value(String.format("2026-%02d-%02d", index % 8 + 1, index % 20 + 1), null, "OCCURRENCE", clue)),
                    List.of(value(places[index % places.length], places[index % places.length], null, clue)), List.copyOf(mentions), missing, List.of()));
        }
    }

    @Override public Optional<ClueData> findClue(String clueId, String version) {
        return Optional.ofNullable(clues.get(clueId)).filter(c -> c.source().version().equals(version));
    }
    @Override public Optional<EvidenceData> findEvidence(String evidenceId) { return Optional.ofNullable(evidence.get(evidenceId)); }
    @Override public SourceLocator syntheticRule() { return new SourceLocator("v0-synthetic-rule", "V0-SYN-ENF-RULE-001", "v1", "checklist"); }
    @Override public Optional<SourceLocator> historicalRightStatus(String clueId) {
        return "V0-SYN-CLUE-001".equals(clueId) ? Optional.of(new SourceLocator("v0-synthetic-right", "V0-SYN-RIGHT-001", "v1", "status@2026-08-18")) : Optional.empty();
    }
    public List<ClueData> clues() { return clues.values().stream().sorted(Comparator.comparing(value -> value.source().sourceId())).toList(); }
    public List<EvidenceData> evidence() { return evidence.values().stream().sorted(Comparator.comparing(value -> value.source().sourceId())).toList(); }

    public void markUnavailable(String evidenceId) {
        EvidenceData prior = evidence.get(evidenceId);
        if (prior == null) return;
        evidence.put(evidenceId, new EvidenceData(prior.source(), prior.controlledResourceRef(), prior.relatedClueId(), prior.collector(),
                prior.collectionMissingReason(), prior.occurrenceTime(), prior.collectionTime(), prior.uploadTime(), "SOURCE_UNAVAILABLE",
                prior.issue(), prior.relationType(), prior.relationReason(), prior.originalRef(), prior.derivedKind()));
    }

    private static ClueAnalysisVersion.SubjectCandidate subject(String role, String name, SourceLocator source) { return new ClueAnalysisVersion.SubjectCandidate(role, name, null, source); }
    private static ClueAnalysisVersion.SourcedValue value(String value, String normalized, String timeKind, SourceLocator source) { return new ClueAnalysisVersion.SourcedValue(value, normalized, timeKind, source); }
    private static SourceLocator source(String id, String version, String location) { return new SourceLocator("v0-synthetic-enforcement", id, version, location); }
    private static EvidenceData evidence(String id, String clue, String availability, String issue, String relationType, String reason, String occurrence, String collection, String upload, String collector) {
        return new EvidenceData(source(id, "v1", "field:content"), "controlled://" + id, clue, collector,
                collector == null ? "NOT_PROVIDED" : null, occurrence, collection, upload, availability, issue, relationType, reason, null, null);
    }
}
