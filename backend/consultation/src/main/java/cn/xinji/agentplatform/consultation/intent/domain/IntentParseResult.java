package cn.xinji.agentplatform.consultation.intent.domain;

import java.time.Instant;
import java.util.List;

public record IntentParseResult(
        String status,
        String parserVersion,
        String question,
        String primaryIntentId,
        List<IntentCandidate> intents,
        List<String> clarificationQuestions,
        String limitation,
        String traceId,
        Instant parsedAt) {
    public IntentParseResult {
        intents = List.copyOf(intents);
        clarificationQuestions = List.copyOf(clarificationQuestions);
    }

    public record IntentCandidate(
            String intentId,
            String label,
            String category,
            int order,
            List<String> matchedSignals,
            List<ExtractedSlot> slots,
            List<String> missingSlots,
            String plannedRoute,
            String routeState,
            String reason) {
        public IntentCandidate {
            matchedSignals = List.copyOf(matchedSignals);
            slots = List.copyOf(slots);
            missingSlots = List.copyOf(missingSlots);
        }
    }

    public record ExtractedSlot(String name, String value, int sourceRound, String sourceType) {}
}
