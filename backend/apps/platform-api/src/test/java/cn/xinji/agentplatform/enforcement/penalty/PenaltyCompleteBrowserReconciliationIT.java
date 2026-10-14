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

/** Reconciles actual product browser commands against committed DM8/originals/events/receipts. */
class PenaltyCompleteBrowserReconciliationIT {
 @Test void reconcileActualBrowserLifecycleAndEveryRejectedCommand() throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();Path browser=Path.of(req("ENF_PENALTY_BROWSER_EVIDENCE"));
  var result=json.readTree(Files.readAllBytes(browser.resolve("result.json")));assertEquals("REAL_ENF15_ENF16_PRODUCT_CONTROLLED_INPUT_NOT_BUSINESS_ACCEPTANCE",result.path("scope").asText());
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(data.getUrl().startsWith("jdbc:dm://"));
  var db=new JdbcTemplate(data);Path root=Path.of(req("ENF_PENALTY_PRODUCT_FILE_ROOT"));
  var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
  var originals=new ArrayList<Map<String,Object>>();var receipts=new ArrayList<Map<String,Object>>();var events=new ArrayList<Map<String,Object>>();var ids=new ArrayList<String>();result.path("ruleIds").forEach(n->ids.add(n.asText()));ids.add(result.path("suggestionId").asText());
  for(var id:ids){
   var rows=db.queryForList("SELECT resource_kind,resource_id,revision,state,artifact_key,artifact_sha,payload FROM enf11_penalty_revision WHERE resource_id=? ORDER BY revision",id);
   for(var row:rows){byte[] bytes=Files.readAllBytes(root.resolve(row.get("ARTIFACT_KEY").toString()));assertEquals(row.get("ARTIFACT_SHA"),PenaltyArtifacts.sha(bytes));assertEquals(json.readTree(row.get("PAYLOAD").toString()),json.readTree(bytes));originals.add(row);}
   var rr=db.queryForList("SELECT request_key,operation,resource_kind,resource_id,revision FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
   var ee=db.queryForList("SELECT request_key,operation,resource_kind,resource_id,revision FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);assertEquals(rr,ee);assertEquals(rows.size(),rr.size());receipts.addAll(rr);events.addAll(ee);
  }
  assertEquals(result.path("mutations").size(),originals.size());
  for(var mutation:result.path("mutations")){assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_receipt WHERE request_key=? AND resource_id=? AND revision=?",Integer.class,mutation.path("requestId").asText(),mutation.path("resourceId").asText(),mutation.path("revision").asLong()));}
  assertEquals(List.of("DRAFT","ACTIVE","DISABLED","DRAFT","ACTIVE"),store.ruleHistory(ids.getFirst()).stream().map(r->r.state().name()).toList());
  var history=store.suggestionHistory(result.path("suggestionId").asText());assertEquals(List.of("PENDING_REVIEW","PENDING_REVIEW","REVIEWED","PENDING_REVIEW","WAITING_INPUT","PENDING_REVIEW","PENDING_REVIEW","PENDING_REVIEW","RETURNED","PENDING_REVIEW"),history.stream().map(s->s.state().name()).toList());
  assertEquals(2,history.getFirst().options().size());assertNotNull(history.get(2).reviewedBy());assertNull(history.getLast().reviewedBy());assertTrue(history.getLast().options().stream().allMatch(o->o.adjustedAmount()==null));
  int rejected=0;var records=json.readTree(Files.readAllBytes(browser.resolve("http-records.json")));
  for(var record:records)if("GET".equals(record.path("method").asText()))assertEquals(200,record.path("status").asInt(),"No hidden failed list/detail/history refresh: "+record.path("path"));
  for(var record:records)if("POST".equals(record.path("method").asText())&&record.path("status").asInt()>=400){
   String key=record.path("key").asText();assertFalse(key.isBlank());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_receipt WHERE request_key=?",Integer.class,key));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_event WHERE request_key=?",Integer.class,key));rejected++;
  }
  assertEquals(12,rejected);assertEquals(9,java.util.stream.StreamSupport.stream(result.path("checks").spliterator(),false).filter(c->c.path("name").asText().endsWith("REQUIRED")||c.path("name").asText().equals("STATUTORY_BOUNDARIES_NOT_VERIFIED")||c.path("name").asText().equals("BOUNDARY_TRIAL_MISMATCH")||c.path("name").asText().equals("PROCEDURE_NOT_APPLICABLE")||c.path("name").asText().equals("STATUTORY_BASIS_MISMATCH")).count());
  var fileInventory=new ArrayList<Map<String,String>>();try(var list=Files.list(root)){for(var f:list.sorted().toList()){
   String key=f.getFileName().toString(),sha=PenaltyArtifacts.sha(Files.readAllBytes(f));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_revision WHERE artifact_key=? AND artifact_sha=?",Integer.class,key,sha),"No orphan original from rejected commands");fileInventory.add(Map.of("name",key,"sha",sha));
  }}
  assertEquals(json.valueToTree(fileInventory),result.path("originals"));
  json.writeValue(Path.of(req("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_ENF15_ENF16_BROWSER_DM8_ORIGINAL_SHA_EVENT_RECEIPT_RECONCILIATION_NOT_FINAL_IMP_ACCEPTANCE","originals",originals,"receipts",receipts,"events",events,"rejectedCommands",rejected,"rejectedReceiptEventWrites",0,"fileInventory",fileInventory,"businessAcceptance","PENDING"));
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
