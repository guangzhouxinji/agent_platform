package cn.xinji.agentplatform.consultation.prompt.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record VariableDefinition(String name, Type type, boolean required, String sourceRef,
                                 boolean allowDefault, Object defaultValue, boolean allowEmpty) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    public enum Type { STRING, BOOLEAN, INTEGER, DECIMAL, STRING_LIST }

    public VariableDefinition {
        if (name == null || !NAME.matcher(name).matches()) throw new PromptFailure("INVALID_TEMPLATE");
        Objects.requireNonNull(type, "type");
        if (sourceRef == null || sourceRef.isBlank()) throw new PromptFailure("VARIABLE_POLICY_UNAVAILABLE");
        if (!allowDefault && defaultValue != null) throw new PromptFailure("INVALID_TEMPLATE");
        if (allowDefault && defaultValue == null) throw new PromptFailure("INVALID_TEMPLATE");
        if (defaultValue != null) defaultValue = checked(type, defaultValue, allowEmpty, name);
    }

    public Object checked(Object value) { return checked(type, value, allowEmpty, name); }

    private static Object checked(Type type, Object value, boolean allowEmpty, String name) {
        boolean matches = switch (type) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger;
            case DECIMAL -> value instanceof BigDecimal;
            case STRING_LIST -> value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
        };
        if (!matches) throw new PromptFailure(List.of(new PromptFailure.Issue("VARIABLE_TYPE_MISMATCH", name)));
        if (!allowEmpty && (value instanceof String s && s.isEmpty()
                || value instanceof List<?> l && (l.isEmpty() || l.stream().anyMatch(v -> ((String) v).isEmpty())))) {
            throw new PromptFailure(List.of(new PromptFailure.Issue("VARIABLE_EMPTY_NOT_ALLOWED", name)));
        }
        return value instanceof List<?> list ? List.copyOf(list) : value;
    }
}
