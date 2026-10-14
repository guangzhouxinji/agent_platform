package cn.xinji.agentplatform.consultation.prompt.domain;

import java.util.List;

public final class PromptFailure extends RuntimeException {
    public record Issue(String code, String variable) {}
    private final List<Issue> issues;

    public PromptFailure(String code) { this(List.of(new Issue(code, null))); }

    public PromptFailure(List<Issue> issues) {
        super(issues.isEmpty() ? "PROMPT_ERROR" : issues.get(0).code());
        if (issues.isEmpty()) throw new IllegalArgumentException("issues");
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() { return issues; }
}
