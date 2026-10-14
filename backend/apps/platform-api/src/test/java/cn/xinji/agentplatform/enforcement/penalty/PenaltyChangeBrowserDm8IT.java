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

/** Product browser rule/source changes reconciled against actual DM8 and immutable original history. */
class PenaltyChangeBrowserDm8IT {
 @Test void ruleAndSourceChangesRetainHistoryAndRecalculateExplicitly() throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();Path browser=Path.of(req("ENF_PENALTY_BROWSER_EVIDENCE"));var result=json.readTree(Files.readAllBytes(browser.resolve("result.json")));assertEquals("REAL_PRODUCT_RULE_SOURCE_CHANGE_NOT_NEW_LAW_OR_BUSINESS_ACCEPTANCE",result.path("scope").asText());
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(data.getUrl().startsWith("jdbc:dm://"));var db=new JdbcTemplate(data);Path root=Path.of(req("ENF_PENALTY_PRODUCT_FILE_ROOT"));var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
  String rule=result.path("ruleId").asText(),suggestion=result.path("suggestionId").asText();assertEquals(7,store.latestRule(rule).orElseThrow().revision());assertEquals(PenaltyContract.RuleState.ACTIVE,store.latestRule(rule).orElseThrow().state());assertEquals(12,store.latestSuggestion(suggestion).orElseThrow().revision());assertEquals("WAITING_INPUT",store.latestSuggestion(suggestion).orElseThrow().state().name());assertEquals("PENDING_REVIEW",store.suggestion(suggestion,11).orElseThrow().state().name());
  assertEquals(4,result.path("mutations").size());var rows=new ArrayList<Map<String,Object>>();for(var receipt:result.path("mutations")){
   String key=receipt.path("requestId").asText(),id=receipt.path("resourceId").asText();long revision=receipt.path("revision").asLong();var rr=db.queryForList("SELECT request_key,operation,resource_kind,resource_id,revision FROM enf11_penalty_receipt WHERE request_key=?",key);var ee=db.queryForList("SELECT request_key,operation,resource_kind,resource_id,revision FROM enf11_penalty_event WHERE request_key=?",key);assertEquals(1,rr.size());assertEquals(rr,ee);
   var row=db.queryForMap("SELECT payload,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_id=? AND revision=?",id,revision);byte[] bytes=Files.readAllBytes(root.resolve(row.get("ARTIFACT_KEY").toString()));assertEquals(row.get("ARTIFACT_SHA"),PenaltyArtifacts.sha(bytes));assertArrayEquals(row.get("PAYLOAD").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),bytes);rows.add(row);
  }
  for(var id:List.of(rule,suggestion))for(var row:db.queryForList("SELECT payload,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_id=?",id)){byte[] bytes=Files.readAllBytes(root.resolve(row.get("ARTIFACT_KEY").toString()));assertEquals(row.get("ARTIFACT_SHA"),PenaltyArtifacts.sha(bytes));assertArrayEquals(row.get("PAYLOAD").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),bytes);}
  var source=json.readTree(Files.readAllBytes(browser.resolve("source-input-update/result.json")));assertTrue(source.path("oldOriginalRetained").asBoolean());assertNotEquals(source.path("oldSha"),source.path("newSha"));
  var latest=store.latestSuggestion(suggestion).orElseThrow();assertTrue(latest.gaps().contains("LEGAL_REFERENCE_REVIEW_REQUIRED"));assertTrue(latest.options().isEmpty());assertNull(latest.reviewedBy());assertFalse(Files.exists(browser.resolve("failure.json")));
  json.writeValue(Path.of(req("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_RULE_SOURCE_BROWSER_DM8_FILE_SHA_EVENT_RECEIPT_NOT_FINAL_IMP_ACCEPTANCE","newRecords",rows,"mutations",result.path("mutations"),"sourceInputChange",source,"latestSuggestion",latest,"businessAcceptance","PENDING"));
 }
 private static String req(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
