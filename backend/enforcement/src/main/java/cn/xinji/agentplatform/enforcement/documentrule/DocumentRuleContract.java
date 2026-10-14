package cn.xinji.agentplatform.enforcement.documentrule;

import java.util.List;
import java.util.Map;

/** Frozen ENF-13 contract: supplied final-product inputs, never inferred business facts. */
public final class DocumentRuleContract {
    private DocumentRuleContract() { }
    public enum Mode { DIRECT, DETERMINISTIC, AUXILIARY_DRAFT }
    public enum Missing { BLOCK, BLANK_WITH_WARNING, OMIT_OPTIONAL }
    public enum State { DRAFT, PUBLISHED, REVIEW_REQUIRED, DISABLED }
    public record Template(String id, long revision, String sha256, List<Field> fields) { }
    public record Field(String id, String type, boolean required, boolean repeated) { }
    public record SchemaField(String path, String type, String sourceRef, boolean confirmed) { }
    public record Schema(String id, String version, List<SchemaField> fields) { }
    public record Mapping(String fieldId, String sourcePath, String schemaVersion, Mode mode,
                          String transform, Missing missing) { }
    public record Rule(String id, long revision, String organization, State state, String templateId,
                       long templateRevision, String templateSha256, String schemaId, String schemaVersion,
                       List<Mapping> mappings) { }
    public record Diagnostic(String fieldId, String sourcePath, String code, boolean blocking) { }
    public record Value(Object value, String sourceRef, String sourceVersion, String locator, boolean confirmed) { }
    public record Mapped(String fieldId, Object value, String sourceRef, String sourceVersion,
                         String locator, Mode mode, boolean reviewRequired) { }
    public record Validation(boolean valid, boolean reviewRequired, List<Diagnostic> diagnostics) { }
    public record Trial(boolean complete, boolean reviewRequired, List<Mapped> fields,
                        List<Diagnostic> diagnostics) { }
    public record Attempt(String id, String ruleId, long revision, String actor, String action,
                          String outcome, String failureCode, java.time.Instant completedAt,
                          Validation validation, Trial trial) { }
    public static final class DependencyChanged extends IllegalArgumentException {
        private final String code;
        public DependencyChanged(String message) { this("TEMPLATE_DRIFT",message); }
        public DependencyChanged(String code,String message) { super(message);this.code=code; }
        public String code(){return code;}
    }
    public interface Inputs {
        Template template(String actor, String organization, String id, long revision);
        Schema schema(String actor, String organization, String id, String version);
        Map<String, Value> values(String actor, String organization, Schema schema, String purpose);
        void assertCurrent(String actor, String organization, Rule rule, String purpose);
        default void assertAccess(String actor,String organization,Rule rule,String purpose) { assertCurrent(actor,organization,rule,purpose); }
        default void assertValueAccess(String actor,String organization,Mapped value,String purpose) { throw new IllegalStateException("Value authorization port required"); }
    }
}
