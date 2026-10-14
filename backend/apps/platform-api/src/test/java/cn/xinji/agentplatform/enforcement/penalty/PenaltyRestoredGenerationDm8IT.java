package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real restored browser generation, failure rollback, unchanged checkpoint and original reconciliation. */
class PenaltyRestoredGenerationDm8IT {
 @Test void restoredProductGeneratesAndFaultsWriteNothing() throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();var source=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_SOURCE_INVENTORY"))));var browser=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_BROWSER_EVIDENCE"),"result.json")));
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(req("ENF_PENALTY_SOURCE_URL"),data.getUrl());var db=new JdbcTemplate(data);
  Path root=Path.of(req("ENF_PENALTY_RESTORE_ORIGINALS"));var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
  int count=db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_revision",Integer.class);assertEquals(source.path("records").size()+1,count);
  for(var record:source.path("records")){var row=record.path("row");String key=row.path("ARTIFACT_KEY").asText();byte[] bytes=Files.readAllBytes(root.resolve(key));assertEquals(row.path("ARTIFACT_SHA").asText(),PenaltyArtifacts.sha(bytes));assertEquals(row.path("PAYLOAD").asText(),new String(bytes,java.nio.charset.StandardCharsets.UTF_8));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? AND revision=? AND artifact_key=? AND artifact_sha=?",Integer.class,row.path("RESOURCE_KIND").asText(),row.path("RESOURCE_ID").asText(),row.path("REVISION").asLong(),key,row.path("ARTIFACT_SHA").asText()));}
  var receipts=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_receipt ORDER BY resource_kind,resource_id,revision");var events=db.queryForList("SELECT request_key,resource_kind,resource_id,revision,operation,actor_ref FROM enf11_penalty_event ORDER BY resource_kind,resource_id,revision");assertEquals(receipts,events);assertEquals(count,receipts.size());
  String id=browser.path("generated").path("resourceId").asText();var report=store.suggestion(id,1).orElseThrow();
  assertEquals(req("ENF_PENALTY_EXPECTED_RESTORED_STATE"),report.state().name());
  if(report.state().name().equals("WAITING_INPUT")){assertTrue(report.options().isEmpty());assertTrue(report.gaps().contains("LEGAL_REFERENCE_REVIEW_REQUIRED"));}
  else{assertEquals(2,report.options().size());assertTrue(report.options().stream().allMatch(o->o.calculation().amount().compareTo(new java.math.BigDecimal("24"))==0));}
  var displayed=browser.path("suggestion");assertEquals(report.id(),displayed.path("id").asText());assertEquals(report.revision(),displayed.path("revision").asLong());assertEquals(report.state().name(),displayed.path("state").asText());assertEquals(report.input().caseId(),displayed.path("input").path("caseId").asText());assertEquals(report.input().caseRevision(),displayed.path("input").path("caseRevision").asLong());assertEquals(report.input().applicableDate().toString(),displayed.path("input").path("applicableDate").asText());
  assertEquals(json.readTree(json.writeValueAsBytes(report.input().dependencies())),displayed.path("input").path("dependencies"));assertEquals(json.readTree(json.writeValueAsBytes(report.ruleRefs())),displayed.path("ruleRefs"));
  for(int i=0;i<report.options().size();i++){var option=report.options().get(i);var visible=displayed.path("options").get(i);assertEquals(option.ruleId(),visible.path("ruleId").asText());assertEquals(option.ruleRevision(),visible.path("ruleRevision").asLong());assertEquals(0,option.calculation().amount().compareTo(visible.path("calculation").path("amount").decimalValue()));}
  int failures=0;for(var record:browser.path("records"))if(record.path("method").asText().equals("POST")&&record.path("status").asInt()>=400){String key=record.path("key").asText();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_receipt WHERE request_key=?",Integer.class,key));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_event WHERE request_key=?",Integer.class,key));failures++;}
  assertEquals(4,failures);try(var files=Files.list(root)){assertEquals(count,files.count(),"No orphan file from failed recovery product operation");}
  for(var record:browser.path("originals")){Path file=root.resolve(record.path("name").asText());assertEquals(record.path("sha").asText(),PenaltyArtifacts.sha(Files.readAllBytes(file)));}
  json.writeValue(Path.of(req("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_RESTORED_ENF15_ENF16_BROWSER_DM8_FILE_SHA_EVENT_RECEIPT_NOT_FINAL_ACCEPTANCE","checkpointRecords",source.path("records").size(),"restoredRecords",count,"newProductSuggestions",1,"rejectedOperations",failures,"rejectedReceiptEventWrites",0,"receipts",receipts,"events",events,"suggestion",report,"businessAcceptance","PENDING"));
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
