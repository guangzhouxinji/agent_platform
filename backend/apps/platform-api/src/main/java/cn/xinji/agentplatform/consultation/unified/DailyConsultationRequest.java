package cn.xinji.agentplatform.consultation.unified;

import java.util.List;

public record DailyConsultationRequest(String question, List<ContextTurn> context) {
    public DailyConsultationRequest {
        context = context == null ? List.of() : List.copyOf(context);
    }

    public record ContextTurn(int round, String content) {}
}
