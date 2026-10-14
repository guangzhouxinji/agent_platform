package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Product input + immutable binding and real grant transactions; isolated H2, not formal DM8/TLS acceptance. */
class ProductComparisonWorkerDelegationBindingTest {
    final ProductComparisonInputTest input=new ProductComparisonInputTest();
    JdbcTemplate db;
    ProductGrantWatermark watermark;
    ProductComparisonWorkerDelegations application;
    ProductWorkerDelegations core;
    ProductComparisonInputs.View view;
    ProductComparisonWorkerDelegations.Issue command;
    Jwt human;
    String service;
    String task;
    @BeforeEach void setup() throws Exception {
        input.inputSetup();db=input.db;task=input.goal.taskId();
        for(String name:List.of("V046__product_workload_subject.sql","V047__product_task_worker_delegation.sql",
                "V098__product_comparison_worker_delegation.sql")) ProductSourceScopeGrantTest.script(input.main,"db/dm8/"+name);
        var authorityData=ProductSourceScopeGrantTest.database();
        ProductSourceScopeGrantTest.script(authorityData,"db/identity-authority/V001__grant_watermark.sql");
        var authority=new JdbcTemplate(authorityData);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",
                ProductComparisonPreparationTest.ISSUER,ProductGrantHistoryBatch.digest(db,ProductComparisonPreparationTest.ISSUER,true,true));
        watermark=new ProductGrantWatermark(db,authority,ProductComparisonPreparationTest.ISSUER);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(input.main));
        var directory=new JdbcProductTaskDirectory(db,input.clock);
        var taskData=directory.find(task).orElseThrow();
        var grants=new ProductGrants(db,tx,input.clock);
        var workload=new ProductWorkloadSubjects(db,tx,watermark,input.clock,ProductComparisonPreparationTest.ISSUER)
                .register(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest("binding-certificate-C".getBytes(java.nio.charset.StandardCharsets.UTF_8))),taskData.organization(),ProductWorkloadSubjects.Kind.AGENT,input.until,"binding-service-C","maintainer-C");
        service=workload.subjectId();
        watermark.change(()->grants.issue(new ProductGrants.Scope(ProductComparisonPreparationTest.ISSUER,"member",taskData.organization(),
                "CONSULTATION",task,taskData.dataScope(),"TASK",task,taskData.authorizationVersion(),"COMPARE","TASK_WORK"),
                input.until,0,"maintainer-C","binding-human-compare-C","c".repeat(64)));
        watermark.change(()->grants.issueComparisonWorkload(ProductComparisonPreparationTest.ISSUER,service,taskData.organization(),task,
                input.until,0,"maintainer-C","binding-service-compare-C","d".repeat(64)));
        Timestamp now=Timestamp.from(input.base.now);
        db.update("INSERT INTO id2_key_control VALUES(?,NULL,0)",ProductComparisonPreparationTest.ISSUER);
        db.update("INSERT INTO id2_signing_key VALUES('key',?,?,?,?, 'ACTIVE',?,NULL)",
                ProductComparisonPreparationTest.ISSUER,new byte[]{1},new byte[]{1},new byte[12],now);
        db.update("INSERT INTO id2_session VALUES('session','jti',?,?,'client','key',?,?,'ACTIVE',NULL)",
                ProductComparisonPreparationTest.ISSUER,"member",now,Timestamp.from(input.until));
        human=Jwt.withTokenValue("binding-C-only").header("alg","RS256").issuer(ProductComparisonPreparationTest.ISSUER)
                .subject("member").claim("sid","session").claim("jti","jti").build();
        org.mockito.Mockito.when(input.documents.read(org.mockito.ArgumentMatchers.eq(task),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(human),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(input.candidate);
        core=new ProductWorkerDelegations(db,tx,watermark,input.access,directory,input.clock,ProductComparisonPreparationTest.ISSUER);
        application=new ProductComparisonWorkerDelegations(db,core,input.comparisons,input.inputStore,input.json,input.clock);
        view=input.comparisons.create(task,input.create,"binding-input-C",human);
        command=new ProductComparisonWorkerDelegations.Issue(service,view.input().candidates().getFirst().candidateId(),
                view.inputSha(),input.until.minusSeconds(600));
    }
    ProductComparisonWorkerDelegations.Receipt issue(String key) {return application.issue(task,view.input().comparisonId(),command,human,key);}
    long count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private org.springframework.mock.web.MockHttpServletRequest workerRequest() throws Exception {
        var cert=org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(cert.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        org.mockito.Mockito.when(cert.getEncoded()).thenReturn("binding-certificate-C".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate",new java.security.cert.X509Certificate[]{cert});return request;
    }
    private ProductComparisonWorkerIntentAccess workerAccess() {
        return new ProductComparisonWorkerIntentAccess(new ProductComparisonWorkerTaskAuthority(db,watermark,
                new JdbcProductTaskDirectory(db,input.clock),input.clock,ProductComparisonPreparationTest.ISSUER),application);
    }
    @Test void realTaskGrantsAndBindingComposeWithoutHumanJwtOrSourceRead() throws Exception {
        var issued=issue("intent-composed");var worker=workerAccess();var request=workerRequest();
        org.mockito.Mockito.clearInvocations(input.documents);
        assertThat(worker.require(request,task,view.input().comparisonId(),command.candidateId(),view.inputSha(),issued.delegationId()))
                .isEqualTo(issued);
        org.mockito.Mockito.verifyNoInteractions(input.documents);watermark.assertCurrent();
        application.revoke(task,view.input().comparisonId(),issued.delegationId(),1,human,"intent-composed-revoke");
        assertThatThrownBy(()->worker.require(request,task,view.input().comparisonId(),command.candidateId(),view.inputSha(),issued.delegationId()))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void composedAuthorityRejectsLocalGrantTamperingBeforeReturningIntent() throws Exception {
        var issued=issue("intent-local-tamper");var worker=workerAccess();var request=workerRequest();
        db.update("UPDATE id2_grant SET action_code='WRITE' WHERE subject_id=? AND resource_type='TASK'",service);
        assertThatThrownBy(()->worker.require(request,task,view.input().comparisonId(),command.candidateId(),view.inputSha(),issued.delegationId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("high-water mark");
    }
    private ProductComparisonWorkerDelegations.Receipt intent(ProductComparisonWorkerDelegations.Receipt issued) {
        return application.currentWorkerIntent(task,view.input().comparisonId(),command.candidateId(),view.inputSha(),
                issued.delegationId(),service,view.input().taskVersion());
    }
    @Test void currentWorkerIntentReturnsOnlyCoordinatesAndDoesNotExecuteOrReadOriginals() {
        var issued=issue("intent-current");
        org.mockito.Mockito.clearInvocations(input.documents);
        assertThat(intent(issued)).isEqualTo(issued);
        org.mockito.Mockito.verifyNoInteractions(input.documents);
        assertThat(input.inputStore.read(task,view.input().comparisonId()).candidateProgress())
                .containsExactly(new ProductComparisonInputs.Progress(command.candidateId(),"QUEUED"));
        assertThat(count("con3_worker_delegation_event")).isEqualTo(1);
        assertThat(input.json.canonical(intent(issued))).doesNotContain("currentPreparationSnapshot","confirmedFeatures","CLAIMS");
    }
    @Test void workerIntentRejectsEveryForeignCoordinateAndTaskVersion() {
        var issued=issue("intent-coordinates");
        var c=view.input().comparisonId();var id=issued.delegationId();long v=view.input().taskVersion();
        java.util.List<Runnable> bad=List.of(
                ()->application.currentWorkerIntent("foreign",c,command.candidateId(),view.inputSha(),id,service,v),
                ()->application.currentWorkerIntent(task,"foreign",command.candidateId(),view.inputSha(),id,service,v),
                ()->application.currentWorkerIntent(task,c,"foreign",view.inputSha(),id,service,v),
                ()->application.currentWorkerIntent(task,c,command.candidateId(),"e".repeat(64),id,service,v),
                ()->application.currentWorkerIntent(task,c,command.candidateId(),view.inputSha(),"foreign",service,v),
                ()->application.currentWorkerIntent(task,c,command.candidateId(),view.inputSha(),id,"foreign",v),
                ()->application.currentWorkerIntent(task,c,command.candidateId(),view.inputSha(),id,service,v+1));
        for(var attempt:bad) assertThatThrownBy(attempt::run).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(intent(issued)).isEqualTo(issued);
    }
    @Test void workerIntentRejectsRevocationAndStaleCurrentInput() {
        var issued=issue("intent-revoked");
        application.revoke(task,view.input().comparisonId(),issued.delegationId(),1,human,"intent-revoke");
        assertThatThrownBy(()->intent(issued)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void workerIntentRejectsMissingCurrentPointersAndCandidateClosure() {
        var issued=issue("intent-pointer");
        db.update("DELETE FROM con5_comparison_current WHERE task_id=?",task);
        assertThatThrownBy(()->intent(issued)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        db.update("INSERT INTO con5_comparison_current VALUES(?,?)",task,view.input().comparisonId());
        db.update("DELETE FROM con5_feature_current WHERE task_id=?",task);
        assertThatThrownBy(()->intent(issued)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void workerIntentRejectsFeatureContentTamperingDespiteUnchangedCurrentPointer() {
        var issued=issue("intent-feature-tamper");
        db.update("UPDATE con5_feature SET user_text_json='\"altered\"' WHERE revision_id=?",view.input().command().featureRevisionId());
        assertThatThrownBy(()->intent(issued)).isInstanceOf(IllegalStateException.class).hasMessageContaining("digest");
    }
    @Test void workerIntentRejectsAnExpiredGoalEvenWhenSearchInputStillLives() {
        var issued=issue("intent-goal-expiry");
        db.update("UPDATE con3_goal_revision SET expires_at=confirmed_at + INTERVAL '1' SECOND WHERE task_id=?",task);
        application=new ProductComparisonWorkerDelegations(db,core,input.comparisons,input.inputStore,input.json,
                java.time.Clock.fixed(input.clock.instant().plusSeconds(2),java.time.ZoneOffset.UTC));
        assertThatThrownBy(()->intent(issued)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void workerIntentRejectsRecomputedBindingShaOutsideImmutableReceipt() {
        var issued=issue("intent-tamper");String changed="f".repeat(64);
        String hash=input.json.sha(java.util.Map.of("taskId",task,"comparisonId",view.input().comparisonId(),"candidateId",command.candidateId(),
                "inputSha",changed,"serviceSubject",service,"expiresAt",command.expiresAt(),"action","COMPARE"));
        db.update("UPDATE con5_comparison_delegation SET input_sha=?,binding_sha=? WHERE delegation_id=?",changed,hash,issued.delegationId());
        assertThatThrownBy(()->application.currentWorkerIntent(task,view.input().comparisonId(),command.candidateId(),changed,
                issued.delegationId(),service,view.input().taskVersion())).isInstanceOf(IllegalStateException.class).hasMessageContaining("digest");
    }
    @Test void currentInputCandidateIntentReplaysReadsAndRevokesWithoutExecuting() {
        var result=issue("binding-one");
        assertThat(result.inputSha()).isEqualTo(view.inputSha());
        assertThat(result.candidateId()).isEqualTo(command.candidateId());
        assertThat(issue("binding-one")).isEqualTo(result);
        assertThat(application.read(task,view.input().comparisonId(),result.delegationId(),human)).isEqualTo(result);
        assertThat(count("con3_worker_delegation")).isEqualTo(1);
        assertThat(count("con5_comparison_delegation")).isEqualTo(1);
        assertThat(input.comparisons.consume(task,view.input().comparisonId(),human).candidateProgress())
                .containsExactly(new ProductComparisonInputs.Progress(command.candidateId(),"QUEUED"));
        assertThat(application.revoke(task,view.input().comparisonId(),result.delegationId(),1,human,"binding-revoke").status()).isEqualTo("REVOKED");
        assertThat(application.revoke(task,view.input().comparisonId(),result.delegationId(),1,human,"binding-revoke").version()).isEqualTo(2);
        assertThatThrownBy(()->issue("binding-one")).isInstanceOf(IllegalStateException.class);
        assertThat(count("con5_comparison_delegation")).isEqualTo(1);watermark.assertCurrent();
    }
    @Test void wrongShaCandidateExpiredOrExcessiveWindowDoesNotLeaveIntent() {
        for(var c:List.of(new ProductComparisonWorkerDelegations.Issue(service,command.candidateId(),"f".repeat(64),command.expiresAt()),
                new ProductComparisonWorkerDelegations.Issue(service,"missing",command.inputSha(),command.expiresAt()),
                new ProductComparisonWorkerDelegations.Issue(service,command.candidateId(),command.inputSha(),input.until.plusSeconds(1)),
                new ProductComparisonWorkerDelegations.Issue(service,command.candidateId(),command.inputSha(),input.base.now))) {
            assertThatThrownBy(()->application.issue(task,view.input().comparisonId(),c,human,"invalid-C"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(count("con3_worker_delegation")).isZero();assertThat(count("con5_comparison_delegation")).isZero();
    }
    @Test void oldInputIsNotDelegatedButItsExistingIntentCanBeRevoked() {
        var first=issue("binding-old");
        var next=input.commandFor(input.confirmed,view.input().comparisonId());
        input.comparisons.create(task,next,"binding-next-input",human);
        assertThatThrownBy(()->issue("binding-new-key")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(application.revoke(task,view.input().comparisonId(),first.delegationId(),1,human,"revoke-stale").status()).isEqualTo("REVOKED");
    }
    @Test void boundInputCannotBeChangedOnTheSameRequestKey() {
        issue("binding-stable");
        var changed=new ProductComparisonWorkerDelegations.Issue(service,command.candidateId(),command.inputSha(),command.expiresAt().minusSeconds(1));
        assertThatThrownBy(()->application.issue(task,view.input().comparisonId(),changed,human,"binding-stable"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("con3_worker_delegation")).isEqualTo(1);watermark.assertCurrent();
    }
    @Test void missingBindingTableRollsBackDelegateReceiptEventAndIndependentIntent() {
        db.execute("DROP TABLE con5_comparison_delegation");
        assertThatThrownBy(()->issue("binding-failed-insert")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("con3_worker_delegation","con3_worker_delegation_event","con3_worker_delegation_receipt"))
            assertThat(count(table)).isZero();
        watermark.assertCurrent();
    }
    @Test void missingOrTamperedBindingCannotBeRecreatedOnReplay() {
        var first=issue("binding-preserved");
        db.update("DELETE FROM con5_comparison_delegation WHERE delegation_id=?",first.delegationId());
        assertThatThrownBy(()->issue("binding-preserved")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(count("con5_comparison_delegation")).isZero();watermark.assertCurrent();
    }
    @Test void evenRecomputedBindingShaCannotChangeInputOutsideObservedReceipt() {
        var first=issue("binding-tamper");
        String changed="f".repeat(64);
        String hash=input.json.sha(java.util.Map.of("taskId",task,"comparisonId",view.input().comparisonId(),"candidateId",command.candidateId(),
                "inputSha",changed,"serviceSubject",service,"expiresAt",command.expiresAt(),"action","COMPARE"));
        db.update("UPDATE con5_comparison_delegation SET input_sha=?,binding_sha=? WHERE delegation_id=?",changed,hash,first.delegationId());
        assertThatThrownBy(()->application.read(task,view.input().comparisonId(),first.delegationId(),human))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("digest");
    }
    @Test void reverseRejectsRetainedHistoryAndEmptyRoundTripPreservesParentTables() throws Exception {
        ProductSourceScopeGrantTest.script(input.main,"db/dm8/U098__product_comparison_worker_delegation.sql");
        ProductSourceScopeGrantTest.script(input.main,"db/dm8/V098__product_comparison_worker_delegation.sql");
        var first=issue("binding-retained");
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(input.main,"db/dm8/U098__product_comparison_worker_delegation.sql"))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(application.read(task,view.input().comparisonId(),first.delegationId(),human).inputSha()).isEqualTo(view.inputSha());
    }
    @Test void controllerUsesStrictJsonCurrentHumanAndExactInputReceipt() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ProductComparisonWorkerDelegationController(application,input.json))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        String path="/v2/consultation/tasks/"+task+"/patent-comparisons/"+view.input().comparisonId()+"/worker-delegations";
        var status=org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-C")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(input.json.canonical(command))).andExpect(status.isUnauthorized());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(human));
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-C")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(input.json.canonical(command).replace("{","{\"action\":\"WRITE\",")))
                    .andExpect(status.isBadRequest());
            String raw=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-C")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(input.json.canonical(command)))
                    .andExpect(status.isOk()).andReturn().getResponse().getContentAsString();
            assertThat(raw).contains("\"inputSha\":\""+view.inputSha()+"\"").doesNotContain("currentPreparationSnapshot","human_grant_id","CLAIMS");
            String id=input.mapper.readTree(raw).path("delegationId").asText();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/"+id)).andExpect(status.isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/"+id+"/revoke").header("Idempotency-Key","http-revoke")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}")).andExpect(status.isOk());
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
