package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductEvaluator;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Component counterexample only: explicit controlled inputs, never actual case/rights evidence. */
class EnforcementDocumentDraftPublicationCompatibilityTest {
 @Test void finalRuntimeLoadsExistingEvidenceAndConductProductPackages() {
  var scan=ProductEnforcementApplication.class.getAnnotation(org.springframework.context.annotation.ComponentScan.class);
  var packages=Arrays.stream(scan.basePackageClasses()).map(Class::getPackageName).toList();
  assertTrue(packages.contains("cn.xinji.agentplatform.enforcement.evidence"));
  assertTrue(packages.contains("cn.xinji.agentplatform.enforcement.conduct"));
 }
 @Test void defenseBearingCaseEvaluationStillPreservesPendingReviewGaps() {
  var basis=new Basis("CONTROLLED_COMPONENT_BASIS","1","component","VERIFIED","COMPONENT_ONLY","TEST","TEST");
  var rule=new Rule("component-rule",1,"component-org",Behavior.TRADEMARK_INFRINGEMENT,"DRAFT",basis,
    List.of(new Element("USE","Controlled required element",true)),List.of("CONTROLLED_DEFENSE"),false,null,null,"component");
  var fact=new Fact("f","MACHINE_CANDIDATE","component-source","component-locator");
  var link=new Link("USE","f","component-source","component-locator",Relation.SUPPORT);
  var right=new RightAtTime("controlled-right","1","VERIFIED_AT_BEHAVIOR_TIME","CONTROLLED_TIME","component-source");
  for(var defenses:List.of(List.<String>of(),List.of("CONTROLLED_DEFENSE_EVALUATION"))) {
   var input=new Input("component-input","component-org","component-case","1","component-evidence",1,
     rule.id(),1,rule.behavior(),"CONTROLLED_TIME",right,List.of(fact),List.of(link),defenses);
   var actual=new ConductEvaluator().evaluate(rule,input);
   assertEquals(Outcome.INSUFFICIENT,actual.outcome());
   assertEquals(List.of(defenses.isEmpty()?"DEFENSE_UNASSESSED":"DEFENSE_REVIEW_REQUIRED"),actual.gaps());
   // Publication compatibility must not erase these case-evaluation gaps.
   assertFalse(actual.gaps().isEmpty());
  }
 }
}
