package cn.xinji.agentplatform.consultation.domain;

import java.util.List;

public record ConsultationOutcome(
        String consultationId,
        String answerId,
        int answerVersion,
        String retrievalTaskId,
        String fingerprint,
        String type,
        List<String> issues,
        List<ConsultationCandidate> candidates,
        List<ConsultationSourceLink> sourceLinks,
        String content,
        String limitation) {
    public ConsultationOutcome(String consultationId, String fingerprint, String type, List<String> issues, List<ConsultationCandidate> candidates, String content, String limitation) {
        this(consultationId, consultationId, 1, "", fingerprint, type, issues, candidates, List.of(), content, limitation);
    }
}
