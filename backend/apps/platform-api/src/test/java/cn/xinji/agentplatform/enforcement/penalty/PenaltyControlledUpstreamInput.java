package cn.xinji.agentplatform.enforcement.penalty;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.*;
import cn.xinji.agentplatform.enforcement.material.*;
import cn.xinji.agentplatform.enforcement.evidence.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductContract;
import cn.xinji.agentplatform.enforcement.procedure.ProcedureContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Input-only approved substitute snapshots. These imports are NOT upstream workflow/real-human acceptance. */
final class PenaltyControlledUpstreamInput {
 static final String ACTOR="CONTROLLED_TEST_INPUT_IMPORT_NOT_HUMAN_APPROVAL";
 static Map<String,Object> prepare(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Path root,String run,String org,
         ProductEnforcementAccess sourceAdmission) throws Exception {
  if(Files.exists(root))throw new IllegalStateException("Fresh controlled case root required");Files.createDirectories(root);
  String caseId="ENF-PEN-CASE-"+run,sourceId="ENF-PEN-SOURCE-"+run;
  String observation=UUID.randomUUID().toString(),discovery=UUID.randomUUID().toString(),clue=UUID.randomUUID().toString(),material=UUID.randomUUID().toString();
  String collector="CONTROLLED_TEST_COLLECTOR_NOT_REAL_PERSON",verifier="CONTROLLED_TEST_VERIFIER_NOT_REAL_PERSON";
  var clock=Clock.systemUTC();var now=clock.instant();var until=now.plusSeconds(21600);
  byte[] original=("PROJECT_AUTHORED_FICTIONAL / FUNCTIONAL_TEST_INPUT ONLY\nThe submitted item is labeled ControlledPenaltyItem\nThe observed event time is 2026-10-01\nReference: PENALTY-TEST.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var files=new LocalMaterialFiles(root.resolve("files"));var staged=files.stage(original);
  var materials=new Dm8MaterialRepository(db,tx,clock);
  sourceAdmission.prepareSource(sourceId,1);
  materials.command("input-material-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{
   materials.addSource(new MaterialWork.Source(sourceId,org,"PROJECT_OWNED_INPUT_NOT_BUSINESS_RIGHTS",1,until,List.of("QUERY","SAVE","DISPLAY","ANALYZE"),true,"project-owned-input",1));
   materials.addObservation(new MaterialWork.Observation(observation,sourceId,"PROJECT_RECORD", "1",org,new String(original,java.nio.charset.StandardCharsets.UTF_8),staged.sha256(),null,1));
   materials.addRule(new MaterialWork.Rule(discovery,1,org,sourceId,"ControlledPenaltyItem","CONTROLLED_INPUT_DEFINITION","ACTIVE",ACTOR));
   materials.addClue(new MaterialWork.Clue(clue,observation,discovery,1,org,"MATERIAL_PENDING",1));
   materials.addMaterial(new MaterialWork.Material(material,clue,org,staged.sha256(),staged.key(),"PROJECT_OWNED_INPUT_NOT_BUSINESS_RIGHTS",until,List.of("SAVE","PREVIEW","ANALYZE"),collector,now.toString(),"PENDING_VERIFICATION",1));
   materials.appendMaterialAction(new MaterialWork.MaterialAction(material,1,"UPLOAD",collector,"PROJECT_TEST_ORIGINAL","Imported controlled input, not real collection",staged.sha256(),now),"PENDING_VERIFICATION");
   materials.appendMaterialAction(new MaterialWork.MaterialAction(material,2,"COLLECT_ATTEST",collector,"PROJECT_TEST_ORIGINAL","Controlled collection-chain specimen; not human attestation",staged.sha256(),now),"PENDING_VERIFICATION");
   materials.appendMaterialAction(new MaterialWork.MaterialAction(material,3,"VERIFY",verifier,"PROJECT_TEST_ORIGINAL","Controlled independently named specimen; not real verification",staged.sha256(),now),"VERIFIED");
   return new MaterialWork.Receipt("input-material-"+run,"IMPORT_CONTROLLED_TEST_INPUT",material,3);
  });
  sourceAdmission.activateSource(sourceId,1);
  String evidenceRule=UUID.randomUUID().toString(),evidenceId=UUID.randomUUID().toString(),conductRule=UUID.randomUUID().toString(),conductId=UUID.randomUUID().toString(),procedureRule=UUID.randomUUID().toString(),procedureId=UUID.randomUUID().toString();
  var erules=new Dm8EvidenceRuleStore(db,tx,clock);
  erules.command("input-e-rule-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{
   erules.add(new EvidenceRuleStore.Rule(evidenceRule,1,org,"PROJECT_TEST","CONTROLLED_INPUT_DEFINITION","ACTIVE",List.of(new EvidenceRuleStore.Matter("ITEM",true,"line-prefix:The submitted item is labeled "))),ACTOR);
   return new EvidenceRuleStore.Receipt("input-e-rule-"+run,"IMPORT_CONTROLLED_TEST_INPUT",evidenceRule,1);
  });
  var eref=new EvidenceTrialSource.Ref(material,"3",staged.sha256());
  var erevision=new EvidenceOrganizationStore.Revision(evidenceId,1,org,caseId,"1",evidenceRule,1,"CONFIRMED",List.of(eref),List.of(new EvidenceOrganizationStore.Matter("ITEM","PRESENT")),List.of(new EvidenceOrganizationStore.Link("ITEM",material,3,staged.sha256(),"line 2","ControlledPenaltyItem","SUPPORT_CANDIDATE","CONTROLLED_INPUT","Explicit test-input coverage")),List.of(),List.of(),List.of(),ACTOR,"CONTROLLED_INPUT_REVIEW_NOT_HUMAN","Approved substitute input only",List.of());
  var evidence=new Dm8EvidenceOrganizationStore(db,tx,json,clock);
  evidence.command("input-evidence-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{evidence.append(erevision,ACTOR);return new EvidenceOrganizationStore.Receipt("input-evidence-"+run,evidenceId,1);});
  var conduct=new Dm8ConductStore(db,tx,json,clock);
  var cbasis=new ConductContract.Basis("CONTROLLED_INPUT_DEFINITION","1","input manifest","VERIFIED","PROJECT_TEST","PROJECT_TEST","PROJECT_TEST");
  var crule=new ConductContract.Rule(conductRule,1,org,ConductContract.Behavior.COUNTERFEIT_PATENT,"ACTIVE",cbasis,List.of(new ConductContract.Element("ITEM","Technical input item",true)),List.of(),true,"CONTROLLED_INPUT_DEFINITION", "CONTROLLED_INPUT_REVIEW_NOT_HUMAN",ACTOR);
  conduct.command("input-c-rule-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{conduct.addRule(crule);return new ConductContract.Receipt("input-c-rule-"+run,"IMPORT_CONTROLLED_TEST_INPUT",conductRule,1);});
  var fact=new ConductContract.Fact("PROJECT_FACT","PARTY_STATEMENT",material,"line 2");
  var creport=new ConductContract.Report(conductId,1,org,caseId,"1",evidenceId,1,conductRule,1,ConductContract.Behavior.COUNTERFEIT_PATENT,new ConductContract.ScopeVerification(ConductContract.Applicability.MATCH,"CONTROLLED_INPUT_DEFINITION","1"),"SIMULATED_RIGHT_NOT_REAL_RIGHT","1","SIMULATED_NOT_STATUTORY",List.of(fact),List.of(new ConductContract.ElementFinding("ITEM",ConductContract.FindingState.SUPPORTED,List.of(new ConductContract.Link("ITEM",fact.ref(),material,"line 2",ConductContract.Relation.SUPPORT)),List.of(),List.of())),List.of(),ConductContract.Outcome.SUSPECTED,"REVIEWED","CONTROLLED_INPUT_REVIEW_NOT_HUMAN","Controlled imported input; no actual legal/human finding",0);
  conduct.command("input-conduct-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{conduct.addReport(creport);return new ConductContract.Receipt("input-conduct-"+run,"IMPORT_CONTROLLED_TEST_INPUT",conductId,1);});
  var procedure=new Dm8ProcedureStore(db,tx,json,clock);
  var prule=new ProcedureContract.Rule(procedureRule,1,org,"PROJECT_TEST","PROJECT_TEST","PROJECT_STEP","ACTIVE",new ProcedureContract.Basis("CONTROLLED_INPUT_DEFINITION","1","input manifest","VERIFIED","PROJECT_TEST",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)),new ProcedureContract.ClockRule(ProcedureContract.Unit.CALENDAR_DAY,5,"PROJECT_START",false,"NONE","PROJECT_CALENDAR","1","NONE","NONE",0),List.of("PROJECT_START"),List.of(material+"@3"),ACTOR,"CONTROLLED_INPUT_REVIEW_NOT_HUMAN","CONTROLLED_INPUT_DEFINITION");
  procedure.command("input-p-rule-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{procedure.addRule(prule);return new ProcedureContract.Receipt("input-p-rule-"+run,"IMPORT_CONTROLLED_TEST_INPUT",procedureRule,1);});
  var preport=new ProcedureContract.Report(procedureId,1,org,caseId,"1",procedureRule,1,"PROJECT_CALENDAR","1","COMPLETE",List.of(new ProcedureContract.CheckItem("PROJECT_STEP",ProcedureContract.State.PASS,List.of(material+"@3"),List.of(),LocalDate.of(2026,10,6),List.of("Controlled input result; not a statutory deadline"))),List.of(),null,null,0);
  procedure.command("input-procedure-"+run,staged.sha256(),ACTOR,"IMPORT_CONTROLLED_TEST_INPUT",()->{procedure.addReport(preport);return new ProcedureContract.Receipt("input-procedure-"+run,"IMPORT_CONTROLLED_TEST_INPUT",procedureId,1);});
  var refs=List.of(new PenaltyContract.Ref("EVIDENCE",evidenceId,1,PenaltyArtifacts.sha(json.writeValueAsBytes(erevision))),new PenaltyContract.Ref("CONDUCT",conductId,1,PenaltyArtifacts.sha(json.writeValueAsBytes(creport))),new PenaltyContract.Ref("PROCEDURE",procedureId,1,PenaltyArtifacts.sha(json.writeValueAsBytes(preport))));
  var confirmed=List.of(new PenaltyContract.ConfirmedFact(fact.ref(),material,"3","line 2","CONFIRMED","CONTROLLED_INPUT_CONFIRMATION_NOT_HUMAN","PROJECT_INPUT_MANIFEST"));
  var amounts=Map.of("B",new PenaltyFormula.Amount(new BigDecimal("12"),true,caseId,"1","amounts.B"));
  var input=new PenaltyContract.CaseInput(caseId,1,org,LocalDate.of(2026,10,1),"PROJECT_SCORE_INPUT_NOT_LEGAL","PROJECT_TEST","PROJECT_TEST",refs,amounts,List.of(),confirmed);
  var snapshot=json.createObjectNode().put("caseRef",caseId).put("version","1").put("organization",org).put("caseType","PROJECT_TEST").put("eventRef","PENALTY-TEST").put("conductType",input.conductType()).put("procedure",input.procedure()).put("region",input.region()).put("applicableAt",input.applicableDate().toString()).put("nature","PROJECT_AUTHORED_FICTIONAL / FUNCTIONAL_TEST_INPUT / NOT_BUSINESS_ACCEPTANCE");
  snapshot.set("materials",json.valueToTree(List.of(eref)));snapshot.set("confirmedFacts",json.valueToTree(confirmed));snapshot.set("amounts",json.valueToTree(amounts));
  Path caseFile=root.resolve("case.json");json.writeValue(caseFile.toFile(),snapshot);Files.write(root.resolve("original.txt"),original);
  var result=Map.<String,Object>of("scope","CONTROLLED_UPSTREAM_INPUT_PREPARATION_NOT_UPSTREAM_WORKFLOW_OR_DOMAIN_I_OR_BUSINESS_ACCEPTANCE","input",input,"caseFile",caseFile.toString(),"materialRoot",root.resolve("files").toString(),"materialId",material,"materialKey",staged.key(),"materialSha256",staged.sha256(),"sourceId",sourceId,"reports",List.of(erevision,creport,preport));
  json.writeValue(root.resolve("input-manifest.json").toFile(),result);return result;
 }
}
