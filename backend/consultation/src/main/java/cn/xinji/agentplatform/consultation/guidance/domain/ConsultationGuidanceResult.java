package cn.xinji.agentplatform.consultation.guidance.domain;

import java.time.Instant;
import java.util.List;

public record ConsultationGuidanceResult(
        String version,
        String status,
        String scenarioId,
        String language,
        List<ConsultationGuidance> guides,
        String limitation,
        Instant generatedAt) {
}
