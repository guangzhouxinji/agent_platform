package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** C-only port-contract tests. No in-memory result is claimed as DM8 integration evidence. */
class DocumentRuleServiceTest {
    private final MemoryStore store = new MemoryStore();
    private boolean permitted = true;
    private boolean drift;
    private final Inputs inputs = new Inputs() {
        public Template template(String a, String o, String id, long r) {
            return new Template(id, drift ? 2 : r, "a".repeat(64), List.of(new Field("party", "STRING", true, false)));
        }
        public Schema schema(String a, String o, String id, String v) {
            return new Schema(id, v, List.of(new SchemaField("party.name", "STRING", "FACT", true)));
        }
        public Map<String, Value> values(String a, String o, Schema s, String p) {
            return Map.of("party.name", new Value("project subject", "FACT", "1", "party.name", true));
        }
        public void assertCurrent(String a, String o, Rule r, String p) { if (!permitted) throw new SecurityException(); }
        public void assertValueAccess(String a,String o,Mapped v,String p){if(!permitted)throw new SecurityException();}
    };
    private final DocumentRuleService service = new DocumentRuleService(store,
            (a, o, id, r, action) -> { if (!permitted) throw new SecurityException(); }, inputs);
    private Rule input(String path) {
        return new Rule("RULE", 99, "ORG", State.PUBLISHED, "TPL", 1, "a".repeat(64), "SCHEMA", "1",
                List.of(new Mapping("party", path, "1", Mode.DIRECT, null, Missing.BLOCK)));
    }
    @Test void createPublishDisablePreservesImmutableHistoryAndReceipts() {
        service.save("operator", "save", 0, input("party.name"));
        assertEquals(State.DRAFT, service.read("operator", "RULE", 1).state());
        service.publish("operator", "publish", "RULE", 1, "DOCUMENT_MAPPING");
        service.disable("operator", "disable", "RULE", 2);
        assertEquals(List.of(State.DRAFT, State.PUBLISHED, State.DISABLED),
                service.history("operator", "RULE").stream().map(Rule::state).toList());
        assertEquals(3, store.receipts.size());
        assertThrows(IllegalArgumentException.class, () -> service.trial("operator", "RULE", 3, "DOCUMENT_MAPPING"));
    }
    @Test void replayDoesNotAppendAndChangedPayloadIsRejected() {
        service.save("operator", "same", 0, input("party.name"));
        service.save("operator", "same", 0, input("party.name"));
        assertEquals(1, store.rows.size());
        assertThrows(IllegalArgumentException.class, () -> service.save("operator", "same", 0, input("unknown")));
        assertEquals(1, store.receipts.size());
    }
    @Test void staleRevisionAndCrossOrganizationNeverWrite() {
        service.save("operator", "save", 0, input("party.name"));
        assertThrows(IllegalArgumentException.class, () -> service.save("operator", "stale", 0, input("party.name")));
        Rule source = input("party.name");
        Rule other = new Rule(source.id(), 2, "OTHER", State.DRAFT, source.templateId(), 1,
                source.templateSha256(), source.schemaId(), "1", source.mappings());
        assertThrows(SecurityException.class, () -> service.save("operator", "other", 1, other));
        assertEquals(1, store.rows.size()); assertEquals(1, store.receipts.size());
    }
    @Test void invalidMappingAndTemplateDriftCannotPublish() {
        service.save("operator", "save", 0, input("unknown"));
        assertThrows(IllegalArgumentException.class, () -> service.publish("operator", "invalid", "RULE", 1, "DOCUMENT_MAPPING"));
        service.save("operator", "edit", 1, input("party.name"));
        drift = true;
        assertThrows(IllegalArgumentException.class, () -> service.publish("operator", "drift", "RULE", 2, "DOCUMENT_MAPPING"));
        assertEquals(2, store.rows.size()); assertEquals(2, store.receipts.size());
    }
    @Test void withdrawnAccessBlocksReadTrialReplayAndMutation() {
        service.save("operator", "save", 0, input("party.name")); permitted = false;
        assertThrows(SecurityException.class, () -> service.read("operator", "RULE", 1));
        assertThrows(SecurityException.class, () -> service.trial("operator", "RULE", 1, "DOCUMENT_MAPPING"));
        assertThrows(SecurityException.class, () -> service.save("operator", "save", 0, input("party.name")));
        assertEquals(1, store.rows.size()); assertEquals(1, store.receipts.size());
    }
    @Test void trialHasProvenanceAndNoBusinessReceiptSideEffect() {
        service.save("operator", "save", 0, input("party.name"));
        var trial = service.trial("operator", "RULE", 1, "DOCUMENT_MAPPING");
        assertTrue(trial.complete()); assertEquals("FACT", trial.fields().getFirst().sourceRef());
        assertEquals(1, store.rows.size()); assertEquals(1, store.receipts.size());
        assertEquals(1,service.attempts("operator","RULE").size());
        assertEquals("TRIAL",store.attempts.getFirst().action());
    }
    @Test void publishedDependencyDriftCreatesOneReviewRevisionAndRetainsOldMappings() {
        service.save("operator","save",0,input("party.name"));service.publish("operator","publish","RULE",1,"DOCUMENT_MAPPING");
        drift=true;var result=service.validate("operator","RULE",2,"DOCUMENT_MAPPING");
        assertFalse(result.valid());assertTrue(result.reviewRequired());
        assertEquals(State.PUBLISHED,store.rows.get(1).state());assertEquals(State.REVIEW_REQUIRED,store.rows.get(2).state());
        assertEquals(store.rows.get(1).mappings(),store.rows.get(2).mappings());
        service.validate("operator","RULE",2,"DOCUMENT_MAPPING");
        assertEquals(3,store.rows.size());assertEquals(3,store.receipts.size());assertEquals(2,store.attempts.size());
        assertThrows(IllegalArgumentException.class,()->service.trial("operator","RULE",3,"DOCUMENT_MAPPING"));
    }
    private static final class MemoryStore implements DocumentRuleService.Store {
        final List<Rule> rows = new ArrayList<>();
        final Map<String, DocumentRuleService.Receipt> receipts = new HashMap<>();
        final Map<String, String> fingerprints = new HashMap<>();
        final List<Attempt> attempts = new ArrayList<>();
        public void attempt(Attempt a,Runnable verify) { verify.run(); attempts.add(a); }
        public List<Attempt> attempts(String id) {return attempts.stream().filter(a->a.ruleId().equals(id)).toList();}
        public Optional<Rule> latest(String id) { return rows.stream().filter(r -> r.id().equals(id)).max(Comparator.comparingLong(Rule::revision)); }
        public Optional<Rule> read(String id, long revision) { return rows.stream().filter(r -> r.id().equals(id) && r.revision() == revision).findFirst(); }
        public List<Rule> history(String id) { return rows.stream().filter(r -> r.id().equals(id)).toList(); }
        public void append(Rule r, long expected, String actor) {
            if (latest(r.id()).map(Rule::revision).orElse(0L) != expected) throw new IllegalArgumentException(); rows.add(r);
        }
        public DocumentRuleService.Receipt command(String key, String fingerprint, String actor, String operation, Supplier<DocumentRuleService.Receipt> mutation) {
            String exact = actor + "|" + operation + "|" + fingerprint;
            if (receipts.containsKey(key)) {
                if (!exact.equals(fingerprints.get(key))) throw new IllegalArgumentException("Idempotency conflict");
                return receipts.get(key);
            }
            var receipt = mutation.get(); fingerprints.put(key, exact); receipts.put(key, receipt); return receipt;
        }
    }
}
