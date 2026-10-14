package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Explicit controlled-port component tests, not product I or business acceptance. */
class PenaltyLifecycleTest {
    final Memory store=new Memory();boolean allowed=true;boolean confirmed=true;boolean applicable=true;boolean basis=true;
    final Inputs inputs=new Inputs() {
        public void assertCurrentAccess(String actor,PenaltyPublishChecks.Input i){check();}
        public PenaltyPublishChecks.Basis legalBasis(String a,String o,String id,long rev){check();return new PenaltyPublishChecks.Basis(id,rev,basis?"CONFIRMED":"PENDING","ACTIVE",List.of());}
        public void assertRuleCurrent(String a,Rule r){check();if(r.state()==RuleState.ACTIVE&&!store.latestRule(r.id()).orElseThrow().equals(r))throw new IllegalStateException("Current active head changed");}
        public void assertRuleTransitionCompleted(String a,Rule r){check();if(!store.latestRule(r.id()).orElseThrow().equals(r))throw new IllegalStateException("Current successor changed");}
        public void assertCaseCurrent(String a,CaseInput i){check();}
        public CaseStatus caseStatus(String a,CaseInput i){check();return new CaseStatus(applicable,applicable,confirmed,List.of(),i.dependencies());}
    };
    final Access access=(a,o,k,id,r,act)->check();
    final PenaltyRuleService rules=new PenaltyRuleService(store,access,inputs);
    final PenaltySuggestionService suggestions=new PenaltySuggestionService(store,access,inputs,Clock.systemUTC());
    void check(){if(!allowed)throw new SecurityException("Controlled revoked port");}
    Rule draft(String id) {
        var sem=new PenaltyFormula.Semantics("PROJECT_SCORE","NONE","POINT",2,RoundingMode.HALF_UP);
        var range=new PenaltyFormula.Range(BigDecimal.ZERO,new BigDecimal("100"),"PROJECT-BASIS",1);
        var f=new PenaltyFormula.Binary(PenaltyFormula.Operator.MULTIPLY,new PenaltyFormula.Input("B"),new PenaltyFormula.Constant(new BigDecimal("2"),"PROJECT-FACTOR@1"));
        var bounds=List.of(new PenaltyPublishChecks.Boundary("LOWER",amount("0"),BigDecimal.ZERO),new PenaltyPublishChecks.Boundary("UPPER",amount("50"),new BigDecimal("100")));
        var input=new PenaltyPublishChecks.Input("ORG",true,"PROJECT-BASIS",1,f,sem,range,bounds,List.of());
        return new Rule(id,1,"ORG","Controlled project score",RuleState.DRAFT,new Scope("PROJECT_TYPE","PROJECT-AUTHORITY","PROJECT_PROCEDURE","PROJECT_REGION",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)),null,List.of(),input);
    }
    Map<String,PenaltyFormula.Amount> amount(String n){return Map.of("B",new PenaltyFormula.Amount(new BigDecimal(n),true,"PROJECT-AMOUNT","1","line1"));}
    CaseInput caseInput(Map<String,PenaltyFormula.Amount> amounts) {
        var deps=List.of(new Ref("EVIDENCE","PROJECT-E",1,"a".repeat(64)),new Ref("CONDUCT","PROJECT-C",1,"b".repeat(64)),new Ref("PROCEDURE","PROJECT-P",1,"c".repeat(64)));
        return new CaseInput("PROJECT-CASE",1,"ORG",LocalDate.of(2026,10,1),"PROJECT_TYPE","PROJECT_PROCEDURE","PROJECT_REGION",deps,amounts,List.of(),
            List.of(new ConfirmedFact("PROJECT-FACT","PROJECT-SOURCE","1","line1","CONFIRMED","EXPLICIT_CONTROLLED_PORT_IDENTITY","PROJECT-CONFIRMATION-NOT-BUSINESS")));
    }
    Ref active(String id){rules.save("A","save-"+id,0,draft(id));rules.publish("A","pub-"+id,id,1);return new Ref("PENALTY_RULE",id,2,null);}
    @Test void exactLifecycleImmutableHistoryReplayAndCas() {
        var ref=active("R1");var receipt=rules.publish("A","pub-R1","R1",1);
        assertEquals(2,receipt.revision());assertEquals(2,rules.history("A","R1").size());assertEquals(RuleState.DRAFT,rules.read("A","R1",1).state());
        assertThrows(IllegalArgumentException.class,()->rules.disable("A","stale","R1",1));
        rules.disable("A","off","R1",ref.revision());assertEquals(RuleState.DISABLED,rules.read("A","R1",3).state());
        assertEquals(3,rules.disable("A","off","R1",ref.revision()).revision());
        assertEquals(3,rules.history("A","R1").size());
        assertThrows(IllegalArgumentException.class,()->rules.disable("A","another-off","R1",ref.revision()));
    }
    @Test void missingLegalReviewCannotPublishOrWriteReceipt() {
        rules.save("A","save",0,draft("R"));basis=false;
        var rejection=assertThrows(PenaltyPublishRejected.class,()->rules.publish("A","pub","R",1));assertEquals(List.of("LEGAL_REFERENCE_REVIEW_REQUIRED"),rejection.gaps());
        assertEquals(1,store.ruleHistory("R").size());assertEquals(1,store.receipts.size());
    }
    @Test void multipleOptionsRemainParallelAndReviewIsNotDecision() {
        var candidates=List.of(active("R1"),active("R2"));suggestions.generate("A","gen","S",0,caseInput(amount("12")),candidates);
        var s=suggestions.read("A","S",1);assertEquals(SuggestionState.PENDING_REVIEW,s.state());assertEquals(2,s.options().size());
        assertEquals(new BigDecimal("24.00"),s.options().getFirst().calculation().amount());
        suggestions.review("B","review","S",1,true,"Controlled technical review");
        assertEquals(SuggestionState.REVIEWED,suggestions.read("B","S",2).state());assertEquals("B",suggestions.read("B","S",2).reviewedBy());
    }
    @Test void missingAmountIsNotZeroButConfirmedZeroIsValid() {
        var ref=active("R");suggestions.generate("A","missing","S",0,caseInput(Map.of()),List.of(ref));
        assertEquals(SuggestionState.WAITING_INPUT,suggestions.read("A","S",1).state());assertTrue(suggestions.read("A","S",1).options().isEmpty());
        suggestions.generate("A","zero","S",1,caseInput(amount("0")),List.of(ref));
        assertEquals(new BigDecimal("0.00"),suggestions.read("A","S",2).options().getFirst().calculation().amount());
    }
    @Test void unconfirmedAndInapplicableNeverCalculate() {
        var ref=active("R");confirmed=false;
        suggestions.generate("A","unconfirmed","S1",0,caseInput(amount("10")),List.of(ref));assertTrue(suggestions.read("A","S1",1).options().isEmpty());
        confirmed=true;applicable=false;suggestions.generate("A","inapplicable","S2",0,caseInput(amount("10")),List.of(ref));assertTrue(suggestions.read("A","S2",1).options().isEmpty());
    }
    @Test void outOfRangeAdjustmentIsTraceableButCannotBeReviewed() {
        var ref=active("R");suggestions.generate("A","gen","S",0,caseInput(amount("10")),List.of(ref));
        suggestions.adjust("A","adjust","S",1,"R",new BigDecimal("101"),"Controlled reason","PROJECT-BASIS@1");
        assertThrows(IllegalArgumentException.class,()->suggestions.review("B","review","S",2,true,"Controlled review"));
        assertEquals(2,store.suggestionHistory("S").size());assertFalse(store.receipts.containsKey("review"));
        assertNull(store.suggestion("S",1).orElseThrow().options().getFirst().adjustedAmount());
    }
    @Test void newRevisionDoesNotInheritReviewAndDisabledRulesCannotRerun() {
        var ref=active("R");suggestions.generate("A","gen","S",0,caseInput(amount("10")),List.of(ref));
        suggestions.review("B","review","S",1,true,"Controlled review");
        suggestions.adjust("A","adjust","S",2,"R",new BigDecimal("25"),"Correction","PROJECT-BASIS@1");
        var s=suggestions.read("A","S",3);assertEquals(SuggestionState.PENDING_REVIEW,s.state());assertNull(s.reviewedBy());
        rules.disable("A","off","R",2);assertThrows(IllegalArgumentException.class,()->suggestions.generate("A","rerun","S",3,caseInput(amount("10")),List.of(ref)));
        assertEquals(3,store.suggestionHistory("S").size());
    }
    @Test void revokedActorCannotReplaySuccessReceipt() {
        active("R");allowed=false;
        assertThrows(SecurityException.class,()->rules.publish("A","pub-R","R",1));assertEquals(2,store.ruleHistory("R").size());
    }
    static final class Memory implements Store {
        final Map<String,List<Rule>> rules=new HashMap<>();final Map<String,List<Suggestion>> suggestions=new HashMap<>();final Map<String,Receipt> receipts=new HashMap<>();final Map<String,String> fingerprints=new HashMap<>();
        public Optional<Rule> rule(String id,long r){return ruleHistory(id).stream().filter(x->x.revision()==r).findFirst();}
        public Optional<Rule> latestRule(String id){return ruleHistory(id).stream().reduce((a,b)->b);}
        public List<Rule> ruleHistory(String id){return rules.getOrDefault(id,List.of());}
        public Optional<Suggestion> suggestion(String id,long r){return suggestionHistory(id).stream().filter(x->x.revision()==r).findFirst();}
        public Optional<Suggestion> latestSuggestion(String id){return suggestionHistory(id).stream().reduce((a,b)->b);}
        public List<Suggestion> suggestionHistory(String id){return suggestions.getOrDefault(id,List.of());}
        public void appendRule(Rule r,long e,String a){if(latestRule(r.id()).map(Rule::revision).orElse(0L)!=e)throw new IllegalArgumentException("CAS");rules.computeIfAbsent(r.id(),x->new ArrayList<>()).add(r);}
        public void appendSuggestion(Suggestion s,long e,String a){if(latestSuggestion(s.id()).map(Suggestion::revision).orElse(0L)!=e)throw new IllegalArgumentException("CAS");suggestions.computeIfAbsent(s.id(),x->new ArrayList<>()).add(s);}
        public Receipt command(String k,String f,String a,String o,Supplier<Receipt> m){if(receipts.containsKey(k)){if(!fingerprints.get(k).equals(a+o+f))throw new IllegalArgumentException("Replay conflict");return receipts.get(k);}Receipt r=m.get();receipts.put(k,r);fingerprints.put(k,a+o+f);return r;}
    }
}
