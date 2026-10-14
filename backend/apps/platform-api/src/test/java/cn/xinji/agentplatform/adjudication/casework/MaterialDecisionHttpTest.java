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
class MaterialDecisionHttpTest {
    @TempDir Path keys;CaseApiFixture f;JdbcMaterialRunStore store;MaterialModelPolicy policy;String grantId;
    AtomicInteger calls=new AtomicInteger();Runnable during=()->{};
    MaterialOrganizationService organization;JdbcMaterialDecisionStore decisions;MaterialDecisionService service;MaterialDecisionController controller;String runId,writeGrant;
    @BeforeEach void setup()throws Exception {
        f=new CaseApiFixture(false,Path.of("docs/evidence/adjudication-llm-materials-20261003/api-component-input.json"));
        try(var c=f.databases.primary.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V090__adjudication_material_model_run.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V091__adjudication_model_approval_binding.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V093__adjudication_material_decisions.sql"));}
        Clock clock=Clock.systemUTC();var hashes=new HashSet<String>();f.pack.path("materials").forEach(m->hashes.add(m.path("source").path("sha256").asText()));
        policy=new MaterialModelPolicy("mechanical-http-component",sha256("approval"),sha256("supplier"),f.caseId,clock.instant().plus(Duration.ofDays(1)),hashes,"deepseek-flash");
        var tx=new TransactionTemplate(f.context.getBean(org.springframework.transaction.PlatformTransactionManager.class));store=new JdbcMaterialRunStore(f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),tx,new MaterialRunVault(keys.resolve("model"),clock),clock);store.initialize(policy);
        var fake=new MaterialOrganizationModel(){public String request(List<Input> input,int max){return "{\"componentPortOnly\":true}";}public Reply invoke(String r){calls.incrementAndGet();during.run();return new Reply("deepseek-flash","{\"candidates\":[{\"kind\":\"PARTY_ASSERTION\",\"sourceTag\":\"s1\",\"side\":\"APPLICANT\",\"statement\":\"申请方提出结构主张\",\"exactQuote\":\"申请方主张，对方提供的连接组件在外壳内使用弹簧固定活动件，并要求就该连接结构开展核查。\"}]}",new Usage(50,20));}};
        organization=new MaterialOrganizationService(f.context.getBean(CaseWorkRepository.class),store,policy,fake,f.mapper,tx,f.context.getBean(CaseSourceAuthority.class),f.context.getBean(ProductGrantWatermark.class),clock);
        var ctrl=new MaterialOrganizationController(organization,f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),f.issuer);
        var mapping=f.context.getBean(RequestMappingHandlerMapping.class);var opts=mapping.getBuilderConfiguration();
        // Registers the actual production controller methods, not a testing-only business interface.
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/runs").methods(RequestMethod.POST).options(opts).build(),ctrl,MaterialOrganizationController.class.getMethod("generate",String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class,com.fasterxml.jackson.databind.JsonNode.class));
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/runs/{runId}").methods(RequestMethod.GET).options(opts).build(),ctrl,MaterialOrganizationController.class.getMethod("read",String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class));
        decisions=new JdbcMaterialDecisionStore(f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),new MaterialDecisionVault(keys.resolve("decisions"),clock),f.codec,clock);
        service=new MaterialDecisionService(f.context.getBean(CaseWorkRepository.class),decisions,store,organization,policy,f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),f.context.getBean(CaseSourceAuthority.class),f.context.getBean(ProductGrantWatermark.class),f.codec,new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions(new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Limits(2000,2000)),clock,f.mapper,tx);
        controller=new MaterialDecisionController(service,f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),f.issuer);
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/runs/{runId}/candidates/{candidateId}/decisions").methods(RequestMethod.POST).options(opts).build(),controller,MaterialDecisionController.class.getMethod("decide",String.class,String.class,String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class,String.class));
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/versions/{versionId}/revisions").methods(RequestMethod.POST).options(opts).build(),controller,MaterialDecisionController.class.getMethod("revise",String.class,String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class,String.class));
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/versions/{versionId}").methods(RequestMethod.GET).options(opts).build(),controller,MaterialDecisionController.class.getMethod("detail",String.class,String.class,org.springframework.security.oauth2.jwt.Jwt.class));
        mapping.registerMapping(RequestMappingInfo.paths("/v2/adjudication/case-works/{caseId}/material-organization/versions").methods(RequestMethod.GET).options(opts).build(),controller,MaterialDecisionController.class.getMethod("list",String.class,long.class,int.class,org.springframework.security.oauth2.jwt.Jwt.class));
        f.create();f.command("/materials",1,f.pack.path("materials").get(0),"material");f.command("/materials",2,f.pack.path("materials").get(1),"material");
        grant();runId=f.send(f.member,f.tokens.get("case-author"),"POST",path(),body(3),"parent",200).path("runId").asText();
    }
    @AfterEach void close()throws Exception{if(f!=null)f.close();}
    String path(){return "/v2/adjudication/case-works/"+f.caseId+"/material-organization/runs";}
    com.fasterxml.jackson.databind.node.ObjectNode body(long v){var b=f.mapper.createObjectNode().put("expectedVersion",v);b.putArray("materialIds").add("material-1").add("material-2");return b;}
    void grant()throws Exception {
        var scope=new ProductGrants.Scope(f.issuer,"case-author",f.pack.path("caseInput").path("organization").asText(),"ADJUDICATION",f.caseId,f.pack.path("caseInput").path("dataScope").asText(),"ADJ_CASE",f.caseId,1,"MODEL_EXECUTE","CASE_AUXILIARY");
        var receipt=f.send(f.maintenance,null,"POST","/v2/adjudication/grants",f.mapper.valueToTree(new CaseGrantController.Issue(scope,Instant.now().plusSeconds(3600),0)),UUID.randomUUID().toString(),200);grantId=receipt.path("grantId").asText();
    }
    com.fasterxml.jackson.databind.JsonNode generate(String key,long v,int status)throws Exception{return f.send(f.member,f.tokens.get("case-author"),"POST",path(),body(v),key,status);}

    String versions(){return path().replace("/runs","/versions");}
    String decisionPath(){return path()+"/"+runId+"/candidates/c1/decisions";}
    com.fasterxml.jackson.databind.node.ObjectNode decision(long work,long org,String action){return f.mapper.createObjectNode().put("expectedWorkVersion",work).put("expectedOrganizationVersion",org).put("action",action);}
    com.fasterxml.jackson.databind.JsonNode decide(String key,com.fasterxml.jackson.databind.JsonNode b,int status)throws Exception{return f.send(f.member,f.tokens.get("case-author"),"POST",decisionPath(),b,key,status);}
    long count(String table){return f.databases.db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    void unchanged(){assertEquals(4,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));assertEquals(4,count("adj2_case_revision"));assertEquals(4,count("adj2_case_audit"));assertEquals(4,count("adj2_case_receipt"));assertEquals(0,count("adj3_material_decision_version"));assertEquals(1,store.totals(policy).calls());}
    @Test void acceptsReplaysAndShowsExactImmutableEncryptedHistory()throws Exception{var r=decide("accept",decision(4,0,"ACCEPT"),200);var id=r.path("version").path("versionId").asText();assertEquals(5,r.path("version").path("workVersion").asLong());assertEquals(3,r.path("version").path("inputVersion").asLong());assertEquals("HUMAN_REVIEWED_MATERIAL_STATEMENT",r.path("version").path("semantic").asText());assertEquals(r,decide("accept",decision(4,0,"ACCEPT"),200));assertEquals(1,count("adj3_material_decision_version"));assertEquals(1,calls.get());assertFalse(f.databases.db.queryForObject("SELECT payload FROM adj3_material_decision_version",String.class).contains("申请方"));var audit=f.databases.db.queryForObject("SELECT payload FROM adj2_case_audit WHERE work_version=5",String.class);assertFalse(audit.contains("申请方"));assertTrue(f.current().path("findings").isEmpty());assertEquals(id,f.send("case-author","GET",versions()+"/"+id,null,200).path("version").path("versionId").asText());}
    @Test void staleSameKeyReplayReturnsExactHistoryButNewKeyAndChangedPayloadStayRejected()throws Exception {
        var request=decision(4,0,"ACCEPT");var accepted=decide("stale-replay",request,200);
        advanceMaterialInput(5);
        var replay=decide("stale-replay",request,200);
        assertEquals(accepted.path("version"),replay.path("version"));assertTrue(replay.path("stale").asBoolean());
        assertEquals(4,replay.path("currentInputVersion").asLong());
        assertEquals("MATERIAL_MODEL_RUN_STALE",decide("new-stale-key",decision(6,1,"ACCEPT"),409).path("code").asText());
        assertEquals("MATERIAL_DECISION_REQUEST_CONFLICT",decide("stale-replay",decision(4,0,"REJECT").put("reason","另一载荷"),409).path("code").asText());
        assertEquals("MATERIAL_MODEL_RUN_STALE",f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+accepted.path("version").path("versionId").asText()+"/revisions",decision(6,1,"EDIT").put("humanText","不能采用旧输入").put("reason","旧候选"),"new-stale-revision",409).path("code").asText());
        assertEquals(replay,f.send("case-author","GET",versions()+"/"+accepted.path("version").path("versionId").asText(),null,200));
        assertEquals(replay,f.send("case-author","GET",versions()+"?limit=1",null,200).path("versions").get(0));
        assertHistoryUnchanged(6,1);
    }
    @Test void staleRevisionReplayReturnsOriginalNotLatestCandidateVersion()throws Exception {
        var first=decide("first",decision(4,0,"ACCEPT"),200);String id=first.path("version").path("versionId").asText();
        var request=decision(5,1,"EDIT").put("humanText","第一版人工更正").put("reason","措辞更正");
        var revised=f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+id+"/revisions",request,"revision-replay",200);
        String revisedId=revised.path("version").path("versionId").asText();
        var latest=f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+revisedId+"/revisions",decision(6,2,"EDIT").put("humanText","后续版本").put("reason","再更正"),"later-revision",200);
        advanceMaterialInput(7);
        var replay=f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+id+"/revisions",request,"revision-replay",200);
        assertEquals(revised.path("version"),replay.path("version"));assertTrue(replay.path("stale").asBoolean());assertFalse(replay.path("currentCandidateDecision").asBoolean());
        var firstReplay=decide("first",decision(4,0,"ACCEPT"),200);assertEquals(first.path("version"),firstReplay.path("version"));assertTrue(firstReplay.path("stale").asBoolean());assertFalse(firstReplay.path("currentCandidateDecision").asBoolean());
        var page=f.send("case-author","GET",versions()+"?afterOrganizationVersion=1&limit=1",null,200);
        assertEquals(replay,page.path("versions").get(0));assertEquals(2,page.path("nextAfterOrganizationVersion").asLong());assertEquals(3,page.path("currentOrganizationVersion").asLong());
        var tail=f.send("case-author","GET",versions()+"?afterOrganizationVersion=2&limit=1",null,200);
        assertEquals(latest.path("version"),tail.path("versions").get(0).path("version"));assertTrue(tail.path("versions").get(0).path("stale").asBoolean());
        assertHistoryUnchanged(8,3);
    }
    @Test void staleSameKeyReplayStillRejectsCurrentIdentityAndRevokedModelGrant()throws Exception {
        var request=decision(4,0,"ACCEPT");decide("revoked-replay",request,200);advanceMaterialInput(5);
        f.send(f.member,f.tokens.get("ungranted-user"),"POST",decisionPath(),request,"revoked-replay",403);
        f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grantId+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);
        decide("revoked-replay",request,403);assertHistoryUnchanged(6,1);
    }
    @Test void staleSameKeyReplayStillRejectsWithdrawnSource()throws Exception {
        var request=decision(4,0,"ACCEPT");decide("withdrawn-replay",request,200);advanceMaterialInput(5);revokeSource();
        decide("withdrawn-replay",request,403);assertHistoryUnchanged(6,1);
    }
    @Test void staleSameKeyReplayStillRejectsExpiredDecision()throws Exception {
        var request=decision(4,0,"ACCEPT");var accepted=decide("expired-replay",request,200);advanceMaterialInput(5);
        Clock expired=Clock.fixed(Instant.parse(accepted.path("version").path("expiresAt").asText()),ZoneOffset.UTC);
        var dao=new JdbcMaterialDecisionStore(f.databases.db,new MaterialDecisionVault(keys.resolve("decisions"),expired),f.codec,expired);
        var app=new MaterialDecisionService(f.context.getBean(CaseWorkRepository.class),dao,store,organization,policy,f.databases.db,f.context.getBean(CaseSourceAuthority.class),f.context.getBean(ProductGrantWatermark.class),f.codec,new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions(new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Limits(2000,2000)),expired,f.mapper,new TransactionTemplate(f.context.getBean(org.springframework.transaction.PlatformTransactionManager.class)));
        var jwt=f.context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(f.tokens.get("case-author"));var access=new CaseWorkAccess(f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),jwt,f.issuer,Clock.systemUTC());
        var error=assertThrows(SecurityException.class,()->app.decide(f.caseId,runId,"c1",new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Request(4,0,cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Action.ACCEPT,null,null),"expired-replay",access));
        assertEquals("MATERIAL_DECISION_EXPIRED",error.getMessage());assertHistoryUnchanged(6,1);
    }
    void advanceMaterialInput(long work)throws Exception {
        f.command("/materials",work,((com.fasterxml.jackson.databind.node.ObjectNode)f.pack.path("materials").get(0).deepCopy()).put("id","material-new").put("supersedesMaterialId","material-1"),"material");
    }
    void assertHistoryUnchanged(long work,long organizationVersion) {
        assertEquals(work,f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class));
        for(var table:List.of("adj2_case_revision","adj2_case_audit","adj2_case_receipt"))assertEquals(work,count(table));
        assertEquals(organizationVersion,count("adj3_material_decision_version"));assertEquals(organizationVersion,decisions.head(f.caseId));assertEquals(1,calls.get());assertEquals(1,store.totals(policy).calls());
    }
    @Test void editThenExactBaseRevisionPreservesOriginalAndDeadline()throws Exception{var r=decide("edit",decision(4,0,"EDIT").put("humanText","人工逐字核查陈述").put("reason","材料仅为一方陈述"),200);var id=r.path("version").path("versionId").asText();var revise=decision(5,1,"EDIT").put("humanText","第二版整理").put("reason","纠正措辞");var n=f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+id+"/revisions",revise,"revision",200);assertEquals(id,n.path("version").path("baseVersionId").asText());assertEquals(r.path("version").path("expiresAt"),n.path("version").path("expiresAt"));assertEquals(r.path("version").path("machineStatement"),n.path("version").path("machineStatement"));assertEquals(n,f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+id+"/revisions",revise,"revision",200));var list=f.send("case-author","GET",versions()+"?limit=1",null,200);assertEquals(1,list.path("versions").size());assertFalse(list.path("versions").get(0).path("currentCandidateDecision").asBoolean());assertEquals(2,f.send("case-author","GET",versions()+"?afterOrganizationVersion=1",null,200).path("versions").get(0).path("version").path("organizationVersion").asLong());f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+id+"/revisions",decision(6,2,"EDIT").put("humanText","旧基线").put("reason","冲突"),"old-base",409);}
    @Test void rejectRequiresReasonAndCannotBeReopened()throws Exception{decide("reason",decision(4,0,"REJECT"),400);unchanged();var r=decide("reject",decision(4,0,"REJECT").put("reason","缺少原文支持"),200);assertEquals("REJECTED_CANDIDATE",r.path("version").path("semantic").asText());assertTrue(r.path("version").path("humanText").isNull());f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+r.path("version").path("versionId").asText()+"/revisions",decision(5,1,"EDIT").put("humanText","不能重开").put("reason","更正"),"reopen",409);}
    @Test void sameKeyDifferentPayloadAndDuplicateCandidateConflict()throws Exception{decide("key",decision(4,0,"ACCEPT"),200);decide("key",decision(4,0,"REJECT").put("reason","另一载荷"),409);decide("different",decision(5,1,"ACCEPT"),409);assertEquals(1,count("adj3_material_decision_version"));}
    @Test void doubleCasRejectsAndDoesNotSave()throws Exception{decide("work",decision(3,0,"ACCEPT"),409);decide("org",decision(4,1,"ACCEPT"),409);unchanged();}
    @Test void unknownServerFieldsAndInvalidVersionsAreRejected()throws Exception{decide("actor",decision(4,0,"ACCEPT").put("actor","forged"),400);decide("expiry",decision(4,0,"ACCEPT").put("expiresAt","forever"),400);decide("null",decision(4,0,"ACCEPT").putNull("expectedWorkVersion"),400);decide("float",decision(4,0,"ACCEPT").put("expectedWorkVersion",4.0),400);unchanged();}
    @Test void duplicateJsonAndTrailingDocumentsAreRejectedByProductParser(){assertThrows(IllegalArgumentException.class,()->controller.request("{\"expectedWorkVersion\":4,\"expectedWorkVersion\":5,\"expectedOrganizationVersion\":0,\"action\":\"ACCEPT\"}"));assertThrows(IllegalArgumentException.class,()->controller.request("{\"expectedWorkVersion\":4,\"expectedOrganizationVersion\":0,\"action\":\"ACCEPT\"} {}"));}
    @Test void ungrantedCurrentSubjectCannotAdoptOrRead()throws Exception{f.send(f.member,f.tokens.get("ungranted-user"),"POST",decisionPath(),decision(4,0,"ACCEPT"),"deny",403);f.send("ungranted-user","GET",versions(),null,403);unchanged();}
    @Test void revokedModelPermissionRejectsWriteAndVersionRead()throws Exception{var r=decide("accept",decision(4,0,"ACCEPT"),200);f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grantId+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);f.send("case-author","GET",versions()+"/"+r.path("version").path("versionId").asText(),null,403);decide("again",decision(5,1,"ACCEPT"),403);assertEquals(1,count("adj3_material_decision_version"));}
    @Test void withdrawnSourceBeforeAdoptionRemainsRejected()throws Exception{revokeSource();decide("withdrawn",decision(4,0,"ACCEPT"),403);unchanged();}
    @Test void staleParentAfterSourceVersionChangeCannotBeAdopted()throws Exception{f.command("/materials",4,((com.fasterxml.jackson.databind.node.ObjectNode)f.pack.path("materials").get(0).deepCopy()).put("id","material-new").put("supersedesMaterialId","material-1"),"material");decide("stale",decision(5,0,"ACCEPT"),409);assertEquals(0,count("adj3_material_decision_version"));}
    @Test void crossRunCandidateAndCaseIdentifiersRejected()throws Exception{f.send(f.member,f.tokens.get("case-author"),"POST",path()+"/"+UUID.randomUUID()+"/candidates/c1/decisions",decision(4,0,"ACCEPT"),"run",404);f.send(f.member,f.tokens.get("case-author"),"POST",path()+"/"+runId+"/candidates/c2/decisions",decision(4,0,"ACCEPT"),"candidate",404);unchanged();}
    @Test void storeFailureRollsBackHeadBodyAuditReceiptAndKey()throws Exception{f.databases.db.execute("ALTER TABLE adj3_material_decision_version ADD CONSTRAINT reject_decision CHECK(action_code<>'ACCEPT')");decide("failure",decision(4,0,"ACCEPT"),503);unchanged();assertEquals(0,count("adj3_material_decision_head"));try(var files=Files.list(keys.resolve("decisions"))){assertEquals(0,files.count());}}
    @Test void failureAfterBodyInsertRollsBackEveryPrimaryObject()throws Exception{f.databases.db.execute("ALTER TABLE adj2_case_receipt ADD CONSTRAINT reject_decision_receipt CHECK(action_code<>'MATERIAL_DECISION')");decide("failure",decision(4,0,"ACCEPT"),503);unchanged();assertEquals(0,count("adj3_material_decision_head"));}
    @Test void restoredStoreAndMissingKeyFailClosed()throws Exception{var r=decide("accept",decision(4,0,"ACCEPT"),200);var id=r.path("version").path("versionId").asText();var reloaded=new JdbcMaterialDecisionStore(f.databases.db,new MaterialDecisionVault(keys.resolve("decisions"),Clock.systemUTC()),f.codec,Clock.systemUTC());assertEquals("申请方提出结构主张",reloaded.load(f.caseId,id).humanText());Files.delete(keys.resolve("decisions").resolve(id+".key"));f.send("case-author","GET",versions()+"/"+id,null,503);assertEquals(1,count("adj3_material_decision_version"));}
    @Test void rowBindingTamperEvenWithUnchangedCipherShaRejects()throws Exception{var r=decide("accept",decision(4,0,"ACCEPT"),200);f.databases.db.update("UPDATE adj3_material_decision_version SET request_key='changed'");f.send("case-author","GET",versions()+"/"+r.path("version").path("versionId").asText(),null,503);}
    @Test void invalidPaginationRejected()throws Exception{f.send("case-author","GET",versions()+"?limit=51",null,400);f.send("case-author","GET",versions()+"?afterOrganizationVersion=-1",null,400);}

    @Test void concurrentCompetingAcceptsExactlyOnePrimaryCommit()throws Exception{
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{
            java.util.concurrent.Callable<Integer> call=()->{var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.endpoint+decisionPath())).header("Authorization","Bearer "+f.tokens.get("case-author")).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(java.net.http.HttpRequest.BodyPublishers.ofString(f.mapper.writeValueAsString(decision(4,0,"ACCEPT")))).build();return f.member.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();};
            var results=pool.invokeAll(List.of(call,call));var codes=new ArrayList<Integer>();for(var future:results)codes.add(future.get());codes.sort(Integer::compareTo);assertEquals(List.of(200,409),codes);assertEquals(1,count("adj3_material_decision_version"));assertEquals(5,count("adj2_case_receipt"));assertEquals(1,store.totals(policy).calls());
        }finally{pool.shutdownNow();}
    }
    @Test void sourceWithdrawalAfterBodyInsertRollsBackPrimaryTransaction()throws Exception{raceDuringSave(true);}
    @Test void modelGrantWithdrawalAfterBodyInsertRollsBackPrimaryTransaction()throws Exception{raceDuringSave(false);}
    void raceDuringSave(boolean source)throws Exception{
        var injected=new java.util.concurrent.atomic.AtomicBoolean();var spy=new org.springframework.jdbc.core.JdbcTemplate(f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class).getDataSource()){@Override public int update(String sql,Object...args){int n=super.update(sql,args);if(sql.startsWith("INSERT INTO adj3_material_decision_version")){injected.set(true);try{if(source)revokeSource();else f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grantId+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);}catch(Exception e){throw new IllegalStateException(e);}}return n;}};
        var dao=new JdbcMaterialDecisionStore(spy,new MaterialDecisionVault(keys.resolve("decisions"),Clock.systemUTC()),f.codec,Clock.systemUTC());
        var app=new MaterialDecisionService(f.context.getBean(CaseWorkRepository.class),dao,store,organization,policy,f.context.getBean(org.springframework.jdbc.core.JdbcTemplate.class),f.context.getBean(CaseSourceAuthority.class),f.context.getBean(ProductGrantWatermark.class),f.codec,new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions(new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Limits(2000,2000)),Clock.systemUTC(),f.mapper,new TransactionTemplate(f.context.getBean(org.springframework.transaction.PlatformTransactionManager.class)));
        var jwt=f.context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(f.tokens.get("case-author"));var access=new CaseWorkAccess(f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),jwt,f.issuer,Clock.systemUTC());
        assertThrows(RuntimeException.class,()->app.decide(f.caseId,runId,"c1",new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Request(4,0,cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Action.ACCEPT,null,null),"during-save",access));assertTrue(injected.get(),"Fault must occur after actual encrypted row insert, not a preflight rejection");unchanged();assertEquals(0,count("adj3_material_decision_head"));
    }

    @Test void rawDuplicateHttpBodyDoesNotReachPersistence()throws Exception{var raw="{\"expectedWorkVersion\":4,\"expectedWorkVersion\":5,\"expectedOrganizationVersion\":0,\"action\":\"ACCEPT\"}";var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.endpoint+decisionPath())).header("Authorization","Bearer "+f.tokens.get("case-author")).header("Content-Type","application/json").header("Idempotency-Key","duplicate-json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(raw)).build();var res=f.member.send(req,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(400,res.statusCode());assertEquals("INVALID_COMMAND_BODY",f.mapper.readTree(res.body()).path("code").asText());unchanged();}
    @Test void removingCurrentWritePermissionStillAllowsReadButRejectsAdoption()throws Exception{String id=f.databases.db.queryForObject("SELECT grant_id FROM id2_grant WHERE action_code='WRITE' AND status='ACTIVE'",String.class);f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+id+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);decide("write-denied",decision(4,0,"ACCEPT"),403);unchanged();}
    @Test void deletingDerivedRowCannotResetFirstDecisionOrHideHistory()throws Exception{decide("one",decision(4,0,"ACCEPT"),200);f.databases.db.update("DELETE FROM adj3_material_decision_version");decide("two",decision(5,1,"ACCEPT"),503);assertEquals(5,count("adj2_case_receipt"));}
    @Test void historyExplicitlyStaleAfterInputChange()throws Exception{var r=decide("one",decision(4,0,"ACCEPT"),200);f.command("/materials",5,((com.fasterxml.jackson.databind.node.ObjectNode)f.pack.path("materials").get(0).deepCopy()).put("id","material-new").put("supersedesMaterialId","material-1"),"material");var h=f.send("case-author","GET",versions()+"/"+r.path("version").path("versionId").asText(),null,200);assertTrue(h.path("stale").asBoolean());f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+r.path("version").path("versionId").asText()+"/revisions",decision(6,1,"EDIT").put("humanText","旧输入不能改").put("reason","应重整理"),"stale-edit",409);}

    @Test void authorityChangeAfterPrimaryCommitRejectsResponseButRetainsExactReceipt()throws Exception{
        var jwt=f.context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder.class).decode(f.tokens.get("case-author"));var actual=new CaseWorkAccess(f.databases.db,f.context.getBean(ProductGrantWatermark.class),f.context.getBean(CaseSourceAuthority.class),jwt,f.issuer,Clock.systemUTC());
        var once=new java.util.concurrent.atomic.AtomicBoolean();var access=new CaseWorkRepository.Access(){public String actor(){return actual.actor();}public String identity(){return actual.identity();}public long generation(){return actual.generation();}public void assertUnchanged(long generation){actual.assertUnchanged(generation);}public void sources(CaseWorkRepository.Scope scope,List<Source> sources,String use){actual.sources(scope,sources,use);}public void require(CaseWorkRepository.Scope scope,String action){
            if(action.equals("WRITE")&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&f.databases.db.queryForObject("SELECT head_version FROM adj2_case_work",Long.class)==5&&once.compareAndSet(false,true)){try{f.send(f.maintenance,null,"POST","/v2/adjudication/grants/"+grantId+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),UUID.randomUUID().toString(),200);}catch(Exception e){throw new IllegalStateException(e);}}actual.require(scope,action);}};
        assertThrows(RuntimeException.class,()->service.decide(f.caseId,runId,"c1",new cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Request(4,0,cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.Action.ACCEPT,null,null),"after-commit",access));
        assertTrue(once.get());assertEquals(5,count("adj2_case_receipt"));assertEquals(1,count("adj3_material_decision_version"));String id=f.databases.db.queryForObject("SELECT version_id FROM adj3_material_decision_version",String.class);assertTrue(Files.exists(keys.resolve("decisions").resolve(id+".key")));f.send("case-author","GET",versions()+"/"+id,null,403);
        var scope=new ProductGrants.Scope(f.issuer,"case-author",f.pack.path("caseInput").path("organization").asText(),"ADJUDICATION",f.caseId,f.pack.path("caseInput").path("dataScope").asText(),"ADJ_CASE",f.caseId,1,"MODEL_EXECUTE","CASE_AUXILIARY");long epoch=f.databases.db.queryForObject("SELECT authorization_epoch FROM id2_current_epoch WHERE action_code='MODEL_EXECUTE'",Long.class);f.send(f.maintenance,null,"POST","/v2/adjudication/grants",f.mapper.valueToTree(new CaseGrantController.Issue(scope,Instant.now().plusSeconds(3600),epoch)),UUID.randomUUID().toString(),200);assertEquals(id,decide("after-commit",decision(4,0,"ACCEPT"),200).path("version").path("versionId").asText());assertEquals(1,count("adj3_material_decision_version"));
    }

    @Test void enabledProductionConfigurationUsesSamePrimaryTransactionAndSurvivesApplicationRestart()throws Exception{
        Path policyFile=keys.resolve("policy.json");Files.writeString(policyFile,f.mapper.writeValueAsString(policy));
        f.configurationOverrides.put("adjudication.v2.materials.enabled",true);f.configurationOverrides.put("adjudication.v2.materials.policy-file",policyFile.toAbsolutePath().toString());f.configurationOverrides.put("adjudication.v2.materials.key-directory",keys.resolve("model").toAbsolutePath().toString());f.configurationOverrides.put("adjudication.v2.materials.decisions.enabled",true);f.configurationOverrides.put("adjudication.v2.materials.decisions.key-directory",keys.resolve("decisions").toAbsolutePath().toString());
        // No valid provider credential and no generate request: production adapter is constructed, never invoked.
        f.configurationOverrides.put("DEEPSEEK_API_KEY","component-config-only-never-call");f.restart();
        assertInstanceOf(cn.xinji.agentplatform.adjudication.materials.adapter.DeepSeekMaterialOrganizationAdapter.class,f.context.getBean(MaterialOrganizationModel.class));
        var r=decide("product-config",decision(4,0,"ACCEPT"),200);String id=r.path("version").path("versionId").asText();assertEquals(1,f.context.getBean(JdbcMaterialRunStore.class).totals(policy).calls());f.restart();
        assertEquals(r,f.send("case-author","GET",versions()+"/"+id,null,200));assertEquals(r,decide("product-config",decision(4,0,"ACCEPT"),200));assertEquals(1,calls.get());assertEquals(1,count("adj3_material_decision_version"));
    }

    @Test void sourceApprovalDeadlineShorterThanParentRunIsInheritedAndNeverRenewed()throws Exception{
        var r=decide("deadline",decision(4,0,"ACCEPT"),200);Instant expires=Instant.parse(r.path("version").path("expiresAt").asText());Instant parent=f.databases.db.queryForObject("SELECT expires_at FROM adj3_material_run",java.sql.Timestamp.class).toInstant();assertTrue(expires.isBefore(parent));
        var scope=new CaseWorkRepository.Scope(f.caseId,f.pack.path("caseInput").path("organization").asText(),f.pack.path("caseInput").path("dataScope").asText());var originals=new ArrayList<Source>();for(var n:f.pack.path("materials"))originals.add(f.codec.request(n,Material.class).source());assertEquals(f.context.getBean(CaseSourceAuthority.class).permittedUntil(scope,originals,"MODEL").truncatedTo(java.time.temporal.ChronoUnit.MILLIS),expires);
        var n=f.send(f.member,f.tokens.get("case-author"),"POST",versions()+"/"+r.path("version").path("versionId").asText()+"/revisions",decision(5,1,"EDIT").put("humanText","期限不重算").put("reason","人工措辞更正"),"deadline-edit",200);assertEquals(r.path("version").path("expiresAt"),n.path("version").path("expiresAt"));
    }
    void revokeSource()throws Exception{var s=f.codec.request(f.pack.path("materials").get(0),Material.class).source();var scope=new CaseWorkRepository.Scope(f.caseId,f.pack.path("caseInput").path("organization").asText(),f.pack.path("caseInput").path("dataScope").asText());String k=f.context.getBean(CaseSourceAuthority.class).key(scope,s);f.send(f.maintenance,null,"POST","/v2/adjudication/source-uses/"+k+"/revoke",f.mapper.createObjectNode().put("expectedVersion",1),null,200);}
}
