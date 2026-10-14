package cn.xinji.agentplatform.adjudication.casework;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.security.product.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Real signed HTTPS/current grants/source authority, with test-only model port, never a paid call. */
class MaterialOrganizationHttpTest {
    @TempDir Path keys;CaseApiFixture f;JdbcMaterialRunStore store;MaterialModelPolicy policy;String grantId;
    AtomicInteger calls=new AtomicInteger();Runnable during=()->{};
    @BeforeEach void setup()throws Exception {
        f=new CaseApiFixture(false,Path.of("docs/evidence/adjudication-llm-materials-20261003/api-component-input.json"));
        try(var c=f.databases.primary.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V090__adjudication_material_model_run.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V091__adjudication_model_approval_binding.sql"));}
        Clock clock=Clock.systemUTC();var hashes=new HashSet<String>();f.pack.path("materials").forEach(m->hashes.add(m.path("source").path("sha256").asText()));
        policy=new MaterialModelPolicy("mechanical-http-component",sha256("approval"),sha256("supplier"),f.caseId,clock.instant().plus(Duration.ofDays(1)),hashes,"deepseek-flash");
        var tx=new TransactionTemplate(f.context.getBean(org.springframework.transaction.PlatformTransactionManager.class));store=new JdbcMaterialRunStore(f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),tx,new MaterialRunVault(keys,clock),clock);store.initialize(policy);
        var fake=new MaterialOrganizationModel(){public String request(List<Input> input,int max){return "{\"componentPortOnly\":true}";}public Reply invoke(String r){calls.incrementAndGet();during.run();return new Reply("deepseek-flash","{\"candidates\":[{\"kind\":\"PARTY_ASSERTION\",\"sourceTag\":\"s1\",\"side\":\"APPLICANT\",\"statement\":\"申请方提出结构主张\",\"exactQuote\":\"申请方主张，对方提供的连接组件在外壳内使用弹簧固定活动件，并要求就该连接结构开展核查。\"}]}",new Usage(50,20));}};
        var service=new MaterialOrganizationService(f.context.getBean(CaseWorkRepository.class),store,policy,fake,f.mapper,tx,f.context.getBean(CaseSourceAuthority.class),f.context.getBean(ProductGrantWatermark.class),clock);
        var ctrl=new MaterialOrganizationController(service,f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),f.issuer);
        var mapping=f.context.getBean(RequestMappingHandlerMapping.class);var opts=mapping.getBuilderConfiguration();
        // Registers the actual production controller methods, not a testing-only business interface.
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/runs").methods(RequestMethod.POST).options(opts).build(),ctrl,MaterialOrganizationController.class.getMethod("generate",String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class,com.fasterxml.jackson.databind.JsonNode.class));
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/runs/{runId}").methods(RequestMethod.GET).options(opts).build(),ctrl,MaterialOrganizationController.class.getMethod("read",String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class));
        f.create();f.command("/materials",1,f.pack.path("materials").get(0),"material");f.command("/materials",2,f.pack.path("materials").get(1),"material");
    }
    @AfterEach void close()throws Exception{if(f!=null)f.close();}
    String path(){return "/v2/adjudication/case-works/"+f.caseId+"/material-organization/runs";}
    com.fasterxml.jackson.databind.node.ObjectNode body(long v){var b=f.mapper.createObjectNode().put("expectedVersion",v);b.putArray("materialIds").add("material-1").add("material-2");return b;}
    void grant()throws Exception {
        var scope=new ProductGrants.Scope(f.issuer,"case-author",f.pack.path("caseInput").path("organization").asText(),"ADJUDICATION",f.caseId,f.pack.path("caseInput").path("dataScope").asText(),"ADJ_CASE",f.caseId,1,"MODEL_EXECUTE","CASE_AUXILIARY");
        var receipt=f.send(f.maintenance,null,"POST","/v2/adjudication/grants",f.mapper.valueToTree(new CaseGrantController.Issue(scope,Instant.now().plusSeconds(3600),0)),UUID.randomUUID().toString(),200);grantId=receipt.path("grantId").asText();
    }
    com.fasterxml.jackson.databind.JsonNode generate(String key,long v,int status)throws Exception{return f.send(f.member,f.tokens.get("case-author"),"POST",path(),body(v),key,status);}
    @Test void existingWriteAndAnalyzeDoNotGrantModelExecution()throws Exception{generate("no-grant",3,403);assertEquals(0,calls.get());assertEquals(0,store.totals(policy).calls());}
    @Test void validRunIsAuditedDoesNotChangeFactsAndExactReplayNoSecondCall()throws Exception{grant();var r=generate("one",3,200);assertEquals(4,r.path("workVersion").asLong());assertEquals(3,r.path("inputVersion").asLong());assertEquals(1,r.path("candidates").size());generate("one",3,200);assertEquals(1,calls.get());assertEquals(1,store.totals(policy).calls());assertTrue(f.current().path("findings").isEmpty());f.send("case-author","GET",path()+"/"+r.path("runId").asText(),null,200);}
    @Test void staleVersionAndClientModelOverrideDoNotCall()throws Exception{grant();generate("old",2,409);var b=body(3).put("model","unapproved");f.send(f.member,f.tokens.get("case-author"),"POST",path(),b,"override",400);assertEquals(0,calls.get());}
    @Test void revokedSourceBeforeCallAndHistoryReadReject()throws Exception{grant();var r=generate("good",3,200);revokeSource();f.send("case-author","GET",path()+"/"+r.path("runId").asText(),null,403);generate("next",4,403);assertEquals(1,calls.get());}
    @Test void sourceWithdrawnDuringCallDoesNotCommitOrOverwrite()throws Exception{grant();during=()->{try{revokeSource();}catch(Exception e){throw new IllegalStateException(e);}};generate("withdraw",3,503);assertEquals(3,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals("UNCERTAIN",f.databases.db.queryForObject("SELECT state FROM adj3_material_run",String.class));}
    @Test void modelGrantWithdrawnDuringCallDoesNotCommit()throws Exception{grant();during=()->{try{f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grantId+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);}catch(Exception e){throw new IllegalStateException(e);}};generate("revoke",3,503);assertEquals(3,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals(1,store.totals(policy).calls());}
    @Test void concurrentHeadChangeRejectsModelCommit()throws Exception{grant();during=()->{try{f.command("/reports",3,null,null);}catch(Exception e){throw new IllegalStateException(e);}};
        generate("conflict",3,409);assertEquals(4,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals("UNCERTAIN",f.databases.db.queryForObject("SELECT state FROM adj3_material_run",String.class));}
    @Test void dbCommitFailureRollsBackCaseAuditAndRetainsReservedCost()throws Exception{grant();f.databases.db.execute("ALTER TABLE adj3_material_run ADD CONSTRAINT reject_model_commit CHECK(state<>'COMMITTED')");generate("db-failure",3,503);assertEquals(3,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals(3,f.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_audit",Integer.class));assertEquals(1,store.totals(policy).calls());}
    @Test void sourcePolicyWrongCaseOrUnapprovedTextNeverCalls()throws Exception{grant();var different=body(3);different.putArray("materialIds").add("not-admitted");f.send(f.member,f.tokens.get("case-author"),"POST",path(),different,"other-source",400);assertEquals(0,calls.get());}
    void revokeSource()throws Exception{var s=f.codec.request(f.pack.path("materials").get(0),Material.class).source();var scope=new CaseWorkRepository.Scope(f.caseId,f.pack.path("caseInput").path("organization").asText(),f.pack.path("caseInput").path("dataScope").asText());String k=f.context.getBean(CaseSourceAuthority.class).key(scope,s);f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses/"+k+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),null,200);}
}
