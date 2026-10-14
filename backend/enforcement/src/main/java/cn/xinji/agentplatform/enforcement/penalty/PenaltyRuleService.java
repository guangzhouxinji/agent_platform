package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import static cn.xinji.agentplatform.enforcement.penalty.PenaltyCommands.*;
import java.util.List;
import java.util.Map;

/** Immutable draft revisions, exact-version publishing, and current-access checked idempotent commands. */
public final class PenaltyRuleService {
    private final Store store;
    private final Access access;
    private final Inputs inputs;
    private final PenaltyPublishChecks checks;
    public PenaltyRuleService(Store store,Access access,Inputs inputs) {
        this.store=java.util.Objects.requireNonNull(store);this.access=java.util.Objects.requireNonNull(access);
        this.inputs=java.util.Objects.requireNonNull(inputs);this.checks=new PenaltyPublishChecks(inputs);
    }
    public Receipt save(String actor,String key,long expected,Rule input) {
        command(actor,key);validateDraft(input);if(expected<0)throw new IllegalArgumentException("Expected revision required");
        Rule draft=copy(input,expected+1,RuleState.DRAFT);
        authorized(actor,draft,"EDIT");
        linkedBasis(actor,draft);
        Receipt result=store.command(key,hash(Map.of("expected",expected,"draft",draft)),actor,"SAVE_PENALTY_RULE",()->{
            current(draft.id(),expected,draft.organization());authorized(actor,draft,"EDIT");linkedBasis(actor,draft);
            store.appendRule(draft,expected,actor);authorized(actor,draft,"EDIT");linkedBasis(actor,draft);
            return new Receipt(key,"SAVE_PENALTY_RULE","PENALTY_RULE",draft.id(),draft.revision());
        });authorized(actor,draft,"EDIT");return result;
    }
    public Rule read(String actor,String id,long revision) {
        Rule rule=store.rule(id,revision).orElseThrow(()->new SecurityException("Rule unavailable"));
        authorized(actor,rule,"READ");return rule;
    }
    public List<Rule> history(String actor,String id) {
        Rule rule=store.latestRule(id).orElseThrow(()->new SecurityException("Rule unavailable"));
        authorized(actor,rule,"READ");var result=List.copyOf(store.ruleHistory(id));authorized(actor,rule,"READ");return result;
    }
    public PenaltyPublishChecks.Result validate(String actor,String id,long revision) {
        Rule rule=read(actor,id,revision);authorized(actor,rule,"TRIAL");
        var result=checks.validate(actor,rule.calculation());authorized(actor,rule,"TRIAL");return result;
    }
    public PenaltyFormula.Result trial(String actor,String id,long revision,Map<String,PenaltyFormula.Amount> amounts) {
        Rule rule=read(actor,id,revision);authorized(actor,rule,"TRIAL");linkedBasis(actor,rule);
        if(!rule.calculation().procedureApplicable()) {
            authorized(actor,rule,"TRIAL");return new PenaltyFormula.Result("WAITING_INPUT",null,List.of(),List.of("PROCEDURE_NOT_APPLICABLE"));
        }
        var result=new PenaltyFormula().trial(rule.calculation().formula(),rule.calculation().semantics(),rule.calculation().range(),amounts);
        authorized(actor,rule,"TRIAL");linkedBasis(actor,rule);return result;
    }
    public Receipt publish(String actor,String key,String id,long expected) {return transition(actor,key,id,expected,RuleState.ACTIVE,"PUBLISH");}
    public Receipt disable(String actor,String key,String id,long expected) {return transition(actor,key,id,expected,RuleState.DISABLED,"DISABLE");}
    private Receipt transition(String actor,String key,String id,long expected,RuleState next,String action) {
        command(actor,key);Rule prior=read(actor,id,expected);
        // A replay checks the exact current grant, but only a new mutation checks the old ACTIVE head.
        // After our own immutable append the current head is the successor, not the prior revision.
        access.require(actor,prior.organization(),"PENALTY_RULE",prior.id(),prior.revision(),action);
        inputs.assertCurrentAccess(actor,prior.calculation());
        Rule successor=copy(prior,expected+1,next);
        if(next==RuleState.ACTIVE)requirePublication(actor,prior);
        Receipt result=store.command(key,hash(id+"|"+expected+"|"+next),actor,action,()->{
            current(id,expected,prior.organization());authorized(actor,prior,action);
            if(next==RuleState.ACTIVE) {
                if(prior.state()!=RuleState.DRAFT)throw new IllegalArgumentException("Draft required");
                requirePublication(actor,prior);
            }else if(prior.state()!=RuleState.ACTIVE)throw new IllegalArgumentException("Active rule required");
            store.appendRule(successor,expected,actor);
            completed(actor,prior,successor,action);
            if(next==RuleState.ACTIVE)requirePublication(actor,prior);
            return new Receipt(key,action,"PENALTY_RULE",id,expected+1);
        });completed(actor,prior,successor,action);
        if(next==RuleState.ACTIVE)requirePublication(actor,prior);
        return result;
    }
    private void completed(String actor,Rule prior,Rule successor,String action) {
        access.require(actor,prior.organization(),"PENALTY_RULE",prior.id(),prior.revision(),action);
        inputs.assertCurrentAccess(actor,prior.calculation());
        inputs.assertRuleTransitionCompleted(actor,successor);
    }
    private void authorized(String actor,Rule rule,String action) {
        access.require(actor,rule.organization(),"PENALTY_RULE",rule.id(),rule.revision(),action);
        if("READ".equals(action))inputs.assertCurrentAccess(actor,rule.calculation());else inputs.assertRuleCurrent(actor,rule);
    }
    private void current(String id,long expected,String organization) {
        var prior=store.latestRule(id);
        if(prior.map(Rule::revision).orElse(0L)!=expected)throw new IllegalArgumentException("Rule version conflict");
        if(prior.isPresent()&&!organization.equals(prior.get().organization()))throw new SecurityException("Organization mismatch");
    }
    private void linkedBasis(String actor,Rule rule) {
        var c=rule.calculation();require(c.basisRef(),"Legal reference");
        var basis=inputs.legalBasis(actor,rule.organization(),c.basisRef(),c.basisRevision());
        if(basis==null||!c.basisRef().equals(basis.ref())||c.basisRevision()!=basis.revision())throw new SecurityException("Exact linked legal reference unavailable");
    }
    private void requirePublication(String actor,Rule rule) {
        var result=checks.validate(actor,rule.calculation());if(!result.publishable())throw new PenaltyPublishRejected(result.gaps());
    }
    private static void validateDraft(Rule rule) {
        if(rule==null)throw new IllegalArgumentException("Rule required");
        require(rule.id(),"Rule ID");require(rule.organization(),"Organization");require(rule.name(),"Name");
        if(rule.scope()==null||rule.calculation()==null||rule.conditions()==null)throw new IllegalArgumentException("Scope, calculation and conditions required");
        var s=rule.scope();require(s.conductType(),"Conduct type");require(s.authorityRef(),"Authority");require(s.procedure(),"Procedure");require(s.region(),"Region");
        if(s.effectiveFrom()==null||s.effectiveUntil()==null||s.effectiveUntil().isBefore(s.effectiveFrom()))throw new IllegalArgumentException("Effective period required");
        if(!rule.organization().equals(rule.calculation().organization()))throw new SecurityException("Calculation organization mismatch");
        for(Condition c:rule.conditions()) {
            if(c==null||c.evidence()==null||c.basisRevision()<1)throw new IllegalArgumentException("Condition provenance required");
            require(c.kind(),"Condition kind");require(c.description(),"Condition");require(c.basisRef(),"Condition basis");
        }
    }
    private static Rule copy(Rule r,long revision,RuleState state) {
        return new Rule(r.id(),revision,r.organization(),r.name(),state,r.scope(),r.discretionBasis(),List.copyOf(r.conditions()),r.calculation());
    }
}
