package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.enforcement.legalreference.FileLegalKnowledgeSource;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real HTTPS/dual-DM8 draft preparation; NOT report generation or document product I. */
class EnforcementDocumentDraftPublicLegalPreparationIT {
 @Test void persistSameCaseTrademarkDraftWithoutInventingPublicationOrReport()throws Exception {
  var s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
  var sourceRoot=s.assets.getParent().resolve("ENF14-CNIPA-LEGAL-SOURCE-20261003-56");
  var catalog=s.json.readTree(Files.readAllBytes(sourceRoot.resolve("legal-catalog.json")));
  var adapter=new FileLegalKnowledgeSource(sourceRoot.resolve("legal-catalog.json"),s.json);
  for(var candidate:catalog.at("/sources/0/candidates")) {
   var exact=adapter.exact(candidate.path("sourceId").asText(),"1",candidate.path("candidateId").asText());
   assertEquals("REVIEW_REQUIRED",exact.effectStatus());assertEquals("REVIEW_REQUIRED",exact.reviewStatus());
   assertFalse(exact.gaps().isEmpty());assertEquals(s.sha(exact.verbatimText().getBytes(java.nio.charset.StandardCharsets.UTF_8)),exact.verbatimSha256());
  }
  var draft=(com.fasterxml.jackson.databind.node.ObjectNode)s.json.readTree(Files.readAllBytes(sourceRoot.resolve("conduct-rule-draft.json")));
  String id="ENF14-CNIPA-TRADEMARK-"+s.run;draft.put("id",id);
  assertEquals(s.org,draft.path("organization").asText());
  s.grant(s.actor,"CONDUCT_RULE",id,1,"EDIT_RULE","RULE_MANAGEMENT");
  s.grant(s.actor,"CONDUCT_RULE",id,1,"READ_RULE","RULE_MANAGEMENT");
  String caseRef="CNIPA-GUIDANCE-CASE-1-PUBLIC-ENF14-PUBLIC-CASE-20261003-45";
  Integer reportsBefore=s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_report WHERE organization_id=?",Integer.class,s.org);
  var receipt=s.command(s.token,"/conduct-rules",draft);
  assertEquals(id,receipt.path("resultId").asText());assertEquals(1,receipt.path("version").asLong());
  var stored=s.get(s.token,"/conduct-rules/"+id+"/versions/1");
  assertEquals("DRAFT",stored.path("state").asText());assertEquals("TRADEMARK_INFRINGEMENT",stored.path("behavior").asText());
  assertEquals("REVIEW_REQUIRED",stored.at("/basis/effectStatus").asText());assertFalse(stored.path("noDefensesConfirmed").asBoolean());
  var row=s.json.readTree(s.db.queryForObject("SELECT payload FROM enf7_conduct_rule WHERE rule_id=? AND revision=1",String.class,id));
  assertEquals(stored,row);
  String key=receipt.path("requestId").asText();assertFalse(key.isBlank());
  assertEquals(1,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_receipt WHERE request_key=? AND operation='DRAFT_CONDUCT_RULE'",Integer.class,key));
  assertEquals(1,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_event WHERE request_key=? AND operation='DRAFT_CONDUCT_RULE'",Integer.class,key));
  assertEquals(reportsBefore,s.db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_report WHERE organization_id=?",Integer.class,s.org));
  var result=new LinkedHashMap<String,Object>();
  result.put("run",s.run);result.put("scope","HTTPS_DUAL_DM8_DRAFT_PREPARATION_NOT_DOCUMENT_OR_REPORT_I");
  result.put("caseRef",caseRef);result.put("candidatesVerified",2);result.put("rule",stored);result.put("receipt",receipt);
  result.put("events",1);result.put("receipts",1);result.put("reportGenerated",false);result.put("referenceConfirmed",false);result.put("commands",s.commands);
  s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("same-case-legal-preparation.json").toFile(),result);
 }
}
