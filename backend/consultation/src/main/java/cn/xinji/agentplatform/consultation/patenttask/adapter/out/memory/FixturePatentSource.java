package cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory;

import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentResultSet;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentRetrievalAction;

import java.util.List;
import java.util.Map;

/** Invented documents for interaction testing. Not a patent database or professional search. */
public final class FixturePatentSource implements PatentFixtureSourcePort {
    private static final Map<String, PatentFixtureDocument> DOCUMENTS = Map.of(
            "fixture-thermal", new PatentFixtureDocument("fixture-thermal", "fixture-doc-thermal-v1",
                    "样例：温度控制装置", "SYNTHETIC-PATENT-001", "说明书样例第 3 段",
                    "样例片段：装置记录温度读数，并由控制单元驱动开关。", "虚构资料，不可作为专利依据"),
            "fixture-sensor", new PatentFixtureDocument("fixture-sensor", "fixture-doc-sensor-v1",
                    "样例：多点传感组件", "SYNTHETIC-PATENT-002", "说明书样例第 5 段",
                    "样例片段：多个传感单元向处理模块发送测量值。", "虚构资料，不可作为专利依据"));

    @Override public Response search(String scopeReference, String methodReference, String queryReference) {
        if ("fixture:no-result".equals(queryReference)) {
            return new Response(PatentRetrievalAction.Outcome.NO_RESULT, scopeReference, methodReference,
                    List.of("SYNTHETIC-FIXTURE"), List.of(), List.of("invented corpus only"), "no fixture record");
        }
        if ("fixture:failure".equals(queryReference)) throw new IllegalStateException("fixture source failure");
        return new Response(PatentRetrievalAction.Outcome.RESULTS, scopeReference, methodReference,
                List.of("SYNTHETIC-FIXTURE"), DOCUMENTS.values().stream().sorted(
                        java.util.Comparator.comparing(PatentFixtureDocument::candidateId))
                        .map(item -> new PatentResultSet.Candidate(item.candidateId(), item.documentVersion())).toList(),
                List.of("invented documents", "no recall or relevance claim"), "fixture candidates only");
    }

    @Override public PatentFixtureDocument document(String candidateId, String documentVersion) {
        PatentFixtureDocument document = DOCUMENTS.get(candidateId);
        if (document == null || !document.documentVersion().equals(documentVersion)) {
            throw new IllegalArgumentException("fixture document version not found");
        }
        return document;
    }
}
