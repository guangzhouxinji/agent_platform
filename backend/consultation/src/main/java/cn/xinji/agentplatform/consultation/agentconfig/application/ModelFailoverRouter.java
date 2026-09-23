package cn.xinji.agentplatform.consultation.agentconfig.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class ModelFailoverRouter {
    public <T> RoutedResult<T> execute(List<String> modelChain, Set<String> switchOn, Function<String, T> attempt) {
        List<Attempt> attempts = new ArrayList<>();
        for (String modelId : modelChain) {
            try {
                T result = attempt.apply(modelId);
                attempts.add(new Attempt(modelId, "SUCCEEDED"));
                return new RoutedResult<>(modelId, result, List.copyOf(attempts));
            } catch (ModelFailure failure) {
                attempts.add(new Attempt(modelId, failure.code()));
                if (!switchOn.contains(failure.code())) throw new RoutingFailure(failure.code(), List.copyOf(attempts));
            }
        }
        throw new RoutingFailure("MODEL_CHAIN_EXHAUSTED", List.copyOf(attempts));
    }

    public record Attempt(String modelId, String outcome) {}
    public record RoutedResult<T>(String selectedModelId, T result, List<Attempt> attempts) {}
    public static final class ModelFailure extends RuntimeException {
        private final String code;
        public ModelFailure(String code) { this.code = code; }
        public String code() { return code; }
    }
    public static final class RoutingFailure extends RuntimeException {
        private final String code; private final List<Attempt> attempts;
        public RoutingFailure(String code, List<Attempt> attempts) { this.code = code; this.attempts = attempts; }
        public String code() { return code; } public List<Attempt> attempts() { return attempts; }
    }
}
