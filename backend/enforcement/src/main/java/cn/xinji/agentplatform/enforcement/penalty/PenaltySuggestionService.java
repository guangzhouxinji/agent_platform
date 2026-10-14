package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import static cn.xinji.agentplatform.enforcement.penalty.PenaltyCommands.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Conditional parallel suggestions; no option selection or official decision side effect. */
public final class PenaltySuggestionService {
    private final Store store;
    private final Access access;
    private final Inputs inputs;
    private final Clock clock;
    public PenaltySuggestionService(Store store,Access access,Inputs inputs,Clock clock) {
        this.store=java.util.Objects.requireNonNull(store);this.access=java.util.Objects.requireNonNull(access);
        this.inputs=java.util.Objects.requireNonNull(inputs);this.clock=java.util.Objects.requireNonNull(clock);
    }
    public Receipt generate(String actor,String key,String id,long expected,CaseInput input,List<Ref> rules) {
        command(actor,key);require(id,"Suggestion ID");validate(input,rules);
        if(expected<0)throw new IllegalArgumentException("Expected revision required");
        String action=expected==0?"CREATE":"RERUN";
        access.require(actor,input.organization(),"PENALTY_SUGGESTION",id,expected+1,action);
        inputs.assertCaseCurrent(actor,input);assertRules(actor,input.organization(),rules);
        Receipt result=store.command(key,hash(java.util.Map.of("id",id,"expected",expected,"input",input,"rules",rules)),actor,action,()->{
            var prior=current(id,expected,input.organization());
            if(prior!=null&&!prior.input().caseId().equals(input.caseId()))throw new IllegalArgumentException("Case identity cannot change");
            access.require(actor,input.organization(),"PENALTY_SUGGESTION",id,expected+1,action);
            CaseStatus status=inputs.caseStatus(actor,input);
            if(status==null||status.gaps()==null||status.resolvedDependencies()==null)throw new IllegalStateException("Upstream status unavailable");
            var gaps=new ArrayList<>(status.gaps());
            if(!status.authorityApplicable())gaps.add("AUTHORITY_NOT_APPLICABLE");
            if(!status.procedureApplicable())gaps.add("PROCEDURE_NOT_APPLICABLE");
            if(!status.factsConfirmed())gaps.add("FACTS_NOT_CONFIRMED");
            if(!namedFacts(input))gaps.add("CONFIRMED_FACT_INPUT_REQUIRED");
            if(!status.resolvedDependencies().equals(input.dependencies()))gaps.add("DEPENDENCY_COORDINATE_MISMATCH");
            for(String kind:List.of("EVIDENCE","CONDUCT","PROCEDURE"))
                if(input.dependencies().stream().noneMatch(r->kind.equals(r.kind())))gaps.add("MISSING_DEPENDENCY:"+kind);
            var options=new ArrayList<Option>();
            for(Ref ref:rules) {
                Rule rule=active(actor,input.organization(),ref);
                if(!applicable(rule,input)) {gaps.add("RULE_NOT_APPLICABLE:"+rule.id());continue;}
                var checked=new PenaltyPublishChecks(inputs).validate(actor,rule.calculation());
                if(!checked.publishable()){gaps.addAll(checked.gaps());continue;}
                // Do not turn a partially confirmed case into a monetary recommendation.
                if(!gaps.isEmpty())continue;
                var c=rule.calculation();var trial=new PenaltyFormula().trial(c.formula(),c.semantics(),c.range(),input.amounts());
                gaps.addAll(trial.gaps());
                options.add(new Option(rule.id(),rule.revision(),c.basisRef(),c.basisRevision(),c.range(),c.semantics(),trial,null,null,null));
            }
            if(!gaps.isEmpty())options.clear();
            if(options.isEmpty()&&gaps.isEmpty())gaps.add("NO_APPLICABLE_OPTION");
            var state=gaps.isEmpty()?SuggestionState.PENDING_REVIEW:SuggestionState.WAITING_INPUT;
            Suggestion suggestion=new Suggestion(id,expected+1,input.organization(),state,input,List.copyOf(rules),
                List.copyOf(options),List.copyOf(gaps),null,null,null);
            inputs.assertCaseCurrent(actor,input);assertRules(actor,input.organization(),rules);
            store.appendSuggestion(suggestion,expected,actor);
            access.require(actor,input.organization(),"PENALTY_SUGGESTION",id,expected+1,action);
            inputs.assertCaseCurrent(actor,input);assertRules(actor,input.organization(),rules);
            return new Receipt(key,action,"PENALTY_SUGGESTION",id,expected+1);
        });access.require(actor,input.organization(),"PENALTY_SUGGESTION",id,result.revision(),action);
        inputs.assertCaseCurrent(actor,input);assertRules(actor,input.organization(),rules);return result;
    }
    public Suggestion read(String actor,String id,long revision) {
        Suggestion result=store.suggestion(id,revision).orElseThrow(()->new SecurityException("Suggestion unavailable"));
        authorized(actor,result,"READ");return result;
    }
    public List<Suggestion> history(String actor,String id) {
        Suggestion latest=store.latestSuggestion(id).orElseThrow(()->new SecurityException("Suggestion unavailable"));
        authorized(actor,latest,"READ");var history=List.copyOf(store.suggestionHistory(id));authorized(actor,latest,"READ");return history;
    }
    public Receipt adjust(String actor,String key,String id,long expected,String ruleId,BigDecimal amount,String reason,String basis) {
        command(actor,key);require(reason,"Adjustment reason");require(basis,"Adjustment basis");
        if(amount==null)throw new IllegalArgumentException("Explicit amount required");
        Suggestion prior=read(actor,id,expected);authorized(actor,prior,"ADJUST");
        return mutate(actor,key,prior,"ADJUST",hash(id+"|"+expected+"|"+ruleId+"|"+amount+"|"+reason+"|"+basis),()->{
            if(prior.state()==SuggestionState.WAITING_INPUT)throw new IllegalArgumentException("Complete inputs required");
            var options=new ArrayList<Option>();boolean found=false;
            for(Option o:prior.options()) {
                if(!o.ruleId().equals(ruleId)){options.add(o);continue;}
                found=true;BigDecimal rounded=amount.setScale(o.semantics().scale(),o.semantics().rounding());
                // Preserve the original calculation and explicit human adjustment as separate fields.
                options.add(new Option(o.ruleId(),o.ruleRevision(),o.basisRef(),o.basisRevision(),o.range(),o.semantics(),
                    o.calculation(),rounded,reason,basis));
            }
            if(!found)throw new IllegalArgumentException("Option unavailable");
            return copy(prior,SuggestionState.PENDING_REVIEW,List.copyOf(options),null,null,null);
        });
    }
    public Receipt review(String actor,String key,String id,long expected,boolean accepted,String reason) {
        command(actor,key);require(reason,"Review reason");Suggestion prior=read(actor,id,expected);authorized(actor,prior,"REVIEW");
        return mutate(actor,key,prior,"REVIEW",hash(id+"|"+expected+"|"+accepted+"|"+reason),()->{
            if(prior.state()!=SuggestionState.PENDING_REVIEW)throw new IllegalArgumentException("Pending review required");
            if(accepted) {
                CaseStatus status=inputs.caseStatus(actor,prior.input());
                if(status==null||!namedFacts(prior.input())||!status.factsConfirmed()||!status.authorityApplicable()||!status.procedureApplicable()
                    ||status.gaps()==null||!status.gaps().isEmpty()||!prior.input().dependencies().equals(status.resolvedDependencies()))
                    throw new IllegalArgumentException("Current confirmed case required");
                if(!prior.gaps().isEmpty()||prior.options().isEmpty())throw new IllegalArgumentException("Complete options required");
                for(Option o:prior.options()) {
                    BigDecimal amount=o.adjustedAmount()==null?o.calculation().amount():o.adjustedAmount();
                    if(amount==null||amount.compareTo(o.range().minimum())<0||amount.compareTo(o.range().maximum())>0)
                        throw new IllegalArgumentException("Adjustment outside statutory range");
                    Rule r=active(actor,prior.organization(),new Ref("PENALTY_RULE",o.ruleId(),o.ruleRevision(),null));
                    if(!new PenaltyPublishChecks(inputs).validate(actor,r.calculation()).publishable())throw new IllegalArgumentException("Legal basis review required");
                }
            }
            return copy(prior,accepted?SuggestionState.REVIEWED:SuggestionState.RETURNED,prior.options(),reason,actor,clock.instant());
        });
    }
    private Receipt mutate(String actor,String key,Suggestion prior,String action,String fingerprint,java.util.function.Supplier<Suggestion> mutation) {
        Receipt result=store.command(key,fingerprint,actor,action,()->{
            current(prior.id(),prior.revision(),prior.organization());authorized(actor,prior,action);
            Suggestion next=mutation.get();store.appendSuggestion(next,prior.revision(),actor);authorized(actor,prior,action);
            return new Receipt(key,action,"PENALTY_SUGGESTION",prior.id(),next.revision());
        });authorized(actor,prior,action);return result;
    }
    private Rule active(String actor,String org,Ref ref) {
        Rule rule=store.rule(ref.id(),ref.revision()).orElseThrow(()->new SecurityException("Rule unavailable"));
        access.require(actor,org,"PENALTY_RULE",ref.id(),ref.revision(),"READ");
        if(!org.equals(rule.organization()))throw new SecurityException("Cross-organization rule");
        var latest=store.latestRule(ref.id()).orElseThrow();
        if(latest.revision()!=ref.revision()||latest.state()!=RuleState.ACTIVE||rule.state()!=RuleState.ACTIVE)
            throw new IllegalArgumentException("Current active rule required");
        inputs.assertRuleCurrent(actor,rule);return rule;
    }
    private void assertRules(String actor,String org,List<Ref> refs) {for(Ref ref:refs)active(actor,org,ref);}
    private void authorized(String actor,Suggestion s,String action) {
        access.require(actor,s.organization(),"PENALTY_SUGGESTION",s.id(),s.revision(),action);inputs.assertCaseCurrent(actor,s.input());
        if(!"READ".equals(action))assertRules(actor,s.organization(),s.ruleRefs());
    }
    private Suggestion current(String id,long expected,String org) {
        var prior=store.latestSuggestion(id);
        if(prior.map(Suggestion::revision).orElse(0L)!=expected)throw new IllegalArgumentException("Suggestion version conflict");
        if(prior.isPresent()&&!org.equals(prior.get().organization()))throw new SecurityException("Organization mismatch");
        return prior.orElse(null);
    }
    private static boolean applicable(Rule rule,CaseInput input) {
        var s=rule.scope();return rule.calculation().procedureApplicable()&&s.conductType().equals(input.conductType())
            &&s.procedure().equals(input.procedure())&&s.region().equals(input.region())
            &&!input.applicableDate().isBefore(s.effectiveFrom())&&!input.applicableDate().isAfter(s.effectiveUntil());
    }
    private static boolean namedFacts(CaseInput input) {
        return !input.confirmedFacts().isEmpty()&&input.confirmedFacts().stream().allMatch(f->f!=null&&"CONFIRMED".equals(f.state())
            &&present(f.ref())&&present(f.sourceRef())&&present(f.sourceVersion())&&present(f.locator())&&present(f.confirmedBy())&&present(f.confirmationRef()));
    }
    private static boolean present(String value){return value!=null&&!value.isBlank();}
    private static void validate(CaseInput input,List<Ref> rules) {
        if(input==null||input.caseRevision()<1||input.applicableDate()==null||input.dependencies()==null||input.amounts()==null||input.factors()==null||input.confirmedFacts()==null)
            throw new IllegalArgumentException("Exact case and dependencies required");
        require(input.caseId(),"Case ID");require(input.organization(),"Organization");require(input.conductType(),"Conduct type");require(input.procedure(),"Procedure");require(input.region(),"Region");
        if(rules==null||rules.isEmpty())throw new IllegalArgumentException("Candidate rules required");
        for(Ref r:rules)if(r==null||r.id()==null||r.id().isBlank()||!"PENALTY_RULE".equals(r.kind())||r.revision()<1)throw new IllegalArgumentException("Exact candidate rule required");
        if(rules.stream().map(Ref::id).distinct().count()!=rules.size())throw new IllegalArgumentException("Distinct candidate rules required");
        for(Ref r:input.dependencies())if(r==null||r.revision()<1||r.sha256()==null||!r.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact dependency digest required");
    }
    private static Suggestion copy(Suggestion s,SuggestionState state,List<Option> options,String reason,String actor,java.time.Instant time) {
        return new Suggestion(s.id(),s.revision()+1,s.organization(),state,s.input(),s.ruleRefs(),options,s.gaps(),reason,actor,time);
    }
}
