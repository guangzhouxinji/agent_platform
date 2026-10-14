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

/** Actual HTTP outcomes plus before/after DM8/file reconciliation; no skips, fake adapters, or H2. */
class PenaltyTransitionNegativesDm8IT {
 @Test void transitionReplayAndCurrentHeadRejectionHaveNoForbiddenSideEffects() throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();
  var baseline=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_BASELINE_RECONCILIATION"))));
  var result=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_BROWSER_EVIDENCE")).resolve("result.json")));
  assertEquals("REAL_PRODUCT_ENF15_TRANSITION_NEGATIVES_NOT_FULL_IMP_ACCEPTANCE",result.path("scope").asText());
  String id=result.path("ruleId").asText();assertEquals(baseline.path("ruleId").asText(),id);
  var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));assertTrue(data.getUrl().startsWith("jdbc:dm://"));
  var db=new JdbcTemplate(data);Path root=Path.of(req("ENF_PENALTY_PRODUCT_FILE_ROOT"));
  var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
  var history=store.ruleHistory(id);assertEquals(3,history.size());assertEquals(PenaltyContract.RuleState.DISABLED,history.getLast().state());
  assertEquals(json.writeValueAsString(baseline.path("history")),json.writeValueAsString(history));
  var receipts=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
  var events=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);
  assertEquals(receipts,events);assertEquals(3,receipts.size());
  assertEquals(json.writeValueAsString(baseline.path("receipts")),json.writeValueAsString(receipts));
  for(var row:baseline.path("originals")){byte[] bytes=Files.readAllBytes(root.resolve(row.path("ARTIFACT_KEY").asText()));assertEquals(row.path("ARTIFACT_SHA").asText(),PenaltyArtifacts.sha(bytes));assertEquals(json.readTree(row.path("PAYLOAD").asText()),json.readTree(bytes));}
  try(var files=Files.list(root)){assertEquals(3,files.count());}
  var statuses=new ArrayList<Integer>();result.path("records").forEach(r->{assertEquals(r.path("expected").asInt(),r.path("status").asInt());statuses.add(r.path("status").asInt());});
  assertEquals(List.of(200,409,503,409,409,409),statuses);
  json.writeValue(Path.of(req("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_HTTP_DM8_FILE_SHA_RECEIPT_EVENT_NEGATIVES_NOT_FULL_IMP_ACCEPTANCE","ruleId",id,"history",history,"receipts",receipts,"events",events,"httpStatuses",statuses,"forbiddenSideEffects",Map.of("addedRevisions",0,"addedOriginals",0,"addedEvents",0,"addedReceipts",0)));
 }
 private static String req(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException(name+" required; no skip");return v;}
}
