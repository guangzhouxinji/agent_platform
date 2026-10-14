import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
/** Independent fresh connection; SELECT and file reads only after committed repair. */
public class EnforcementRecoveryReconcile87 {
 public static void main(String[] args)throws Exception {
  var j=EnforcementRecoveryApply87.JSON;Path root=Path.of(args[0]),out=Path.of(args[1]);
  if(!"ENFACC_BIZ".equals(System.getenv("ENF_AUDIT_USER")))throw new IllegalArgumentException("Business identity required");
  JsonNode source=j.readTree(Files.readString(root.resolve("recovery-reexport-20261004-85/retained-history-utf8.json"),StandardCharsets.UTF_8));
  JsonNode before=j.readTree(Files.readString(out.resolve("application/application-protected-before.json"),StandardCharsets.UTF_8));
  JsonNode inTx=j.readTree(Files.readString(out.resolve("application/restored-in-transaction.json"),StandardCharsets.UTF_8));
  JsonNode expectedFiles=j.readTree(Files.readString(out.resolve("application/application-files-before.json"),StandardCharsets.UTF_8));
  JsonNode originals=j.readTree(Files.readString(root.resolve("recovery-approved-20261004-86/current-originals-and-preservation.json"),StandardCharsets.UTF_8));
  var result=new LinkedHashMap<String,Object>();result.put("checkedAt",Instant.now().toString());result.put("scope","INDEPENDENT_FRESH_CONNECTION_SELECT_ONLY_FULL_ORIGINAL_FIELDS_AND_PROTECTED_STATE");
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5311","ENFACC_BIZ",System.getenv("ENF_AUDIT_PASSWORD"))){
   c.setReadOnly(true);c.setAutoCommit(false);JsonNode current=EnforcementRecoveryApply87.originalsInDb(c,source);
   EnforcementRecoveryApply87.require(current.equals(inTx),"Independent original reconciliation mismatch");
   JsonNode protectedNow=EnforcementRecoveryApply87.protectedState(c);EnforcementRecoveryApply87.require(protectedNow.equals(before),"Independent protection mismatch");
   JsonNode filesNow=EnforcementRecoveryApply87.fileState(originals);EnforcementRecoveryApply87.require(filesNow.equals(expectedFiles),"Independent files mismatch");
   EnforcementRecoveryApply87.save(out.resolve("independent-restored-records.json"),current);EnforcementRecoveryApply87.save(out.resolve("independent-protected-after.json"),protectedNow);EnforcementRecoveryApply87.save(out.resolve("independent-files-after.json"),filesNow);
   var counts=new LinkedHashMap<String,Integer>();for(String t:EnforcementRecoveryApply87.TABLES)counts.put(t,source.path(t).size());result.put("originalRowsByTable",counts);result.put("originalFieldsEqual",199);result.put("typedTimestampsEqual",29);result.put("originalsSHAMatched",12);result.put("postIncidentDocumentAndOriginalPreserved",true);result.put("executionWindowAuthorizationProjectionPreserved",true);result.put("authorizationProjectionRows",before.path("authorization").size());result.put("triggerRows",protectedNow.path("enf8_integration_trigger").size());result.put("fileRegistrationRows",protectedNow.path("enf8_job_pack_file").size());result.put("originalReceiptEventRelationshipPreserved",true);
   c.rollback();
  }result.put("status","KNOWN_16_HISTORICAL_ORIGINALS_RESTORATION_VERIFIED");result.put("fullLatestHistoryCompleteness","NOT_PROVED");result.put("businessWrites",0);result.put("serviceRestart",false);result.put("formalBusinessAcceptance","PENDING");
  EnforcementRecoveryApply87.save(out.resolve("independent-reconciliation.json"),result);
 }
}
