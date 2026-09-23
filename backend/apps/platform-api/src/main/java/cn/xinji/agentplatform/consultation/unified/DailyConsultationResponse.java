package cn.xinji.agentplatform.consultation.unified;

import java.util.List;

public record DailyConsultationResponse(
        String consultationId,
        String status,
        String parserVersion,
        String intentResolutionSource,
        String primaryIntentId,
        List<String> clarificationQuestions,
        List<RouteResult> results,
        String content,
        String limitation) {

    public DailyConsultationResponse {
        clarificationQuestions = List.copyOf(clarificationQuestions);
        results = List.copyOf(results);
    }

    public record RouteResult(
            String intentId,
            String label,
            String category,
            String status,
            String taskId,
            String summary,
            String actualScope,
            String limitation,
            String nextAction,
            String nextActionHref,
            List<Candidate> candidates,
            List<Source> sources) {
        public RouteResult {
            candidates = List.copyOf(candidates);
            sources = List.copyOf(sources);
        }
    }

    public record Candidate(
            String sourceId,
            String version,
            String title,
            String identifier,
            String matchBasis,
            String location) {}

    public record Source(
            String sourceId,
            String version,
            String title,
            String location,
            String sourceHref) {}
}
