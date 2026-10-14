package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptJdbc.ts;
import static org.assertj.core.api.Assertions.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptCreateReconciliationTest {
    @TempDir Path root;
    private ProductPromptWorkspaceAuthorizationFixture f;
    private ProductPromptAuthorityStore authority;
    private ProductPromptCreateRequestStore requests;
    private ProductPromptCreateReconciliation reconciliation;
    private TransactionTemplate suspended;
    private Workspace workspace;
    private PreparedWrite prepared;
    private ProductPromptCreateRequest request;
    private String failAfter;
    private boolean saveSchema;

    @BeforeEach void setup()throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);
        ProductHumanTaskFactsFixture.migrate(f.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
        var db=new JdbcTemplate(f.authoritySource){
            @Override public int update(String sql,Object...args){
                int result=super.update(sql,args);
                if(failAfter!=null&&sql.startsWith(failAfter))throw new DataAccessResourceFailureException("failure after authority write");
                return result;
            }
        };
        authority=new ProductPromptAuthorityStore(db,f.authorityManager,f.clock);
        requests=new ProductPromptCreateRequestStore(authority,f.mapper,f.clock);
        reconciliation=new ProductPromptCreateReconciliation(f.store,authority,f.clock);
        suspended=new TransactionTemplate(f.mainManager);suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        workspace=f.mainTx.execute(status->f.store.workspace(f.workspaceId,false));
        var origin=new PromptRevision.Provenance("source:original","member",f.now,"member",f.now,"CREATE_DRAFT",List.of(),"input-permission.json",workspace.policyRef());
        var reference=PromptRevision.referenceFor(id(),id(),1,PromptRevision.Kind.SYSTEM,"Draft","Original draft",Set.of("b","a"),Set.of("query"),"internal","zh",0,List.of());
        var revision=new PromptRevision(reference,PromptRevision.Kind.SYSTEM,"Draft","Original draft",Set.of("b","a"),Set.of("query"),"internal","zh",0,List.of(),origin);
        var write=new Write(workspace.id(),revision,0,Operation.CREATE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"member",hash("key"),hash("command"),
                f.now.plusSeconds(300),f.now.plusSeconds(900));
        prepared=f.mainTx.execute(status->f.store.prepare(write));
        var r=prepared.receipt();var h=prepared.after();var binding=f.bindings.get(workspace.id());
        var pending=new Pending(r.promptId(),workspace.id(),revision.kind(),0,null,"0".repeat(64),h.scalarSha(),r.revisionId(),id(),Operation.CREATE,
                r.keySha(),r.requestSha(),f.now.plusSeconds(60),h.containerUntil(),h.controlUntil());
        request=requests.prepared(pending,prepared,new ProductPromptCreateRequest.Evidence(1,workspace.registrySha(),binding.approvalRef(),binding.approvalSha(),
                workspace.policyRef(),workspace.policySha(),"input-permission.json",hash("input-permission"),origin.generationSourceRef()));
    }

    @Test void freshAndPersistedAllBeforeAreRecognizedWithoutReadingBody(){
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        reserve();assertThat(observe(find()).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        assertThat(f.bodyReads.get()).isZero();assertThat(count("idp_revision_witness")).isZero();
    }

    @Test void exactMainCommitCompletesThreeAuthorityWritesAndReplayDoesNotRepeat(){
        reserve();append();Receipt first=finish();assertThat(first).isEqualTo(request.receipt());
        assertThat(find().state()).isEqualTo("READY");assertThat(count("idp_revision_witness")).isEqualTo(1);
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("READY");
        assertThat(finish()).isEqualTo(first);
        assertThat(count("idp_revision_witness")).isEqualTo(1);assertThat(mainCount("conp_revision")).isEqualTo(1);
        assertThat(mainCount("conp_event")).isEqualTo(1);assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"intent","body"})
    void expiredIntentOrBodyCanSettleExactCommittedScalarsWithoutReadingText(String deadline){
        reserve();append();f.now="intent".equals(deadline)?request.pending().intentUntil():request.header().bodyUntil();
        assertThat(finish()).isEqualTo(request.receipt());assertThat(f.bodyReads.get()).isZero();
        if("body".equals(deadline))assertThatThrownBy(()->f.mainTx.execute(status->f.store.readRevision(request.receipt().promptId(),request.receipt().revisionId(),authority)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void auditDeadlineCannotBeSettled(){
        reserve();append();f.now=request.header().auditUntil();
        assertThatThrownBy(this::finish).isInstanceOf(IllegalStateException.class);
        assertThat(count("idp_revision_witness")).isZero();assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"conp_prompt","conp_revision","conp_event","conp_receipt"})
    void eachIsolatedMainTableResidueIsUnknown(String retained){
        reserve();append();f.db.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt"))if(!table.equals(retained))f.db.update("DELETE FROM "+table);
        assertThat(observe(find()).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThat(count("idp_revision_witness")).isZero();assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"null-body","cleared","header-time","extra-event"})
    void incompleteOrChangedAfterStateNeverProducesCommitProof(String mutation){
        reserve();append();
        switch(mutation){
            case "null-body"->{f.db.execute("ALTER TABLE conp_revision DROP CONSTRAINT ck_conp_revision");f.db.update("UPDATE conp_revision SET revision_json=NULL");}
            case "cleared"->f.db.update("UPDATE conp_revision SET body_state='CLEARED',revision_json=NULL");
            case "header-time"->f.db.update("UPDATE conp_revision SET submitted_at=?",ts(request.header().submittedAt().plusMillis(1)));
            case "extra-event"->{f.db.execute("ALTER TABLE conp_event DROP CONSTRAINT uq_conp_event");var r=request.receipt();
                f.db.update("INSERT INTO conp_event SELECT ?,prompt_id,revision_id,generation,operation,issuer,subject_id,submitted_at,request_sha,result_sha,audit_until FROM conp_event",id());}
            default->throw new AssertionError(mutation);
        }
        assertThat(observe(find()).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"INSERT INTO idp_revision_witness","UPDATE idp_prompt_control SET state='READY'","UPDATE idp_prompt_request SET state='READY'"})
    void everyAuthorityCompletionFailureRollsBackAllThreeEvenIfCaught(String failedWrite){
        reserve();append();failAfter=failedWrite;
        assertThatThrownBy(()->f.mainTx.executeWithoutResult(main->{
            var proof=reconciliation.observe(workspace,request).exactAfter();
            suspended.executeWithoutResult(outer->f.authorityTx.executeWithoutResult(inner->{
                assertThatThrownBy(()->requests.complete(proof)).isInstanceOf(DataAccessResourceFailureException.class);
            }));
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(count("idp_revision_witness")).isZero();
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("PENDING");
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        failAfter=null;assertThat(finish()).isEqualTo(request.receipt());
    }

    @Test void matchingExistingWitnessIsReusedButDriftCannotBeRepaired(){
        reserve();append();insertWitness();
        f.independent.update("UPDATE idp_revision_witness SET record_sha=?",hash("wrong"));
        assertThatThrownBy(this::finish).isInstanceOf(IllegalStateException.class);
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("PENDING");
        f.independent.update("UPDATE idp_revision_witness SET record_sha=?",request.header().recordSha());
        assertThat(finish()).isEqualTo(request.receipt());assertThat(count("idp_revision_witness")).isEqualTo(1);
    }

    @Test void proofRequiresActualMainSuspensionSameThreadAndLiveOriginalTransaction()throws Exception{
        reserve();append();
        var stale=f.mainTx.execute(status->reconciliation.observe(workspace,request).exactAfter());
        assertThatThrownBy(()->f.authorityTx.execute(status->requests.complete(stale))).isInstanceOf(IllegalStateException.class);
        f.mainTx.executeWithoutResult(status->{
            var proof=reconciliation.observe(workspace,request).exactAfter();
            assertThatThrownBy(()->f.authorityTx.execute(other->requests.complete(proof))).isInstanceOf(IllegalStateException.class);
        });
        f.mainTx.executeWithoutResult(status->{
            var proof=reconciliation.observe(workspace,request).exactAfter();
            try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
                var attempt=executor.submit(()->f.authorityTx.execute(other->requests.complete(proof)));
                assertThatThrownBy(()->attempt.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            }
        });
        assertThat(count("idp_revision_witness")).isZero();assertThat(finish()).isEqualTo(request.receipt());
    }

    @Test void explicitSuspendRestoresExactHolderAndProofCannotBeConsumedTwice(){
        reserve();append();
        f.mainTx.executeWithoutResult(status->{
            Object holder=TransactionSynchronizationManager.getResource(f.mainSource);
            var proof=reconciliation.observe(workspace,request).exactAfter();
            Receipt receipt=suspended.execute(outer->f.authorityTx.execute(inner->requests.complete(proof)));
            assertThat(receipt).isEqualTo(request.receipt());assertThat(TransactionSynchronizationManager.getResource(f.mainSource)).isSameAs(holder);
            assertThatThrownBy(()->suspended.execute(outer->f.authorityTx.execute(inner->requests.complete(proof)))).isInstanceOf(IllegalStateException.class);
        });
        assertThat(count("idp_revision_witness")).isEqualTo(1);
    }

    @Test void readyOriginalRequestSurvivesLaterSaveWithoutRollingBackCurrentControl(){
        reserve();append();finish();f.now=f.now.plusSeconds(1);
        var origin=prepared.write().revision().provenance();
        var ref=PromptRevision.referenceFor(request.pending().promptId(),id(),2,PromptRevision.Kind.SYSTEM,"Updated","New version",Set.of(),Set.of(),"internal","zh",0,List.of());
        var revision=new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Updated","New version",Set.of(),Set.of(),"internal","zh",0,List.of(),
                new PromptRevision.Provenance(origin.generationSourceRef(),origin.createdBy(),origin.createdAt(),"member",f.now,"SAVE_DRAFT",List.of(),origin.contentApprovalRef(),origin.retentionPolicyRef()));
        var write=new Write(workspace.id(),revision,1,Operation.SAVE,request.receipt().issuer(),"member",hash("save-key"),hash("save-command"),f.now.plusSeconds(300),f.now.plusSeconds(900));
        var next=f.mainTx.execute(status->f.store.prepare(write));var before=next.before();var after=next.after();
        var pending=new Pending(before.promptId(),workspace.id(),before.kind(),1,before.revisionId(),before.scalarSha(),after.scalarSha(),after.revisionId(),id(),
                Operation.SAVE,write.keySha(),write.requestSha(),f.now.plusSeconds(60),after.containerUntil(),after.controlUntil());
        f.authorityTx.executeWithoutResult(status->authority.reserve(pending));f.mainTx.executeWithoutResult(status->f.store.append(next));
        f.authorityTx.executeWithoutResult(status->authority.complete(pending,next.after(),next.header(),next.receipt()));
        assertThat(finish()).isEqualTo(request.receipt());
        assertThat(f.independent.queryForObject("SELECT generation FROM idp_prompt_control",Long.class)).isEqualTo(2L);
        assertThat(f.independent.queryForObject("SELECT head_revision_id FROM idp_prompt_control",String.class)).isEqualTo(after.revisionId());
        assertThat(count("idp_revision_witness")).isEqualTo(2);assertThat(mainCount("conp_revision")).isEqualTo(2);
    }

    @Test void competingCompletionsYieldOneWitnessAndSameReceipt()throws Exception{
        reserve();append();var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->{start.await(5,TimeUnit.SECONDS);return finish();});
            var second=executor.submit(()->{start.await(5,TimeUnit.SECONDS);return finish();});start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(request.receipt());assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(request.receipt());
        }
        assertThat(count("idp_revision_witness")).isEqualTo(1);assertThat(mainCount("conp_event")).isEqualTo(1);
    }

    @Test void independentControlDriftCannotAuthorizeAnAllBeforeRetry(){
        reserve();f.independent.update("UPDATE idp_prompt_control SET expected_after_sha=?",hash("drift"));
        assertThatThrownBy(this::find).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::reserve).isInstanceOf(IllegalStateException.class);assertThat(mainCount("conp_prompt")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"foreign-revision","foreign-event","foreign-key"})
    void crossPromptCoordinatesCannotBeMistakenForNoPreviousWrite(String collision){
        var base=prepared.write();var value=base.revision();var r=request.receipt();
        String revisionId="foreign-revision".equals(collision)?r.revisionId():id();
        var ref=PromptRevision.referenceFor(id(),revisionId,1,value.kind(),value.name(),value.body(),value.scenarioIds(),value.intentIds(),value.audienceRef(),
                value.language(),value.displayOrder(),value.variables());
        var foreign=new PromptRevision(ref,value.kind(),value.name(),value.body(),value.scenarioIds(),value.intentIds(),value.audienceRef(),value.language(),
                value.displayOrder(),value.variables(),value.provenance());
        var write=new Write(workspace.id(),foreign,0,Operation.CREATE,r.issuer(),r.subject(),"foreign-key".equals(collision)?r.keySha():hash(id()),
                r.requestSha(),base.bodyUntil(),base.auditUntil());
        f.mainTx.executeWithoutResult(status->f.store.append(f.store.prepare(write,"foreign-event".equals(collision)?r.id():id())));
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        assertThat(count("idp_revision_witness")).isZero();assertThat(f.bodyReads.get()).isZero();
    }

    @Test void observationRequiresCurrentWorkspaceAndActualMainTransaction(){
        assertThatThrownBy(()->reconciliation.observe(workspace,request)).isInstanceOf(IllegalStateException.class);
        f.db.update("UPDATE conp_workspace SET state='CLOSED'");
        assertThatThrownBy(()->observe(request)).isInstanceOf(IllegalStateException.class);
        assertThat(count("idp_revision_witness")).isZero();
    }

    @Test void saveAllBeforeRebuildsOriginalPreparedAcrossStoresWithoutOldDeadlineColumns()throws Exception{
        reserve();append();finish();startSave("save",120,600);
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        reserve();var original=request;var write=prepared.write();f.now=f.now.plusSeconds(10);
        requests=new ProductPromptCreateRequestStore(authority,f.mapper,f.clock);
        request=find();assertThat(request).isEqualTo(original);
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.ALL_BEFORE);
        prepared=f.mainTx.execute(status->{var rebuilt=f.store.prepare(write,original.receipt().id());requests.requirePrepared(original,rebuilt);return rebuilt;});
        assertThat(prepared.after().containerUntil()).isAfter(prepared.header().bodyUntil());
        assertThat(prepared.after().controlUntil()).isAfter(prepared.header().auditUntil());
        assertThat(prepared.before().generation()).isEqualTo(1);append();assertThat(finish()).isEqualTo(original.receipt());
        assertThat(f.bodyReads.get()).isZero();assertThat(mainCount("conp_revision")).isEqualTo(2);
    }

    @Test void createAndSaveSameTextKeyStaySeparateAndReadySaveSurvivesNextGeneration()throws Exception{
        reserve();append();finish();var create=request;startSave("key",300,900);reserve();append();finish();var firstSave=find();
        assertThat(firstSave.key().keySha()).isEqualTo(create.key().keySha());assertThat(firstSave.key()).isNotEqualTo(create.key());
        startSave("save-two",300,900);reserve();
        assertThatThrownBy(()->f.authorityTx.execute(status->requests.find(firstSave.key()))).isInstanceOf(IllegalStateException.class);
        append();finish();var latest=f.mainTx.execute(status->f.store.head(firstSave.pending().promptId(),false));
        request=firstSave;assertThat(finish()).isEqualTo(firstSave.receipt());
        request=create;assertThat(finish()).isEqualTo(create.receipt());
        assertThat(f.independent.queryForObject("SELECT head_revision_id FROM idp_prompt_control",String.class)).isEqualTo(latest.revisionId());
        assertThat(f.independent.queryForObject("SELECT generation FROM idp_prompt_control",Long.class)).isEqualTo(3);
        assertThat(count("idp_revision_witness")).isEqualTo(3);assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"intent","body"})
    void saveExactAfterCanSettleExpiredIntentOrNewBody(String expiry)throws Exception{
        reserve();append();finish();startSave("save",120,600);reserve();append();
        f.now="intent".equals(expiry)?request.pending().intentUntil():request.header().bodyUntil();
        assertThat(finish()).isEqualTo(request.receipt());assertThat(count("idp_revision_witness")).isEqualTo(2);assertThat(f.bodyReads.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"INSERT INTO idp_revision_witness","UPDATE idp_prompt_control SET state='READY'","UPDATE idp_prompt_request SET state='READY'"})
    void saveCompletionFailuresRollbackWitnessControlAndRequestTogether(String failure)throws Exception{
        reserve();append();finish();startSave("save",300,900);reserve();append();failAfter=failure;
        assertThatThrownBy(this::finish).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("idp_revision_witness")).isEqualTo(1);assertThat(find().state()).isEqualTo("PENDING");
        assertThat(f.independent.queryForObject("SELECT generation FROM idp_prompt_control",Long.class)).isEqualTo(1L);
        failAfter=null;assertThat(finish()).isEqualTo(request.receipt());
    }

    @ParameterizedTest @ValueSource(strings={"old-revision","old-sha","old-container","target-revision","target-event","target-receipt"})
    void saveMixedBeforeOrTargetResidueNeverPermitsRetry(String fault)throws Exception{
        reserve();append();finish();startSave("save",120,600);reserve();var r=request.receipt();
        switch(fault){
            case "old-revision"->f.db.update("UPDATE conp_prompt SET head_revision_id=?",id());
            case "old-sha"->f.db.update("UPDATE conp_prompt SET scalar_sha=?",hash("drift"));
            case "old-container"->f.db.update("UPDATE conp_prompt SET container_until=?",ts(prepared.before().containerUntil().minusMillis(1)));
            case "target-revision"->f.db.update("INSERT INTO conp_revision SELECT ?,prompt_id,?,revision_json,content_sha,record_sha,submitted_at,body_until,audit_until,body_state FROM conp_revision WHERE revision_number=1",r.revisionId(),r.generation());
            case "target-event"->f.db.update("INSERT INTO conp_event SELECT ?,prompt_id,revision_id,?,'SAVE',issuer,subject_id,submitted_at,request_sha,result_sha,audit_until FROM conp_event WHERE generation=1",r.id(),r.generation());
            case "target-receipt"->f.db.update("INSERT INTO conp_receipt SELECT ?,workspace_id,issuer,subject_id,'SAVE',?,request_sha,prompt_id,revision_id,?,content_sha,result_sha,submitted_at,audit_until FROM conp_receipt WHERE generation=1",r.id(),r.keySha(),r.generation());
            default->throw new AssertionError(fault);
        }
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);assertThat(f.bodyReads.get()).isZero();
    }

    @Test void saveControlRequiresExactOldRevisionAndProofCannotCompleteChangedControl()throws Exception{
        reserve();append();finish();startSave("save",300,900);reserve();append();
        f.mainTx.executeWithoutResult(status->{
            var proof=reconciliation.observe(workspace,request).exactAfter();
            f.independent.update("UPDATE idp_prompt_control SET head_revision_id=?",id());
            assertThatThrownBy(()->suspended.execute(outer->f.authorityTx.execute(inner->requests.complete(proof)))).isInstanceOf(IllegalStateException.class);
        });
        assertThat(count("idp_revision_witness")).isEqualTo(1);assertThat(f.bodyReads.get()).isZero();
    }

    @Test void onlyOneSaveCanReserveAnExactOldHead()throws Exception{
        reserve();append();finish();startSave("first-save",300,900);var first=request;
        startSave("second-save",300,900);var second=request;
        f.authorityTx.executeWithoutResult(status->requests.reserve(first));
        assertThatThrownBy(()->f.authorityTx.executeWithoutResult(status->requests.reserve(second))).isInstanceOf(IllegalStateException.class);
        assertThat(count("idp_prompt_request")).isEqualTo(2);assertThat(mainCount("conp_revision")).isEqualTo(1);
        assertThat(observe(second).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);
        request=first;assertThat(find()).isEqualTo(first);
    }

    @ParameterizedTest @ValueSource(strings={"foreign-revision","foreign-event","foreign-save-key"})
    void saveChecksTargetCoordinatesEvenWhenResidueBelongsToAnotherPrompt(String fault)throws Exception{
        reserve();append();finish();startSave("save",300,900);var target=request.receipt();var value=prepared.write().revision();
        String revisionId="foreign-revision".equals(fault)?target.revisionId():id();
        var ref=PromptRevision.referenceFor(id(),revisionId,1,value.kind(),value.name(),value.body(),value.scenarioIds(),value.intentIds(),value.audienceRef(),value.language(),value.displayOrder(),value.variables());
        var origin=value.provenance();var revision=new PromptRevision(ref,value.kind(),value.name(),value.body(),value.scenarioIds(),value.intentIds(),value.audienceRef(),value.language(),value.displayOrder(),value.variables(),
                new PromptRevision.Provenance(origin.generationSourceRef(),"member",f.now,"member",f.now,"CREATE_DRAFT",List.of(),origin.contentApprovalRef(),origin.retentionPolicyRef()));
        var write=new Write(workspace.id(),revision,0,Operation.CREATE,target.issuer(),target.subject(),hash("foreign"),hash("foreign-command"),f.now.plusSeconds(300),f.now.plusSeconds(900));
        var foreign=f.mainTx.execute(status->f.store.prepare(write,"foreign-event".equals(fault)?target.id():id()));
        f.mainTx.executeWithoutResult(status->f.store.append(foreign));
        if("foreign-save-key".equals(fault))f.db.update("UPDATE conp_receipt SET operation='SAVE',key_sha=? WHERE receipt_id=?",target.keySha(),foreign.receipt().id());
        assertThat(observe(request).state()).isEqualTo(ProductPromptCreateReconciliation.State.UNKNOWN);assertThat(f.bodyReads.get()).isZero();
    }

    @Test void savePreparedRejectsInventedPreviousHeadAndChangedOriginEvenWhenContentMatches()throws Exception{
        reserve();append();finish();startSave("save",120,600);var before=prepared.before();
        var fabricated=new Head(before.promptId(),before.workspaceId(),before.kind(),before.revisionId(),before.generation(),before.scalarSha(),before.containerUntil().plusSeconds(1),before.controlUntil());
        assertThatThrownBy(()->requests.requirePrepared(request,new PreparedWrite(prepared.write(),fabricated,prepared.after(),prepared.header(),prepared.receipt()))).isInstanceOf(IllegalStateException.class);
        var w=prepared.write();var v=w.revision();var p=v.provenance();
        var revision=new PromptRevision(v.reference(),v.kind(),v.name(),v.body(),v.scenarioIds(),v.intentIds(),v.audienceRef(),v.language(),v.displayOrder(),v.variables(),
                new PromptRevision.Provenance(p.generationSourceRef(),"forged-creator",p.createdAt(),p.modifiedBy(),p.modifiedAt(),p.changeDescription(),p.testRecordRefs(),p.contentApprovalRef(),p.retentionPolicyRef()));
        var changed=new Write(w.workspaceId(),revision,w.expectedGeneration(),w.operation(),w.issuer(),w.subject(),w.keySha(),w.requestSha(),w.bodyUntil(),w.auditUntil());
        assertThatThrownBy(()->requests.requirePrepared(request,new PreparedWrite(changed,before,prepared.after(),prepared.header(),prepared.receipt()))).isInstanceOf(IllegalStateException.class);
    }

    private void startSave(String key,int bodySeconds,int auditSeconds)throws Exception{
        if(!saveSchema){ProductHumanTaskFactsFixture.migrate(f.authoritySource,"identity-authority/V009__product_prompt_save_request.sql");saveSchema=true;}
        f.now=f.now.plusSeconds(1);var prior=prepared.write().revision();var origin=prior.provenance();
        var before=f.mainTx.execute(status->f.store.head(prior.reference().promptId(),false));
        var ref=PromptRevision.referenceFor(before.promptId(),id(),before.generation()+1,before.kind(),"Saved","Updated full body",Set.of("b","a"),Set.of(),"internal","zh",0,List.of());
        var revision=new PromptRevision(ref,before.kind(),"Saved","Updated full body",Set.of("b","a"),Set.of(),"internal","zh",0,List.of(),
                new PromptRevision.Provenance(origin.generationSourceRef(),origin.createdBy(),origin.createdAt(),"member",f.now,"SAVE_DRAFT",List.of(),origin.contentApprovalRef(),origin.retentionPolicyRef()));
        var write=new Write(workspace.id(),revision,before.generation(),Operation.SAVE,request.receipt().issuer(),"member",hash(key),hash("save-command-"+key+"-"+before.generation()),f.now.plusSeconds(bodySeconds),f.now.plusSeconds(auditSeconds));
        prepared=f.mainTx.execute(status->f.store.prepare(write));var r=prepared.receipt();var after=prepared.after();
        var pending=new Pending(before.promptId(),workspace.id(),before.kind(),before.generation(),before.revisionId(),before.scalarSha(),after.scalarSha(),after.revisionId(),id(),Operation.SAVE,r.keySha(),r.requestSha(),f.now.plusSeconds(60),after.containerUntil(),after.controlUntil());
        request=requests.prepared(pending,prepared,request.evidence());
    }

    private ProductPromptCreateReconciliation.Observation observe(ProductPromptCreateRequest value){return f.mainTx.execute(status->reconciliation.observe(workspace,value));}
    private ProductPromptCreateRequest find(){return f.authorityTx.execute(status->requests.find(request.key()));}
    private void reserve(){f.authorityTx.executeWithoutResult(status->requests.reserve(request));}
    private void append(){f.mainTx.executeWithoutResult(status->f.store.append(prepared));}
    private Receipt finish(){
        return f.mainTx.execute(status->{
            f.store.workspace(workspace.id(),true);
            ProductPromptCreateRequest current=suspended.execute(outer->f.authorityTx.execute(inner->requests.find(request.key())));
            var proof=reconciliation.observe(workspace,current).exactAfter();
            return suspended.execute(outer->f.authorityTx.execute(inner->requests.complete(proof)));
        });
    }
    private void insertWitness(){
        var h=request.header();var r=request.receipt();
        f.independent.update("INSERT INTO idp_revision_witness(revision_id,prompt_id,revision_number,record_sha,content_sha,submitted_at,body_until,audit_until,"
                        +"body_state,result_sha,key_sha,request_sha,issuer,subject_id) VALUES(?,?,1,?,?,?,?,?,'PRESENT',?,?,?,?,?)",
                h.revisionId(),h.promptId(),h.recordSha(),h.contentSha(),ts(h.submittedAt()),ts(h.bodyUntil()),ts(h.auditUntil()),
                r.resultSha(),r.keySha(),r.requestSha(),r.issuer(),r.subject());
    }
    private int count(String table){return f.independent.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private int mainCount(String table){return f.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private static String id(){return UUID.randomUUID().toString();}
    private static String hash(String value){return ProductComparisonJson.hash(value);}
}
