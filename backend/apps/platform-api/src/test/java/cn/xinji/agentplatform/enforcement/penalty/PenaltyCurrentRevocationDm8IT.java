package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Reconciles actual browser/HTTP failures against both real DB inventories and original SHA. */
class PenaltyCurrentRevocationDm8IT {
 @Test void sourceAndOldPhysicalRestoreRejectWithoutDomainSideEffects() throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();Path root=Path.of(req("ENF_PENALTY_REVOCATION_RECONCILIATION_ROOT"));
  var browser=json.readTree(Files.readAllBytes(root.resolve("browser/result.json")));
  assertEquals(403,browser.path("sourceTrial").asInt());assertEquals(503,browser.path("restoredTrial").asInt());assertTrue(browser.path("sourceSessionCleared").asBoolean());
  assertEquals(2,browser.path("replays").size());assertEquals(403,browser.path("replays").get(0).path("status").asInt());assertEquals(503,browser.path("replays").get(1).path("status").asInt());
  for(String tag:List.of("source","target")){
   var before=json.readTree(Files.readAllBytes(root.resolve(tag+"-before.json")));var after=json.readTree(Files.readAllBytes(root.resolve(tag+"-after.json")));
   assertFalse(before.path("records").isEmpty());for(String field:List.of("records","receipts","events"))assertEquals(before.path(field),after.path(field),tag+" domain changed");
   for(var record:after.path("records")){var file=Path.of(record.path("originalPath").asText());assertEquals(record.path("sha256").asText(),PenaltyArtifacts.sha(Files.readAllBytes(file)));}
  }
  assertFalse(Files.exists(root.resolve("browser/failure.json")));
  json.writeValue(root.resolve("product-db-originals-sha-events-receipts.json").toFile(),Map.of("scope","REAL_BROWSER_HTTP_REVOCATION_BOTH_DM8_ORIGINALS_RECONCILIATION_NOT_BUSINESS_ACCEPTANCE","sourceTrial",403,"oldPhysicalRestoreTrial",503,"domainMutations",0,"businessAcceptance","PENDING"));
 }
 static String req(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
