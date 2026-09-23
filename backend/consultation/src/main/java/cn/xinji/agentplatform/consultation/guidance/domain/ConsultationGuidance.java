package cn.xinji.agentplatform.consultation.guidance.domain;

import java.util.List;

public record ConsultationGuidance(
        String guideId,
        String title,
        String question,
        String scenarioId,
        List<String> relatedQuestionIds,
        String language,
        String audience,
        int displayOrder,
        String status,
        String version) {
}
