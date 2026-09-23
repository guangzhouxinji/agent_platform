package cn.xinji.agentplatform.enforcement.application;

import static cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class DeterministicRuleInterpreter {
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
    private static final List<String> LEAVES = List.of("EXISTS", "TEXT_CONTAINS", "NUMBER_GT", "NUMBER_GTE",
            "NUMBER_LT", "NUMBER_LTE", "NUMBER_EQ");
    private static final List<String> GROUPS = List.of("AND", "OR");
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 64;

    void validate(Condition root) {
        if (root == null) throw failure("CONDITION_REQUIRED", "condition");
        Counter count = new Counter();
        validate(root, 1, count, "condition");
    }

    Outcome evaluate(Condition root, Map<String, Object> payload) {
        validate(root);
        List<ConditionResult> results = new ArrayList<>();
        Evaluation evaluation = evaluateNode(root, payload, results);
        String status = evaluation.typeError ? "TYPE_ERROR" : "SUCCEEDED";
        return new Outcome(status, !evaluation.typeError && evaluation.matched,
                evaluation.typeError ? "FIELD_TYPE_MISMATCH" : null, List.copyOf(results));
    }

    private void validate(Condition condition, int depth, Counter count, String path) {
        if (depth > MAX_DEPTH) throw failure("CONDITION_DEPTH_EXCEEDED", path);
        if (++count.value > MAX_NODES) throw failure("CONDITION_COUNT_EXCEEDED", path);
        if (condition.nodeId() == null || condition.nodeId().isBlank()) throw failure("CONDITION_NODE_ID_REQUIRED", path + ".nodeId");
        if (!count.nodeIds.add(condition.nodeId())) throw failure("CONDITION_NODE_ID_DUPLICATE", path + ".nodeId");
        String operator = condition.operator();
        if (!LEAVES.contains(operator) && !GROUPS.contains(operator)) throw failure("OPERATOR_NOT_ALLOWED", path + ".operator");
        if (GROUPS.contains(operator)) {
            if (condition.children().size() < 2) throw failure("CONDITION_CHILDREN_INVALID", path + ".children");
            if (condition.fieldPath() != null || condition.value() != null) throw failure("CONDITION_SHAPE_INVALID", path);
            for (int index = 0; index < condition.children().size(); index++) validate(condition.children().get(index), depth + 1, count, path + ".children[" + index + "]");
            return;
        }
        if (condition.children() != null && !condition.children().isEmpty()) throw failure("CONDITION_SHAPE_INVALID", path + ".children");
        if (condition.fieldPath() == null || !FIELD.matcher(condition.fieldPath()).matches()) throw failure("FIELD_PATH_INVALID", path + ".fieldPath");
        if ("EXISTS".equals(operator)) {
            if (condition.value() != null) throw failure("CONDITION_VALUE_INVALID", path + ".value");
        } else if ("TEXT_CONTAINS".equals(operator)) {
            if (!(condition.value() instanceof String text) || text.isBlank()) throw failure("CONDITION_VALUE_INVALID", path + ".value");
        } else if (!(condition.value() instanceof Number)) {
            throw failure("CONDITION_VALUE_INVALID", path + ".value");
        }
    }

    private Evaluation evaluateNode(Condition condition, Map<String, Object> payload, List<ConditionResult> results) {
        if (GROUPS.contains(condition.operator())) {
            List<Evaluation> children = condition.children().stream().map(child -> evaluateNode(child, payload, results)).toList();
            boolean typeError = children.stream().anyMatch(Evaluation::typeError);
            boolean matched = "AND".equals(condition.operator())
                    ? children.stream().allMatch(Evaluation::matched) : children.stream().anyMatch(Evaluation::matched);
            results.add(new ConditionResult(condition.nodeId(), condition.operator(), null,
                    !typeError && matched, typeError ? "CHILD_TYPE_ERROR" : matched ? "MATCHED" : "NOT_MATCHED", null));
            return new Evaluation(matched, typeError);
        }
        Lookup lookup = lookup(payload, condition.fieldPath());
        if (!lookup.present) {
            results.add(new ConditionResult(condition.nodeId(), condition.operator(), condition.fieldPath(), false,
                    "FIELD_MISSING", null));
            return new Evaluation(false, false);
        }
        if ("EXISTS".equals(condition.operator())) {
            results.add(new ConditionResult(condition.nodeId(), condition.operator(), condition.fieldPath(), true,
                    "MATCHED", safeActual(lookup.value)));
            return new Evaluation(true, false);
        }
        if ("TEXT_CONTAINS".equals(condition.operator())) {
            if (!(lookup.value instanceof String actual)) return typeError(condition, lookup.value, results);
            boolean matched = actual.contains((String) condition.value());
            results.add(result(condition, matched, safeActual(actual)));
            return new Evaluation(matched, false);
        }
        if (!(lookup.value instanceof Number actual)) return typeError(condition, lookup.value, results);
        int comparison = new BigDecimal(actual.toString()).compareTo(new BigDecimal(condition.value().toString()));
        boolean matched = switch (condition.operator()) {
            case "NUMBER_GT" -> comparison > 0; case "NUMBER_GTE" -> comparison >= 0;
            case "NUMBER_LT" -> comparison < 0; case "NUMBER_LTE" -> comparison <= 0;
            case "NUMBER_EQ" -> comparison == 0; default -> false;
        };
        results.add(result(condition, matched, safeActual(actual)));
        return new Evaluation(matched, false);
    }

    private static Evaluation typeError(Condition condition, Object actual, List<ConditionResult> results) {
        results.add(new ConditionResult(condition.nodeId(), condition.operator(), condition.fieldPath(), false,
                "FIELD_TYPE_MISMATCH", safeActual(actual)));
        return new Evaluation(false, true);
    }
    private static ConditionResult result(Condition condition, boolean matched, Object actual) {
        return new ConditionResult(condition.nodeId(), condition.operator(), condition.fieldPath(), matched,
                matched ? "MATCHED" : "NOT_MATCHED", actual);
    }
    @SuppressWarnings("unchecked")
    private static Lookup lookup(Map<String, Object> payload, String path) {
        Object current = payload;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return new Lookup(false, null);
            current = ((Map<String, Object>) map).get(part);
        }
        return new Lookup(current != null, current);
    }
    private static Object safeActual(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value : null;
    }

    static String canonical(Condition condition) {
        String children = condition.children().stream().map(DeterministicRuleInterpreter::canonical).toList().toString();
        return condition.nodeId() + '|' + condition.operator() + '|' + Objects.toString(condition.fieldPath(), "")
                + '|' + Objects.toString(condition.value(), "") + '|' + children;
    }
    record Outcome(String status, boolean matched, String unhandledReason, List<ConditionResult> results) {}
    private record Evaluation(boolean matched, boolean typeError) {}
    private record Lookup(boolean present, Object value) {}
    private static final class Counter { int value; final Set<String> nodeIds = new HashSet<>(); }
}
