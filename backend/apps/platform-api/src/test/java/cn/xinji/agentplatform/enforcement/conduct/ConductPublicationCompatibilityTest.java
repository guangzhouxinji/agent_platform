package cn.xinji.agentplatform.enforcement.conduct;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deterministic publication classifier C; controlled inputs, no product/database claims. */
class ConductPublicationCompatibilityTest {
 private final ConductEvaluator evaluator=new ConductEvaluator();
 private Rule rule(boolean noDefense) {
  return new Rule("controlled-rule",1,"controlled-org",Behavior.TRADEMARK_INFRINGEMENT,"DRAFT",
   new Basis("CONTROLLED_CONFIGURATION","1","fixture","VERIFIED","TEST","TEST","TEST"),
   List.of(new Element("USE","Controlled element",true)),noDefense?List.of():List.of("CONTROLLED_DEFENSE"),
   noDefense,noDefense?"CONTROLLED_CONFIRMED_NO_DEFENSE":null,null,"controlled-author");
 }
 private Input input(Rule rule,boolean supported,boolean knownRight,List<String> defenses) {
  return new Input("controlled-input","controlled-org","controlled-case","1","controlled-evidence",1,
   rule.id(),1,rule.behavior(),"CONTROLLED_TIME",knownRight?new RightAtTime("controlled-right","1",
   "VERIFIED_AT_BEHAVIOR_TIME","CONTROLLED_TIME","controlled-source"):null,
   List.of(new Fact("fact","MACHINE_CANDIDATE","controlled-source","fixture")),
   supported?List.of(new Link("USE","fact","controlled-source","fixture",Relation.SUPPORT)):List.of(),defenses);
 }
 private ConductRuleService.Example example(Rule rule,Input input) {
  var actual=evaluator.evaluate(rule,input);
  return new ConductRuleService.Example(input,actual.outcome(),actual.gaps());
 }
 private void classify(Rule rule,ConductRuleService.Example example,boolean positive,Set<String> seen)throws Exception {
  var method=ConductRuleService.class.getDeclaredMethod("verifyExample",Rule.class,ConductRuleService.Example.class,boolean.class,HashSet.class);
  method.setAccessible(true);
  try{method.invoke(new ConductRuleService(null,null,null),rule,example,positive,seen);}
  catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw failure;}
 }
 @Test void configuredDefensePositiveKeepsUnassessedGapAndInsufficientOutcome()throws Exception {
  var rule=rule(false);var input=input(rule,true,true,List.of());var example=example(rule,input);
  classify(rule,example,true,new HashSet<>());
  assertEquals(List.of("DEFENSE_UNASSESSED"),example.gaps());assertEquals(Outcome.INSUFFICIENT,example.outcome());
  assertEquals(example.gaps(),evaluator.evaluate(rule,input).gaps());
 }
 @Test void suppliedDefensePositiveKeepsReviewGapAndInsufficientOutcome()throws Exception {
  var rule=rule(false);var example=example(rule,input(rule,true,true,List.of("CONTROLLED_DEFENSE_RECORD")));
  classify(rule,example,true,new HashSet<>());
  assertEquals(List.of("DEFENSE_REVIEW_REQUIRED"),example.gaps());assertEquals(Outcome.INSUFFICIENT,example.outcome());
 }
 @Test void omittedGapsAndClaimedSuspectedCannotPublish() {
  var rule=rule(false);var input=input(rule,true,true,List.of());
  assertThrows(IllegalArgumentException.class,()->classify(rule,new ConductRuleService.Example(input,Outcome.INSUFFICIENT,List.of()),true,new HashSet<>()));
  assertThrows(IllegalArgumentException.class,()->classify(rule,new ConductRuleService.Example(input,Outcome.SUSPECTED,List.of("DEFENSE_UNASSESSED")),true,new HashSet<>()));
 }
 @Test void missingRightCannotBeHiddenByDefenseException() {
  var rule=rule(false);var example=example(rule,input(rule,true,false,List.of()));
  assertThrows(IllegalArgumentException.class,()->classify(rule,example,true,new HashSet<>()));
 }
 @Test void missingRequiredElementCannotBeHiddenByDefenseException() {
  var rule=rule(false);var example=example(rule,input(rule,false,true,List.of()));
  assertThrows(IllegalArgumentException.class,()->classify(rule,example,true,new HashSet<>()));
 }
 @Test void pendingDefenseAloneIsNotANegativeExample() {
  var rule=rule(false);
  for(var refs:List.of(List.<String>of(),List.of("CONTROLLED_DEFENSE_RECORD"))) {
   var example=example(rule,input(rule,true,true,refs));
   assertThrows(IllegalArgumentException.class,()->classify(rule,example,false,new HashSet<>()));
  }
 }
 @Test void negativeMustShowActualCoverageFailureAndKeepDefenseGap()throws Exception {
  var rule=rule(false);var example=example(rule,input(rule,false,true,List.of()));
  classify(rule,example,false,new HashSet<>());
  assertTrue(example.gaps().contains("ELEMENT:USE:MISSING"));assertTrue(example.gaps().contains("DEFENSE_UNASSESSED"));
 }
 @Test void confirmedNoDefenseBranchStillRequiresZeroGapPositive()throws Exception {
  var rule=rule(true);var example=example(rule,input(rule,true,true,List.of()));
  classify(rule,example,true,new HashSet<>());assertTrue(example.gaps().isEmpty());
  assertThrows(IllegalArgumentException.class,()->classify(rule,example(rule,input(rule,true,false,List.of())),true,new HashSet<>()));
 }
 @Test void duplicateExampleFingerprintStillRejected()throws Exception {
  var rule=rule(false);var example=example(rule,input(rule,true,true,List.of()));var seen=new HashSet<String>();
  classify(rule,example,true,seen);
  assertThrows(IllegalArgumentException.class,()->classify(rule,example,true,seen));
 }
}
