package cn.xinji.agentplatform.consultation.prompt.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TemplateRenderer {
    public enum Mode { TEST, RUNTIME }
    public sealed interface Segment permits LiteralSegment, DataSegment {}
    public record LiteralSegment(String text) implements Segment {}
    public record DataSegment(String name, VariableDefinition.Type type, String sourceEvidenceRef,
                              Object value) implements Segment {
        public DataSegment { if (value instanceof List<?> list) value = List.copyOf(list); }
    }
    public record Value(Object value, String sourceRef, String evidenceRef) {
        public Value { if (value instanceof List<?> list) value = List.copyOf(list); }
    }
    /** Attestation returned by the trusted policy port; this value object does not authenticate its digest. */
    public record Policy(String policyRef, String policySha256, Instant expiresAt,
                         Map<String, VariableDefinition> allowedDefinitions) {
        public Policy {
            Objects.requireNonNull(policyRef); Objects.requireNonNull(expiresAt);
            if (policyRef.isBlank() || policySha256 == null || !policySha256.matches("[a-f0-9]{64}"))
                throw new PromptFailure("VARIABLE_POLICY_UNAVAILABLE");
            allowedDefinitions = Map.copyOf(allowedDefinitions);
        }
    }
    public record Result(PromptRevision.Reference revision, String policyRef, String policySha256,
                         Mode mode, List<Segment> segments) {
        public Result { segments = List.copyOf(segments); }
    }
    private record Token(String literal, String variable) {}

    public Result render(PromptRevision revision, Map<String, Value> supplied, Policy policy,
                         Mode mode, Instant now) {
        return render(revision, supplied, policy, mode, now, Integer.MAX_VALUE);
    }

    public Result render(PromptRevision revision, Map<String, Value> supplied, Policy policy,
                         Mode mode, Instant now, int maxSegments) {
        if (maxSegments < 1) throw new IllegalArgumentException("maxSegments");
        Objects.requireNonNull(mode); Objects.requireNonNull(now);
        if (policy == null || !now.isBefore(policy.expiresAt())) throw new PromptFailure("VARIABLE_POLICY_UNAVAILABLE");
        List<Token> tokens = tokenize(revision.body(), maxSegments);
        Map<String, VariableDefinition> schema = new HashMap<>();
        revision.variables().forEach(v -> schema.put(v.name(), v));
        List<PromptFailure.Issue> issues = new ArrayList<>();
        for (VariableDefinition definition : revision.variables()) {
            if (!definition.equals(policy.allowedDefinitions().get(definition.name())))
                issues.add(new PromptFailure.Issue("VARIABLE_POLICY_UNAVAILABLE", definition.name()));
        }
        supplied.keySet().stream().filter(n -> !schema.containsKey(n)).sorted()
                .forEach(n -> issues.add(new PromptFailure.Issue("UNDEFINED_VARIABLE", n)));
        tokens.stream().filter(t -> t.variable() != null && !schema.containsKey(t.variable()))
                .forEach(t -> issues.add(new PromptFailure.Issue("UNDEFINED_VARIABLE", t.variable())));
        Map<String, DataSegment> data = new HashMap<>();
        for (VariableDefinition definition : revision.variables()) {
            Value provided = supplied.get(definition.name());
            Object value = provided == null ? null : provided.value();
            String evidence = provided == null ? null : provided.evidenceRef();
            if (value != null && (!definition.sourceRef().equals(provided.sourceRef())
                    || evidence == null || evidence.isBlank())) {
                issues.add(new PromptFailure.Issue("VARIABLE_POLICY_UNAVAILABLE", definition.name()));
                continue;
            }
            if (value == null && definition.allowDefault()) {
                value = definition.defaultValue(); evidence = policy.policyRef();
            }
            boolean used = tokens.stream().anyMatch(t -> definition.name().equals(t.variable()));
            if (value == null) {
                if (definition.required() || used) issues.add(new PromptFailure.Issue("MISSING_VARIABLE", definition.name()));
                continue;
            }
            try {
                data.put(definition.name(), new DataSegment(definition.name(), definition.type(), evidence, definition.checked(value)));
            } catch (PromptFailure failure) { issues.addAll(failure.issues()); }
        }
        if (!issues.isEmpty()) throw new PromptFailure(issues);
        List<Segment> segments = tokens.stream().map(t -> t.variable() == null
                ? (Segment) new LiteralSegment(t.literal()) : data.get(t.variable())).toList();
        return new Result(revision.reference(), policy.policyRef(), policy.policySha256(), mode, segments);
    }

    public void validateSyntax(String body) { tokenize(body); }

    private static List<Token> tokenize(String body) {
        return tokenize(body, Integer.MAX_VALUE);
    }

    private static List<Token> tokenize(String body, int maxSegments) {
        if (body == null) throw new PromptFailure("INVALID_TEMPLATE");
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < body.length()) {
            int open = body.indexOf("{{", cursor);
            int strayClose = body.indexOf("}}", cursor);
            if (strayClose >= 0 && (open < 0 || strayClose < open)) throw new PromptFailure("INVALID_TEMPLATE");
            if (open < 0) { budget(tokens,maxSegments);tokens.add(new Token(body.substring(cursor), null)); break; }
            if (open > cursor) {budget(tokens,maxSegments);tokens.add(new Token(body.substring(cursor, open), null));}
            int close = body.indexOf("}}", open + 2);
            if (close < 0) throw new PromptFailure("INVALID_TEMPLATE");
            String name = body.substring(open + 2, close);
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) throw new PromptFailure("INVALID_TEMPLATE");
            budget(tokens,maxSegments);tokens.add(new Token(null, name)); cursor = close + 2;
        }
        return List.copyOf(tokens);
    }

    private static void budget(List<Token> tokens,int maxSegments) {
        if(tokens.size()>=maxSegments)throw new PromptFailure("PREVIEW_RESOURCE_LIMIT");
    }
}
