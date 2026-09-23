package cn.xinji.agentplatform.consultation.adapter.in.web;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import java.util.List;

public record ConsultationResponse(
        String consultationId,
        String answerId,
        int answerVersion,
        String retrievalTaskId,
        String outcomeType,
        List<String> issues,
        List<CandidateResponse> candidates,
        List<SourceLinkResponse> sourceLinks,
        String content,
        String limitation) {
    static ConsultationResponse from(ConsultationOutcome outcome) {
        return new ConsultationResponse(outcome.consultationId(), outcome.answerId(), outcome.answerVersion(), outcome.retrievalTaskId(), outcome.type(), outcome.issues(),
                outcome.candidates().stream().map(CandidateResponse::from).toList(), outcome.sourceLinks().stream().map(SourceLinkResponse::from).toList(), outcome.content(), outcome.limitation());
    }

    record CandidateResponse(String sourceId, String version, String location, String matchBasis) {
        static CandidateResponse from(ConsultationCandidate candidate) {
            return new CandidateResponse(candidate.sourceId(), candidate.version(), candidate.location(), candidate.matchBasis());
        }
    }
}
