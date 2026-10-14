package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Actual TLS/JWT/grants/source-authority + SQL + domain report chain. Engineering input, not legal acceptance. */
class AdjudicationAgentTaskHttpTest {
    CaseApiFixture f;
    @BeforeEach void start() throws Exception {
        f=fixture();
        try(var connection=f.databases.primary.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("db/dm8/V096__agent_runtime_checkpoint.sql")));
        }
        byte[] key=new byte[32];Arrays.fill(key,(byte)71);
        f.configurationOverrides.put("agent-platform.runtime.enabled",true);
        f.configurationOverrides.put("agent-platform.runtime.checkpoint-key",Base64.getEncoder().encodeToString(key));
        f.restart();
    }
    CaseApiFixture fixture() throws Exception {return new CaseApiFixture(false);}
    @AfterEach void close() throws Exception {if(f!=null)f.close();}
    String base(){return "/v2/adjudication/case-works/"+f.caseId+"/agent-runs";}
    long prepare() throws Exception {
        long version=f.create().path("workVersion").asLong();
        for(var material:f.pack.path("materials"))version=f.command("/materials",version,material,"material").path("workVersion").asLong();
        return version;
    }
    JsonNode create(long version,String key) throws Exception {
        var body=f.mapper.createObjectNode().put("expectedVersion",version);body.putArray("modelMaterialIds");
        return f.send(f.member,f.tokens.get("case-author"),"POST",base(),body,key,200);
    }
    JsonNode advance(String run) throws Exception {return f.send("case-author","POST",base()+"/"+run+"/advance",null,200);}
    @Test void singleAuthorizedExecuteUsesActualTlsJwtDomainReportAndSameRunReplay() throws Exception {
        var created=create(prepare(),"one-request");String run=created.path("checkpoint").path("runId").asText();
        var result=f.send("case-author","POST",base()+"/"+run+"/execute",null,200);
        assertEquals("STOP",result.path("checkpoint").path("phase").asText());
        assertEquals("NEED_HUMAN",result.path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(created.path("checkpoint").path("runId"),result.path("checkpoint").path("runId"));
        assertEquals(result,f.send("case-author","POST",base()+"/"+run+"/execute",null,200));
        assertEquals(result,f.send("case-author","GET",base()+"/"+run,null,200));
        var state=f.send("case-author","GET","/v2/adjudication/case-works/"+f.caseId,null,200);
        assertEquals(1,state.path("reports").size());
    }
    @Test void realTaskLoopRestartsAndStopsForHumanWithExactlyOneRetainedReport() throws Exception {
        long version=prepare();var created=create(version,"agent-start");
        String run=created.path("checkpoint").path("runId").asText();assertFalse(run.isBlank());
        assertEquals(created,create(version,"agent-start"));
        var current=advance(run);assertEquals("PLAN",current.path("checkpoint").path("phase").asText());
        String issuer=f.issuer,token=f.tokens.get("case-author");
        long grants=f.databases.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE issuer=?",Long.class,issuer);
        f.restart();
        assertEquals(issuer,f.issuer);assertEquals(token,f.tokens.get("case-author"));
        assertNotEquals(issuer,f.endpoint);assertTrue(f.context.getWebServer().getPort()>0);
        assertEquals(grants,f.databases.db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE issuer=?",Long.class,issuer));
        assertEquals(current,f.send("case-author","GET",base()+"/"+run,null,200));
        for(int i=0;i<20 && !current.path("checkpoint").path("phase").asText().equals("STOP");i++)current=advance(run);
        var checkpoint=current.path("checkpoint");
        assertEquals("STOP",checkpoint.path("phase").asText());assertEquals("NEED_HUMAN",checkpoint.path("outcome").path("reason").asText());
        assertEquals(5,checkpoint.path("invocations").asInt());assertEquals(5,checkpoint.path("cursor").asInt());
        assertEquals(6,checkpoint.path("observations").size());
        assertTrue(checkpoint.path("observations").toString().contains("patent-status-gap"));
        var state=f.current();assertEquals(version+1,state.path("version").asLong());
        assertEquals(1,state.path("reports").get(0).path("report").path("revision").asLong());
        assertEquals(current,advance(run));
        assertEquals(version+1,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Long.class));
        assertEquals(checkpoint.path("version").asLong()+1,f.databases.db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Long.class));
        assertFalse(f.databases.db.queryForObject("SELECT ciphertext FROM agent_run_revision WHERE revision=0",String.class).contains("initialWorkVersion"));
        assertTrue(state.path("reports").get(0).path("report").path("diagnostics").size()>0);
    }
    @Test void reportSideEffectSurvivesCheckpointCommitFailureAndReplaysWithoutSecondReport() throws Exception {
        long version=prepare();String run=create(version,"agent-report-replay").path("checkpoint").path("runId").asText();
        JsonNode current;
        current=advance(run);
        for(int i=0;i<20 && !"report-draft".equals(current.path("checkpoint").path("pending").path("step").path("tool").asText());i++)current=advance(run);
        assertEquals("report-draft",current.path("checkpoint").path("pending").path("step").path("tool").asText());
        long pendingVersion=current.path("checkpoint").path("version").asLong();
        f.databases.db.execute("ALTER TABLE agent_run_revision ADD CONSTRAINT owned_checkpoint_failure CHECK (revision<>"+(pendingVersion+1)+")");
        try {f.send("case-author","POST",base()+"/"+run+"/advance",null,503);}
        finally {f.databases.db.execute("ALTER TABLE agent_run_revision DROP CONSTRAINT owned_checkpoint_failure");}
        assertEquals(pendingVersion,f.databases.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));
        assertEquals(1,f.current().path("reports").size());
        f.restart();current=advance(run);
        assertEquals("EVALUATE",current.path("checkpoint").path("phase").asText());
        assertEquals("NEED_HUMAN",advance(run).path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(1,f.current().path("reports").size());
        assertEquals(version+1,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Long.class));
    }
    @Test void liveWriteRevocationBlocksRestartedExecutionWithoutChangingHistory() throws Exception {
        String run=create(prepare(),"agent-revoke").path("checkpoint").path("runId").asText();
        advance(run);
        String grant=f.databases.db.queryForObject("SELECT grant_id FROM id2_grant WHERE subject_id='case-author' AND action_code='WRITE'",String.class);
        var body=f.mapper.createObjectNode().put("expectedVersion",1);
        f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grant+"/revoke",body,"agent-revoke-grant",200);
        f.restart();
        f.send("case-author","GET",base()+"/"+run,null,200);
        f.send("case-author","POST",base()+"/"+run+"/advance",null,403);
        assertEquals(2,f.databases.db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Long.class));
        assertEquals(0,f.current().path("reports").size());
    }
    @Test void anonymousAndUngrantedCannotExecuteAndReadOnlyReviewerCannotAdvance() throws Exception {
        var created=create(prepare(),"agent-permissions");String run=created.path("checkpoint").path("runId").asText();
        f.send(f.member,null,"POST",base()+"/"+run+"/advance",null,"anonymous",401);
        f.send("ungranted-user","GET",base()+"/"+run,null,403);
        f.send("independent-reviewer","GET",base()+"/"+run,null,200);
        f.send("independent-reviewer","POST",base()+"/"+run+"/advance",null,403);
        assertEquals(1,f.databases.db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Long.class));
    }
    @Test void changedMaterialsInvalidatePinnedGoalAndLeaveCheckpointUntouched() throws Exception {
        long version=f.create().path("workVersion").asLong();var created=create(version,"agent-stale");
        String run=created.path("checkpoint").path("runId").asText();
        f.command("/materials",version,f.pack.path("materials").get(0),"material");
        f.send("case-author","POST",base()+"/"+run+"/advance",null,409);
        assertEquals(1,f.databases.db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Long.class));
    }
}
