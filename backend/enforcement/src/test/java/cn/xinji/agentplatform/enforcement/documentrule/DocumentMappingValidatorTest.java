package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure C assertions; these are not DM8, HTTP, browser, or business-acceptance evidence. */
class DocumentMappingValidatorTest {
    private final Template template = new Template("TPL", 1, "a".repeat(64), List.of(new Field("party", "STRING", true, false)));
    private final Schema schema = new Schema("SCHEMA", "1", List.of(new SchemaField("party.name", "STRING", "FACT", true)));
    private Rule rule(String path, Mode mode, Missing missing) {
        return new Rule("RULE", 1, "ORG", State.DRAFT, "TPL", 1, "a".repeat(64), "SCHEMA", "1",
                List.of(new Mapping("party", path, "1", mode, null, missing)));
    }
    private DocumentMappingValidator engine(Template current, Schema source, Map<String, Value> values, boolean authorized) {
        return new DocumentMappingValidator(new Inputs() {
            public Template template(String a, String o, String id, long v) { return current; }
            public Schema schema(String a, String o, String id, String v) { return source; }
            public Map<String, Value> values(String a, String o, Schema s, String p) { return values; }
            public void assertCurrent(String a, String o, Rule r, String p) { if (!authorized) throw new SecurityException("Current grant unavailable"); }
        });
    }
    @Test void directTrialPreservesExactSourceAndHasNoMutationPort() {
        var value = new Value("项目主体", "FACT@1", "1", "facts[0].name", true);
        var result = engine(template, schema, Map.of("party.name", value), true).trial("operator", rule("party.name", Mode.DIRECT, Missing.BLOCK), "DOCUMENT_MAPPING");
        assertTrue(result.complete()); assertFalse(result.reviewRequired());
        assertEquals("FACT@1", result.fields().getFirst().sourceRef());
        assertEquals("项目主体", result.fields().getFirst().value());
    }
    @Test void unknownSourcePathAndRequiredOmissionBlockValidation() {
        var engine = engine(template, schema, Map.of(), true);
        assertFalse(engine.validate("operator", rule("unknown", Mode.DIRECT, Missing.BLOCK), "DOCUMENT_MAPPING").valid());
        assertFalse(engine.validate("operator", rule("party.name", Mode.DIRECT, Missing.OMIT_OPTIONAL), "DOCUMENT_MAPPING").valid());
    }
    @Test void missingPartyRemainsIncompleteEvenWithBlankWarning() {
        var result = engine(template, schema, Map.of(), true).trial("operator", rule("party.name", Mode.DIRECT, Missing.BLANK_WITH_WARNING), "DOCUMENT_MAPPING");
        assertFalse(result.complete()); assertTrue(result.fields().isEmpty());
        assertEquals("MISSING_SOURCE_VALUE", result.diagnostics().getFirst().code());
    }
    @Test void auxiliaryCannotInventPartyAndActualWrongTypeIsRejected() {
        assertFalse(engine(template, schema, Map.of(), true).validate("operator", rule("party.name", Mode.AUXILIARY_DRAFT, Missing.BLOCK), "DOCUMENT_MAPPING").valid());
        var result = engine(template, schema, Map.of("party.name", new Value(12, "FACT@1", "1", "name", true)), true)
                .trial("operator", rule("party.name", Mode.DIRECT, Missing.BLOCK), "DOCUMENT_MAPPING");
        assertFalse(result.complete()); assertTrue(result.fields().isEmpty());
    }
    @Test void driftAndCurrentAuthorizationFailClosed() {
        Template changed = new Template("TPL", 2, "b".repeat(64), template.fields());
        assertFalse(engine(changed, schema, Map.of(), true).validate("operator", rule("party.name", Mode.DIRECT, Missing.BLOCK), "DOCUMENT_MAPPING").valid());
        assertThrows(SecurityException.class, () -> engine(template, schema, Map.of(), false)
                .validate("operator", rule("party.name", Mode.DIRECT, Missing.BLOCK), "DOCUMENT_MAPPING"));
    }
    @Test void unconfirmedMoneyNeverDefaultsToZero() {
        var amountTemplate = new Template("TPL", 1, "a".repeat(64), List.of(new Field("amount", "DECIMAL", true, false)));
        var amountSchema = new Schema("SCHEMA", "1", List.of(new SchemaField("penalty.amount", "DECIMAL", "PENALTY", false)));
        var amountRule = new Rule("RULE", 1, "ORG", State.DRAFT, "TPL", 1, "a".repeat(64), "SCHEMA", "1",
                List.of(new Mapping("amount", "penalty.amount", "1", Mode.DIRECT, null, Missing.BLOCK)));
        var result = engine(amountTemplate, amountSchema, Map.of(), true).trial("operator", amountRule, "DOCUMENT_MAPPING");
        assertFalse(result.complete()); assertTrue(result.fields().isEmpty()); assertTrue(result.reviewRequired());
    }
}

