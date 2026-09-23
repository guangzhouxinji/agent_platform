package cn.xinji.agentplatform.consultation.intent.domain;

import java.util.List;

public record IntentParseCommand(String question, List<ContextTurn> context) {
    public IntentParseCommand {
        question = question == null ? "" : question.trim();
        if (question.isBlank()) throw new IllegalArgumentException("question is required");
        context = context == null ? List.of() : List.copyOf(context);
    }

    public record ContextTurn(int round, String content) {
        public ContextTurn {
            if (round < 1) throw new IllegalArgumentException("context round must be positive");
            content = content == null ? "" : content.trim();
            if (content.isBlank()) throw new IllegalArgumentException("context content is required");
        }
    }
}
