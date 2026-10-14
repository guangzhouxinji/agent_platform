package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** ENF-13 immutable revision lifecycle; the store owns atomic CAS, receipt and event writes. */
public final class DocumentRuleService {
    public record Receipt(String requestId, String operation, String ruleId, long revision) { }
    public interface Store {
        Optional<Rule> latest(String id);
        Optional<Rule> read(String id, long revision);
        List<Rule> history(String id);
        void append(Rule rule, long expectedRevision, String actor);
        Receipt command(String key, String fingerprint, String actor, String operation, Supplier<Receipt> mutation);
        void attempt(Attempt attempt, Runnable verifyCurrentAccess);
        List<Attempt> attempts(String id);
    }
    public interface Access { void require(String actor, String organization, String id, long revision, String action); }
    private final Store store;
    private final Access access;
    private final DocumentMappingValidator validator;
    private final Inputs inputs;
    public DocumentRuleService(Store store, Access access, Inputs inputs) {
        this.store = java.util.Objects.requireNonNull(store);
        this.access = java.util.Objects.requireNonNull(access);
        this.validator = new DocumentMappingValidator(java.util.Objects.requireNonNull(inputs));
        this.inputs = inputs;
    }
    public Receipt save(String actor, String key, long expectedRevision, Rule input) {
        requireCommand(actor, key);
        if (input == null || blank(input.id()) || blank(input.organization()) || input.mappings() == null || expectedRevision < 0)
            throw new IllegalArgumentException("Exact rule input required");
        long revision = expectedRevision + 1;
        Rule draft = copy(input, revision, State.DRAFT);
        access.require(actor, draft.organization(), draft.id(), revision, "EDIT");
        Receipt receipt = store.command(key, hash(expectedRevision + draft.toString()), actor, "SAVE_DOCUMENT_RULE", () -> {
            requireExpected(input.id(), expectedRevision, input.organization());
            access.require(actor, draft.organization(), draft.id(), revision, "EDIT");
            // A draft may have mapping diagnostics, but linked inputs and their current permissions must exist.
            validator.validate(actor, draft, "DOCUMENT_MAPPING");
            store.append(draft, expectedRevision, actor);
            access.require(actor, draft.organization(), draft.id(), revision, "EDIT");
            validator.validate(actor, draft, "DOCUMENT_MAPPING");
            return new Receipt(key, "SAVE_DOCUMENT_RULE", draft.id(), revision);
        });
        access.require(actor, draft.organization(), draft.id(), receipt.revision(), "EDIT");
        return receipt;
    }
    public Rule read(String actor, String id, long revision) {
        Rule result = store.read(id, revision).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, result.organization(), id, revision, "READ");
        return result;
    }
    public List<Rule> history(String actor, String id) {
        Rule latest = store.latest(id).orElseThrow(() -> new SecurityException("Rule unavailable"));
        access.require(actor, latest.organization(), id, latest.revision(), "READ");
        return List.copyOf(store.history(id));
    }
    public Validation validate(String actor, String id, long revision, String purpose) {
        Rule rule = read(actor, id, revision);
        access.require(actor, rule.organization(), id, revision, "VALIDATE");
        Validation result;
        try { result=validator.validate(actor,rule,purpose); }
        catch(DependencyChanged changed){result=changedValidation(changed.code());}
        catch(IllegalStateException failure){recordAttempt(actor,rule,"VALIDATE","FAILED","DEPENDENCY_UNAVAILABLE",null,null);throw failure;}
        recordAttempt(actor,rule,"VALIDATE",result.valid()?"COMPLETED":"REVIEW_REQUIRED",null,result,null);
        return result;
    }
    public Trial trial(String actor, String id, long revision, String purpose) {
        Rule rule = read(actor, id, revision);
        access.require(actor, rule.organization(), id, revision, "TRIAL");
        if (rule.state() == State.DISABLED || rule.state() == State.REVIEW_REQUIRED) {
            recordAttempt(actor,rule,"TRIAL","FAILED","RULE_NOT_USABLE",null,null);
            throw new IllegalArgumentException("Rule is not usable");
        }
        Trial result;
        try { result=validator.trial(actor,rule,purpose); }
        catch(DependencyChanged changed){result=new Trial(false,true,List.of(),changedValidation(changed.code()).diagnostics());}
        catch(IllegalStateException failure){recordAttempt(actor,rule,"TRIAL","FAILED","DEPENDENCY_UNAVAILABLE",null,null);throw failure;}
        recordAttempt(actor,rule,"TRIAL",result.complete()?"COMPLETED":"REVIEW_REQUIRED",null,null,result);
        return result;
    }
    public List<Attempt> attempts(String actor,String id) {
        Rule latest=store.latest(id).orElseThrow(()->new SecurityException("Rule unavailable"));
        access.require(actor,latest.organization(),id,latest.revision(),"READ");
        inputs.assertAccess(actor,latest.organization(),latest,"DOCUMENT_MAPPING");
        List<Attempt> result=store.attempts(id);
        for(Attempt attempt:result)if(attempt.trial()!=null)for(Mapped field:attempt.trial().fields())
            inputs.assertValueAccess(actor,latest.organization(),field,"DOCUMENT_MAPPING");
        access.require(actor,latest.organization(),id,latest.revision(),"READ");
        inputs.assertAccess(actor,latest.organization(),latest,"DOCUMENT_MAPPING");
        for(Attempt attempt:result)if(attempt.trial()!=null)for(Mapped field:attempt.trial().fields())
            inputs.assertValueAccess(actor,latest.organization(),field,"DOCUMENT_MAPPING");
        return List.copyOf(result);
    }
    private void recordAttempt(String actor,Rule rule,String action,String outcome,String code,Validation validation,Trial trial) {
        var attempt=new Attempt(java.util.UUID.randomUUID().toString(),rule.id(),rule.revision(),actor,action,outcome,code,java.time.Instant.now(),validation,trial);
        store.attempt(attempt,()->{access.require(actor,rule.organization(),rule.id(),rule.revision(),action);
            inputs.assertAccess(actor,rule.organization(),rule,"DOCUMENT_MAPPING");
            List<Diagnostic> diagnostics=validation!=null?validation.diagnostics():trial!=null?trial.diagnostics():List.of();
            if(diagnostics.stream().anyMatch(d->List.of("TEMPLATE_DRIFT","SCHEMA_DRIFT").contains(d.code())))markReview(actor,rule,action);
        });
        access.require(actor,rule.organization(),rule.id(),rule.revision(),action);
        inputs.assertAccess(actor,rule.organization(),rule,"DOCUMENT_MAPPING");
    }
    private static Validation changedValidation(String code) { return new Validation(false,true,List.of(new Diagnostic("","",code,true))); }
    private void markReview(String actor,Rule rule,String action) {
        if(rule.state()!=State.PUBLISHED)return;
        var latest=store.latest(rule.id()).orElseThrow();
        if(latest.revision()!=rule.revision()&&latest.state()==State.REVIEW_REQUIRED)return;
        String key="dependency-review-"+hash(rule.id()+"|"+rule.revision());
        store.command(key,hash(rule.toString()),actor,"DEPENDENCY_REVIEW",()->{
            requireExpected(rule.id(),rule.revision(),rule.organization());
            access.require(actor,rule.organization(),rule.id(),rule.revision(),action);
            inputs.assertAccess(actor,rule.organization(),rule,"DOCUMENT_MAPPING");
            store.append(copy(rule,rule.revision()+1,State.REVIEW_REQUIRED),rule.revision(),actor);
            access.require(actor,rule.organization(),rule.id(),rule.revision(),action);
            inputs.assertAccess(actor,rule.organization(),rule,"DOCUMENT_MAPPING");
            return new Receipt(key,"DEPENDENCY_REVIEW",rule.id(),rule.revision()+1);
        });
    }
    public Receipt publish(String actor, String key, String id, long expectedRevision, String purpose) {
        return transition(actor, key, id, expectedRevision, State.PUBLISHED, "PUBLISH", purpose);
    }
    public Receipt disable(String actor, String key, String id, long expectedRevision) {
        return transition(actor, key, id, expectedRevision, State.DISABLED, "DISABLE", "DOCUMENT_MAPPING");
    }
    private Receipt transition(String actor, String key, String id, long expected, State next, String action, String purpose) {
        requireCommand(actor, key);
        Rule prior = read(actor, id, expected);
        access.require(actor, prior.organization(), id, expected, action);
        Receipt receipt = store.command(key, hash(id + "|" + expected + "|" + next + "|" + purpose), actor, action, () -> {
            requireExpected(id, expected, prior.organization());
            access.require(actor, prior.organization(), id, expected, action);
            if (next == State.PUBLISHED) {
                if (prior.state() != State.DRAFT) throw new IllegalArgumentException("Current draft required");
                Validation checked = validator.validate(actor, prior, purpose);
                if (!checked.valid()) throw new IllegalArgumentException("Mapping not publishable");
            } else if (prior.state() != State.PUBLISHED && prior.state() != State.REVIEW_REQUIRED)
                throw new IllegalArgumentException("Published rule required");
            store.append(copy(prior, expected + 1, next), expected, actor);
            access.require(actor, prior.organization(), id, expected, action);
            if (next == State.PUBLISHED && !validator.validate(actor, prior, purpose).valid())
                throw new IllegalArgumentException("Mapping changed before commit");
            return new Receipt(key, action, id, expected + 1);
        });
        access.require(actor, prior.organization(), id, expected, action);
        return receipt;
    }
    private void requireExpected(String id, long expected, String org) {
        var current = store.latest(id);
        if (current.map(Rule::revision).orElse(0L) != expected) throw new IllegalArgumentException("Rule version conflict");
        if (current.isPresent() && !org.equals(current.get().organization())) throw new SecurityException("Organization mismatch");
    }
    private static Rule copy(Rule r, long revision, State state) {
        return new Rule(r.id(), revision, r.organization(), state, r.templateId(), r.templateRevision(),
                r.templateSha256(), r.schemaId(), r.schemaVersion(), List.copyOf(r.mappings()));
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static void requireCommand(String actor, String key) {
        if (blank(actor) || blank(key)) throw new IllegalArgumentException("Actor and idempotency key required");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
