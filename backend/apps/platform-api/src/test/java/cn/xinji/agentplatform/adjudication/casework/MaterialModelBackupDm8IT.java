package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.materials.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Native imported ADJ-only backup. No identity, business HTTP or provider authorization claim. */
@EnabledIfEnvironmentVariable(named="ADJ_MODEL_BACKUP_RECOVERY",matches="true")
class MaterialModelBackupDm8IT {
    @Test void nativeImportedBudgetAndCandidatesRecoverWithoutExtendingRetention()throws Exception {
        String sourceUrl=required("ADJ_BACKUP_SOURCE_URL"),restoreUrl=required("ADJ_BACKUP_RESTORE_URL");
        assertEquals("jdbc:dm://127.0.0.1:5301",sourceUrl);
        assertEquals("jdbc:dm://127.0.0.1:5369",restoreUrl);
        assertNotEquals(sourceUrl,restoreUrl);
        String user=required("ADJ_BACKUP_USER");assertEquals("ADJLLM20261003",user);
        var sourceDs=new DriverManagerDataSource(sourceUrl,user,required("ADJ_BACKUP_PASSWORD"));
        var restoreDs=new DriverManagerDataSource(restoreUrl,user,required("ADJ_BACKUP_PASSWORD"));
        var source=new JdbcTemplate(sourceDs);var db=new JdbcTemplate(restoreDs);
        var json=new ObjectMapper().findAndRegisterModules();var root=Path.of("docs/evidence/adjudication-llm-materials-20261003");
        var prior=json.readTree(Files.readString(root.resolve("real-model-dm8-01/real-model-dm8-summary.json")));
        String id=prior.path("runId").asText();var expiry=Instant.parse(prior.path("expiresAt").asText());
        var hashes=new HashSet<String>();json.readTree(Files.readString(root.resolve("user-approved-material-package-v1/manifest.json"))).path("files").forEach(f->hashes.add(f.path("sha256").asText()));
        var base=json.readTree(Files.readString(root.resolve("user-approval-v1.json")));
        var policy=new MaterialModelPolicy(base.path("reference").asText(),sha256(Files.readString(root.resolve("user-approval-v1.json"))),sha256(Files.readString(root.resolve("supplier-approval-v1.json"))),"adjudication-materials-case-01",expiry,hashes,"deepseek-flash");
        String sourceCipher=source.queryForObject("SELECT payload FROM adj3_material_run WHERE run_id=?",String.class,id);
        String budgetSql="SELECT policy_key,approval_key,case_id,config_sha,calls,input_tokens,output_tokens,cost_micros,frozen,expires_at FROM adj3_model_budget";
        var sourceBudget=source.queryForList(budgetSql);assertEquals(sourceBudget,db.queryForList(budgetSql));
        assertEquals(1,sourceBudget.size());assertEquals(sourceCipher,db.queryForObject("SELECT payload FROM adj3_material_run WHERE run_id=?",String.class,id));
        assertEquals(prior.path("payloadCipherSha256").asText(),sha256(sourceCipher));
        Clock clock=Clock.systemUTC();Path originalKeys=Path.of(required("ADJ_BACKUP_KEY_DIRECTORY"));
        var vault=new MaterialRunVault(originalKeys,clock);var tx=new TransactionTemplate(new DataSourceTransactionManager(restoreDs));
        var store=new JdbcMaterialRunStore(db,tx,vault,clock);store.purgeExpired();store.initialize(policy);
        assertEquals(1,store.totals(policy).calls());assertEquals(687,store.totals(policy).inputTokens());assertEquals(1149,store.totals(policy).outputTokens());assertEquals(10566,store.totals(policy).costMicros());
        String payload=store.load(policy,id);var node=json.readTree(payload);
        var inputs=json.readerForListOf(MaterialOrganizationModel.Input.class).<List<MaterialOrganizationModel.Input>>readValue(node.path("inputs"));
        var candidates=new MaterialCandidateValidator().validate(inputs,node.path("reply").path("rawContent").asText());
        assertEquals(16,candidates.size());assertEquals(node.path("candidates"),json.valueToTree(candidates));
        assertEquals(payload,new JdbcMaterialRunStore(db,tx,new MaterialRunVault(originalKeys,clock),clock).load(policy,id));
        var other=new MaterialModelPolicy(policy.approvalRef(),policy.approvalSha256(),policy.supplierApprovalSha256(),"different-case",policy.validUntil(),policy.permittedTextHashes(),policy.modelVersion());
        assertThrows(RuntimeException.class,()->store.initialize(other));assertEquals(1,store.totals(policy).calls());
        Path emptyKeys=Files.createTempDirectory("adj-native-recovery-no-keys-");
        try {
            var noKeys=new JdbcMaterialRunStore(db,tx,new MaterialRunVault(emptyKeys,clock),clock);
            assertThrows(IllegalStateException.class,()->noKeys.load(policy,id));
            // Only the newly imported independent recovery database is fault-injected, never a source/authority database.
            db.update("UPDATE adj3_material_run SET payload=? WHERE run_id=?","damaged-recovery-cipher",id);
            assertThrows(IllegalStateException.class,()->store.load(policy,id));
            db.update("UPDATE adj3_material_run SET payload=? WHERE run_id=?",sourceCipher,id);
            assertEquals(payload,store.load(policy,id));
            Clock future=Clock.fixed(expiry.plusSeconds(1),ZoneOffset.UTC);
            var expired=new JdbcMaterialRunStore(db,tx,new MaterialRunVault(emptyKeys,future),future);
            assertThrows(SecurityException.class,()->expired.load(policy,id));
            expired.purgeExpired();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM adj3_material_run",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM adj3_model_budget",Integer.class));
            assertThrows(RuntimeException.class,()->expired.load(policy,id));
        }finally{Files.deleteIfExists(emptyKeys);}
        assertEquals(sourceBudget,source.queryForList(budgetSql));
        assertEquals(sourceCipher,source.queryForObject("SELECT payload FROM adj3_material_run WHERE run_id=?",String.class,id));
        var result=new LinkedHashMap<String,Object>();result.put("scope","NATIVE_DM8_IMPORTED_MODEL_BACKUP_NO_BUSINESS_IDENTITY_OR_HTTP_OR_PROVIDER_CALL");result.put("passed",true);result.put("runId",id);result.put("caseId",policy.caseId());result.put("candidates",candidates.size());result.put("cipherSha256",sha256(sourceCipher));result.put("expiresAt",expiry.toString());result.put("budgetBeforeNegativeCleanup",Map.of("calls",1,"inputTokens",687,"outputTokens",1149,"costMicros",10566));result.put("approvalUniqueBindingPreserved",true);result.put("candidateAndOriginalLocationRechecked",true);result.put("missingKeyRejected",true);result.put("damagedCipherRejected",true);result.put("expiredBeforePurgeRejected",true);result.put("expiredRestoredRowsPurged",true);result.put("sourceBudgetAndCipherUnchanged",true);result.put("keyCopiedIntoBackup",false);result.put("providerCalls",0);
        Files.writeString(Path.of(required("ADJ_BACKUP_EVIDENCE")).resolve("native-dm8-recovery-summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalStateException("Missing private recovery configuration: "+key);return v;}
}
