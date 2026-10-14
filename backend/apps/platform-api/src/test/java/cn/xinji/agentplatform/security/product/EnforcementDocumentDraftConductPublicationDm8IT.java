package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductRuleService;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real publication HTTPS/DM8 I, with explicitly controlled configuration examples; NOT public case evidence. */
class EnforcementDocumentDraftConductPublicationDm8IT {
 private final List<Map<String,Object>> checks=new ArrayList<>();
 private EnforcementDocumentDraftPublicAdmissionIT s;
 private Basis basis(String effect,String scope) {
  return new Basis("CONTROLLED_CONFIGURATION_VALIDATION","1","controlled-example-contract",effect,scope,"TEST_ONLY","TEST_ONLY");
 }
 private Input sample(String id,boolean covered,boolean knownRight,List<String> defenses) {
  return new Input("CONTROLLED_EXAMPLE",s.org,"CONTROLLED_CONFIGURATION_EXAMPLE_NOT_CASE","1","CONTROLLED_EXAMPLE_NOT_EVIDENCE",1,
   id,1,Behavior.TRADEMARK_INFRINGEMENT,"CONTROLLED_TIME_NOT_ACTUAL_EVENT",knownRight?new RightAtTime("CONTROLLED_RIGHT_EXAMPLE","1",
   "VERIFIED_AT_BEHAVIOR_TIME","CONTROLLED_TIME_NOT_ACTUAL_EVENT","CONTROLLED_SAMPLE_SOURCE"):null,
   List.of(new Fact("f","MACHINE_CANDIDATE","CONTROLLED_SAMPLE_SOURCE","sample-contract")),
   covered?List.of(new Link("USE","f","CONTROLLED_SAMPLE_SOURCE","sample-contract",Relation.SUPPORT)):List.of(),defenses);
 }
 private ConductRuleService.Example example(Input input) {
  var rule=new Rule(input.ruleId(),1,s.org,input.behavior(),"DRAFT",basis("VERIFIED","CONFIGURATION_TEST_ONLY"),
   List.of(new Element("USE","Controlled deterministic coverage example",true)),List.of("CONTROLLED_DEFENSE"),false,null,null,s.actor);
  var evaluated=new cn.xinji.agentplatform.enforcement.conduct.ConductEvaluator().evaluate(rule,input);
  return new ConductRuleService.Example(input,evaluated.outcome(),evaluated.gaps());
 }
 private String draft(String label,String effect,String scope)throws Exception {
  String id="ENF14-PUB-"+label+"-"+s.run;
  for(String action:List.of("EDIT_RULE","READ_RULE","PUBLISH_RULE","TRIAL_RULE"))s.grant(s.actor,"CONDUCT_RULE",id,1,action,"RULE_MANAGEMENT");
  s.command(s.token,"/conduct-rules",new ConductRuleService.Draft(id,s.org,Behavior.TRADEMARK_INFRINGEMENT,basis(effect,scope),
   List.of(new Element("USE","Controlled deterministic coverage example",true)),List.of("CONTROLLED_DEFENSE"),false,null));
  return id;
 }
 private ConductRuleService.Publication publication(String id,List<String> defenses) {
  return new ConductRuleService.Publication("CODEX_TECHNICAL_CONFIGURATION_REVIEW_NOT_BUSINESS_APPROVAL",
   List.of(example(sample(id,true,true,defenses))),List.of(example(sample(id,false,true,List.of()))));
 }
 private com.fasterxml.jackson.databind.JsonNode post(String path,String key,Object body,int expected)throws Exception {
  var response=s.http.send(s.request(s.token,path).header("Content-Type","application/json").header("Idempotency-Key",key)
   .POST(HttpRequest.BodyPublishers.ofString(s.json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  checks.add(Map.of("path",path,"at",Instant.now().toString(),"httpStatus",response.statusCode(),"expected",expected));
  assertEquals(expected,response.statusCode(),path);
  return expected==200?s.json.readTree(response.body()):s.json.createObjectNode();
 }
 private void unchangedDraft(String id)throws Exception {
  assertEquals(1,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_rule WHERE rule_id=?",Integer.class,id));
  assertEquals("DRAFT",s.db.queryForObject("SELECT state FROM enf7_conduct_rule WHERE rule_id=? AND revision=1",String.class,id));
  for(String table:List.of("enf7_conduct_receipt","enf7_conduct_event"))
   assertEquals(1,s.db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE object_id=?",Integer.class,id));
 }
 @Test void realPublicationPreservesDefenseGapsAndAllFailureGuards()throws Exception {
  s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
  Integer reportsBefore=s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_report WHERE organization_id=?",Integer.class,s.org);
  for(var defenses:List.of(List.<String>of(),List.of("CONTROLLED_DEFENSE_RECORD"))) {
   String id=draft(defenses.isEmpty()?"EMPTY":"PRESENT","VERIFIED","CONFIGURATION_TEST_ONLY");
   String path="/conduct-rules/"+id+"/versions/1/publish",key=UUID.randomUUID().toString();
   var publication=publication(id,defenses);var receipt=post(path,key,publication,200);
   assertEquals(2,receipt.path("version").asLong());assertEquals(receipt,post(path,key,publication,200));
   var changed=new ConductRuleService.Publication("CHANGED_CONTROLLED_REVIEW",publication.positive(),publication.negative());
   post(path,key,changed,409);post(path,UUID.randomUUID().toString(),publication,409);
   s.grant(s.actor,"CONDUCT_RULE",id,2,"READ_RULE","RULE_MANAGEMENT");
   var stored=s.get(s.token,"/conduct-rules/"+id+"/versions/2");assertEquals("ACTIVE",stored.path("state").asText());
   assertFalse(stored.path("noDefensesConfirmed").asBoolean());assertEquals(1,stored.path("defenses").size());
   s.grant(s.actor,"CONDUCT_RULE",id,2,"TRIAL_RULE","RULE_MANAGEMENT");
   var original=sample(id,true,true,defenses);
   var current=new Input(original.id(),original.organization(),original.caseRef(),original.caseVersion(),original.evidenceOrganizationId(),
    original.evidenceOrganizationVersion(),original.ruleId(),2,original.behavior(),original.behaviorAt(),original.right(),original.facts(),original.links(),original.defenseRefs());
   var trial=s.command(s.token,"/conduct-rules/"+id+"/versions/2/trials",current);
   assertEquals("INSUFFICIENT",trial.path("outcome").asText());
   assertEquals(defenses.isEmpty()?"DEFENSE_UNASSESSED":"DEFENSE_REVIEW_REQUIRED",trial.path("gaps").get(0).asText());
   for(String table:List.of("enf7_conduct_rule","enf7_conduct_receipt","enf7_conduct_event"))
    assertEquals(2,s.db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+(table.endsWith("rule")?"rule_id":"object_id")+"=?",Integer.class,id));
   var events=s.db.queryForList("SELECT request_key,actor_ref,operation,object_revision FROM enf7_conduct_event WHERE object_id=? ORDER BY object_revision",id);
   var receipts=s.db.queryForList("SELECT request_key,actor_ref,operation,object_revision FROM enf7_conduct_receipt WHERE object_id=? ORDER BY object_revision",id);
   assertEquals(events,receipts);
   checks.add(Map.of("rule",id,"revision",2,"payloadSha256",s.sha(s.json.writeValueAsBytes(stored)),"events",events,"receipts",receipts,"trial",trial));
   // Leave no controlled published rule consumable by the public-case workflow.
   s.grant(s.actor,"CONDUCT_RULE",id,2,"DISABLE_RULE","RULE_MANAGEMENT");
   post("/conduct-rules/"+id+"/versions/2/disable",UUID.randomUUID().toString(),Map.of(),200);
   assertEquals("DISABLED",s.get(s.token,"/conduct-rules/"+id+"/versions/2").path("state").asText());
   assertEquals(1,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_disabled WHERE rule_id=? AND revision=2",Integer.class,id));
   for(String table:List.of("enf7_conduct_receipt","enf7_conduct_event"))
    assertEquals(3,s.db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE object_id=?",Integer.class,id));
  }
  String id=draft("REJECT","VERIFIED","CONFIGURATION_TEST_ONLY"),path="/conduct-rules/"+id+"/versions/1/publish";
  var standard=publication(id,List.of());var positive=standard.positive().getFirst();
  post(path,UUID.randomUUID().toString(),new ConductRuleService.Publication("CONTROLLED_REVIEW",List.of(new ConductRuleService.Example(positive.input(),Outcome.SUSPECTED,List.of())),standard.negative()),409);unchangedDraft(id);
  post(path,UUID.randomUUID().toString(),new ConductRuleService.Publication("CONTROLLED_REVIEW",List.of(example(sample(id,true,false,List.of()))),standard.negative()),409);unchangedDraft(id);
  post(path,UUID.randomUUID().toString(),new ConductRuleService.Publication("CONTROLLED_REVIEW",List.of(example(sample(id,false,true,List.of()))),standard.negative()),409);unchangedDraft(id);
  post(path,UUID.randomUUID().toString(),new ConductRuleService.Publication("CONTROLLED_REVIEW",standard.positive(),List.of(example(sample(id,true,true,List.of("OTHER_CONTROLLED_DEFENSE"))))),409);unchangedDraft(id);
  for(var invalid:List.of(new String[]{"UNVERIFIED","REVIEW_REQUIRED","CONFIGURATION_TEST_ONLY","409"},new String[]{"SCOPE","VERIFIED","OTHER_SCOPE","403"})) {
   String rejected=draft(invalid[0],invalid[1],invalid[2]);post("/conduct-rules/"+rejected+"/versions/1/publish",UUID.randomUUID().toString(),publication(rejected,List.of()),Integer.parseInt(invalid[3]));unchangedDraft(rejected);
  }
  String revoked=draft("REVOKED","VERIFIED","CONFIGURATION_TEST_ONLY");
  String grant=s.db.queryForObject("SELECT current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type='CONDUCT_RULE' AND resource_id=? AND action_code='PUBLISH_RULE' AND purpose='RULE_MANAGEMENT'",String.class,s.issuer,s.actor,s.org,revoked);
  long version=s.db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,grant);
  s.watermark.change(()->s.grants.revoke(grant,version,"CODEX_CONTROLLED_PUBLICATION_TEST",UUID.randomUUID().toString(),"b".repeat(64)));
  post("/conduct-rules/"+revoked+"/versions/1/publish",UUID.randomUUID().toString(),publication(revoked,List.of()),403);unchangedDraft(revoked);
  assertEquals(reportsBefore,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_report WHERE organization_id=?",Integer.class,s.org));
  s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("publication-compatibility-i.json").toFile(),Map.of("run",s.run,"nature","CONTROLLED_CONFIGURATION_VALIDATION_NOT_PUBLIC_CASE_OR_BUSINESS_ACCEPTANCE","scope","REAL_HTTPS_DUAL_DM8_PUBLICATION_I","checks",checks,"reportGenerated",false,"publicCaseRulePublished",false));
 }
}
