package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles actual browser-produced data. Missing browser execution is a failure, not a skip or a fabricated success. */
class PenaltyBrowserReconciliationIT {
    @Test void actualBrowserDraftTrialsRejectedPublishAndHttpReplayHaveOneOriginalReceiptEvent()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var execution=json.readTree(Files.readAllBytes(Path.of(required("ENF_PENALTY_BROWSER_EVIDENCE")).resolve("result.json")));
        assertEquals("REAL_HTTPS_OAUTH_PKCE_BROWSER_PARTIAL_ENF15_NOT_FINAL_ACCEPTANCE",execution.path("scope").asText());
        String id=execution.path("ruleId").asText();assertFalse(id.isBlank());
        var source=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertTrue(source.getUrl().startsWith("jdbc:dm://"));var db=new JdbcTemplate(source);
        Path root=Path.of(required("ENF_PENALTY_PRODUCT_FILE_ROOT"));
        var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(source)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
        var history=store.ruleHistory(id);assertEquals(1,history.size());assertEquals(PenaltyContract.RuleState.DRAFT,history.getFirst().state());
        var receipts=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);assertEquals(receipts,events);assertEquals(1,receipts.size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_receipt WHERE resource_id=? AND operation='PUBLISH'",Integer.class,id));
        var original=db.queryForMap("SELECT artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_id=? AND revision=1",id);
        Path file=root.resolve(original.get("ARTIFACT_KEY").toString());assertEquals(original.get("ARTIFACT_SHA").toString(),PenaltyArtifacts.sha(Files.readAllBytes(file)));
        try(var files=Files.list(root)){assertEquals(1,files.count(),"Rejected publication/CAS/replay/trials must not add originals");}
        var denied=execution.path("suggestionPermission");
        assertEquals(403,denied.path("status").asInt());assertTrue(denied.path("sessionCleared").asBoolean());
        String suggestionId=denied.path("id").asText();assertTrue(suggestionId.startsWith("ENF16-DENIED-"));
        assertTrue(store.suggestionHistory(suggestionId).isEmpty());
        for(String table:java.util.List.of("enf11_penalty_revision","enf11_penalty_receipt","enf11_penalty_event"))
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE resource_id=?",Integer.class,suggestionId),"Denied create must not write "+table);
        json.writeValue(Path.of(required("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_DM8_BROWSER_RECONCILIATION_PARTIAL_NOT_FINAL_ACCEPTANCE",
            "ruleId",id,"history",history,"original",original,"receipts",receipts,"events",events,"suggestionPermission",denied,"forbiddenSideEffects",Map.of("publishReceipts",0,"additionalVersions",0,"additionalFiles",0,"unauthorizedSuggestionWrites",0)));
    }
    static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required; no skip");return v;}
}
