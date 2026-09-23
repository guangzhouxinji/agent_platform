package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.List;

public interface ConsultationAnswerGenerator {
    Generation generate(String consultationId, String question, List<ConsultationCandidate> candidates, String actualScope);

    record Generation(String content, String limitation) {}

    static ConsultationAnswerGenerator deterministic() {
        return (consultationId, question, candidates, actualScope) -> new Generation(
                "已根据当前输入整理可参考的候选信息。",
                "");
    }
}
