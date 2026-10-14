package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Side-effect-free mapping validation and trial; access and immutable source versions belong to ports. */
public final class DocumentMappingValidator {
    private final Inputs inputs;
    public DocumentMappingValidator(Inputs inputs) { this.inputs = java.util.Objects.requireNonNull(inputs); }
    public Validation validate(String actor, Rule rule, String purpose) {
        require(actor, rule, purpose);
        Template template = inputs.template(actor, rule.organization(), rule.templateId(), rule.templateRevision());
        Schema schema = inputs.schema(actor, rule.organization(), rule.schemaId(), rule.schemaVersion());
        var issues = new ArrayList<Diagnostic>();
        if (!template.id().equals(rule.templateId()) || template.revision() != rule.templateRevision()
                || !template.sha256().equals(rule.templateSha256()))
            issues.add(new Diagnostic("", "", "TEMPLATE_DRIFT", true));
        if (!schema.id().equals(rule.schemaId()) || !schema.version().equals(rule.schemaVersion()))
            issues.add(new Diagnostic("", "", "SCHEMA_DRIFT", true));
        Map<String, Field> fields = new HashMap<>();
        template.fields().forEach(field -> fields.put(field.id(), field));
        Map<String, SchemaField> paths = new HashMap<>();
        schema.fields().forEach(field -> paths.put(field.path(), field));
        var seen = new HashSet<String>();
        for (Mapping mapping : rule.mappings()) {
            Field field = fields.get(mapping.fieldId());
            SchemaField source = paths.get(mapping.sourcePath());
            if (!seen.add(mapping.fieldId())) diagnostic(issues, mapping, "DUPLICATE_MAPPING", true);
            if (field == null) diagnostic(issues, mapping, "UNKNOWN_TEMPLATE_FIELD", true);
            if (source == null) diagnostic(issues, mapping, "UNKNOWN_SOURCE_PATH", true);
            if (!schema.version().equals(mapping.schemaVersion())) diagnostic(issues, mapping, "SCHEMA_DRIFT", true);
            if (mapping.mode() == null || mapping.missing() == null) diagnostic(issues, mapping, "MISSING_POLICY", true);
            if (field == null || source == null) continue;
            if (!field.type().equals(source.type())) diagnostic(issues, mapping, "TYPE_MISMATCH", true);
            if (field.repeated() != source.path().contains("[]")) diagnostic(issues, mapping, "REPEAT_MISMATCH", true);
            if (mapping.mode() == Mode.AUXILIARY_DRAFT && !"LONG_TEXT".equals(field.type()))
                diagnostic(issues, mapping, "AUXILIARY_PROTECTED_FIELD", true);
            if (mapping.mode() == Mode.DETERMINISTIC && !("ISO_DATE".equals(mapping.transform()) && "DATE".equals(field.type())))
                diagnostic(issues, mapping, "UNSUPPORTED_TRANSFORM", true);
            if (mapping.mode() != Mode.DETERMINISTIC && mapping.transform() != null && !mapping.transform().isBlank())
                diagnostic(issues, mapping, "UNEXPECTED_TRANSFORM", true);
            if (field.required() && mapping.missing() == Missing.OMIT_OPTIONAL)
                diagnostic(issues, mapping, "REQUIRED_FIELD_CANNOT_OMIT", true);
            if (!source.confirmed()) diagnostic(issues, mapping, "SOURCE_REVIEW_REQUIRED", protectedType(source.type()));
        }
        for (Field field : template.fields()) if (field.required() && !seen.contains(field.id()))
            issues.add(new Diagnostic(field.id(), "", "REQUIRED_MAPPING_MISSING", true));
        inputs.assertCurrent(actor, rule.organization(), rule, purpose);
        return new Validation(issues.stream().noneMatch(Diagnostic::blocking),
                issues.stream().anyMatch(issue -> List.of("SOURCE_REVIEW_REQUIRED","TEMPLATE_DRIFT","SCHEMA_DRIFT").contains(issue.code())), List.copyOf(issues));
    }
    public Trial trial(String actor, Rule rule, String purpose) {
        Validation checked = validate(actor, rule, purpose);
        var issues = new ArrayList<>(checked.diagnostics());
        var result = new ArrayList<Mapped>();
        if (!checked.valid()) return new Trial(false, checked.reviewRequired(), List.of(), List.copyOf(issues));
        Schema schema = inputs.schema(actor, rule.organization(), rule.schemaId(), rule.schemaVersion());
        Map<String, Value> values = inputs.values(actor, rule.organization(), schema, purpose);
        Template template = inputs.template(actor, rule.organization(), rule.templateId(), rule.templateRevision());
        for (Mapping mapping : rule.mappings()) {
            Value source = values.get(mapping.sourcePath());
            if (source == null || source.value() == null || source.value() instanceof String text && text.isBlank()) {
                boolean required = template.fields().stream().anyMatch(field -> field.id().equals(mapping.fieldId()) && field.required());
                diagnostic(issues, mapping, "MISSING_SOURCE_VALUE", required || mapping.missing() == Missing.BLOCK);
                continue;
            }
            if (source.sourceRef() == null || source.sourceVersion() == null || source.locator() == null)
                throw new IllegalArgumentException("Exact source provenance required");
            String type = schema.fields().stream().filter(field -> field.path().equals(mapping.sourcePath()))
                    .findFirst().orElseThrow().type();
            if (!source.confirmed() && protectedType(type)) { diagnostic(issues, mapping, "UNCONFIRMED_PROTECTED_VALUE", true); continue; }
            Object value = source.value();
            if (!typed(value, type, mapping.sourcePath().contains("[]"))) { diagnostic(issues, mapping, "INVALID_SOURCE_VALUE_TYPE", true); continue; }
            if (mapping.mode() == Mode.DETERMINISTIC) {
                try { value = LocalDate.parse(String.valueOf(value)).toString(); }
                catch (java.time.DateTimeException failure) { diagnostic(issues, mapping, "INVALID_ISO_DATE", true); continue; }
            }
            result.add(new Mapped(mapping.fieldId(), value, source.sourceRef(), source.sourceVersion(),
                    source.locator(), mapping.mode(), !source.confirmed() || mapping.mode() == Mode.AUXILIARY_DRAFT));
        }
        inputs.assertCurrent(actor, rule.organization(), rule, purpose);
        boolean complete = issues.stream().noneMatch(Diagnostic::blocking);
        return new Trial(complete, checked.reviewRequired() || result.stream().anyMatch(Mapped::reviewRequired),
                List.copyOf(result), List.copyOf(issues));
    }
    private static boolean protectedType(String type) {
        return List.of("DECIMAL", "LEGAL_REFERENCE", "INTERVIEW_RECORD").contains(type);
    }
    private static boolean typed(Object value, String type, boolean repeated) {
        if (repeated) return value instanceof List<?> items && items.stream().allMatch(item -> typed(item, type, false));
        return switch (type) {
            case "STRING", "LONG_TEXT" -> value instanceof String;
            case "DATE" -> value instanceof String || value instanceof LocalDate;
            case "DECIMAL" -> value instanceof java.math.BigDecimal;
            case "LEGAL_REFERENCE", "INTERVIEW_RECORD" -> value instanceof Map<?, ?> || value instanceof List<?>;
            default -> false;
        };
    }
    private static void diagnostic(List<Diagnostic> issues, Mapping mapping, String code, boolean blocking) {
        issues.add(new Diagnostic(mapping.fieldId(), mapping.sourcePath(), code, blocking));
    }
    private static void require(String actor, Rule rule, String purpose) {
        if (actor == null || actor.isBlank() || purpose == null || purpose.isBlank() || rule == null
                || rule.organization() == null || rule.organization().isBlank() || rule.revision() < 1
                || rule.templateRevision() < 1 || rule.mappings() == null)
            throw new IllegalArgumentException("Exact mapping context required");
    }
}
