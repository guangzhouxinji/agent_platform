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

/** Real DM8/file reconciliation of browser-generated lifecycle, not simulated service acceptance. */
class PenaltyPositiveBrowserReconciliationIT {
    @Test void browserPublishDisablePreservesAllOriginalsEventsAndReceipts() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var execution=json.readTree(Files.readAllBytes(Path.of(req("ENF_PENALTY_BROWSER_EVIDENCE")).resolve("result.json")));
        assertEquals("REAL_PRODUCT_ENF15_LIFECYCLE_CONTROLLED_INPUT_NOT_BUSINESS_ACCEPTANCE",execution.path("scope").asText());
        assertEquals(List.of("DRAFT","ACTIVE","DISABLED"),json.convertValue(execution.path("states"),List.class));
        var data=new DriverManagerDataSource(req("ENF_ACCEPTANCE_BUSINESS_URL"),req("ENF_ACCEPTANCE_BUSINESS_USER"),req("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertTrue(data.getUrl().startsWith("jdbc:dm://"));var db=new JdbcTemplate(data);
        Path root=Path.of(req("ENF_PENALTY_PRODUCT_FILE_ROOT"));
        var store=new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
        String id=execution.path("ruleId").asText();assertTrue(id.startsWith("ENF15-POSITIVE-"));
        var history=store.ruleHistory(id);assertEquals(3,history.size());
        assertEquals(List.of(PenaltyContract.RuleState.DRAFT,PenaltyContract.RuleState.ACTIVE,PenaltyContract.RuleState.DISABLED),history.stream().map(PenaltyContract.Rule::state).toList());
        var receipts=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,revision,operation FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);
        assertEquals(receipts,events);assertEquals(3,receipts.size());
        assertEquals(List.of("SAVE_PENALTY_RULE","PUBLISH","DISABLE"),receipts.stream().map(r->r.get("OPERATION")).toList());
        var originals=db.queryForList("SELECT revision,artifact_key,artifact_sha,payload FROM enf11_penalty_revision WHERE resource_id=? ORDER BY revision",id);
        for(var row:originals) {
            byte[] bytes=Files.readAllBytes(root.resolve(row.get("ARTIFACT_KEY").toString()));
            assertEquals(row.get("ARTIFACT_SHA").toString(),PenaltyArtifacts.sha(bytes));
            assertEquals(json.readTree(row.get("PAYLOAD").toString()),json.readTree(bytes));
        }
        try(var files=Files.list(root)){assertEquals(3,files.count(),"Rejected duplicate/stale/disable trials must not add originals");}
        assertEquals(409,execution.path("disabledRepublish").path("status").asInt());
        assertEquals(409,execution.path("disabledDisable").path("status").asInt());
        assertTrue(execution.path("apiChecks").isArray());assertEquals(3,execution.path("apiChecks").size());
        json.writeValue(Path.of(req("ENF_PENALTY_RECONCILIATION_EVIDENCE")).toFile(),Map.of("scope","REAL_PRODUCT_BROWSER_DM8_FILE_SHA_EVENT_RECEIPT_ENF15_NOT_FULL_IMP_ACCEPTANCE","ruleId",id,"history",history,"receipts",receipts,"events",events,"originals",originals,"forbiddenSideEffects",Map.of("extraRevisions",0,"extraFiles",0,"extraReceipts",0,"extraEvents",0),"businessAcceptance","PENDING"));
    }
    private static String req(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
