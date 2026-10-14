package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static org.assertj.core.api.Assertions.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.UnexpectedRollbackException;

class ProductPromptCreateRequestStoreTest {
    private ProductPromptCreateRequestFixture f;
    @BeforeEach void setup()throws Exception{f=new ProductPromptCreateRequestFixture();}

    @Test void independentCommitSurvivesMainRollbackAndRebuiltStoreFindsEveryOriginalScalar(){
        Write write=f.write("human",hash("key"),"Draft body");
        ProductPromptCreateRequest[] original=new ProductPromptCreateRequest[1];
        assertThatThrownBy(()->f.mainTx.executeWithoutResult(status->{
            var prepared=f.store.prepare(write);original[0]=f.request(prepared);
            f.authorityTx.executeWithoutResult(other->f.requests.reserve(original[0]));
            f.store.append(prepared);throw new IllegalStateException("main commit not reached");
        })).hasMessage("main commit not reached");
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt"))assertThat(f.mainCount(table)).isZero();
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);assertThat(f.count("idp_prompt_control")).isEqualTo(1);
        var reopened=f.reopen();
        ProductPromptCreateRequest found=f.authorityTx.execute(status->reopened.find(original[0].key()));
        assertThat(found).isEqualTo(original[0]);assertThat(found.state()).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        assertThat(f.count("idp_revision_witness")).isZero();
        f.now=f.now.plusSeconds(60);
        f.mainTx.executeWithoutResult(status->{
            var restartedMain=new ProductPromptStore(f.main,f.mainManager,f.mapper,f.clock);
            PreparedWrite rebuilt=restartedMain.prepare(write,found.receipt().id());
            reopened.requirePrepared(found,rebuilt);
            assertThat(rebuilt.after().scalarSha()).isEqualTo(found.pending().afterSha());
            assertThat(rebuilt.receipt().submittedAt()).isEqualTo(write.revision().provenance().modifiedAt());
            assertThat(rebuilt.header().bodyUntil()).isEqualTo(write.bodyUntil());
        });
        assertThat(f.mainCount("conp_prompt")).isZero();
    }

    @Test void exactReserveReplayHasNoSecondControlOrRequest(){
        var request=f.prepared("human",hash("same"),"Original");f.reserve(request);
        f.now=f.now.plusSeconds(1);
        ProductPromptCreateRequest replay=f.authorityTx.execute(status->f.requests.reserve(request));
        assertThat(replay).isEqualTo(request);
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);assertThat(f.count("idp_prompt_control")).isEqualTo(1);
    }

    @Test void sameKeyDifferentCommandFailsWithoutReservingAnotherPrompt(){
        String key=hash("same");var first=f.prepared("human",key,"Original");f.reserve(first);
        var different=f.prepared("human",key,"Different");
        assertThatThrownBy(()->f.reserve(different)).isInstanceOf(IllegalStateException.class).hasMessageContaining("conflict");
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);assertThat(f.count("idp_prompt_control")).isEqualTo(1);
        assertThat(f.find(first.key())).isEqualTo(first);
    }

    @Test void sameCommandCannotReplaceOriginalPreparedIds(){
        String key=hash("same");var first=f.prepared("human",key,"Original");f.reserve(first);
        var differentIds=f.prepared("human",key,"Original");
        assertThat(differentIds.receipt().requestSha()).isEqualTo(first.receipt().requestSha());
        assertThatThrownBy(()->f.reserve(differentIds)).isInstanceOf(IllegalStateException.class);
        assertThat(f.find(first.key())).isEqualTo(first);assertThat(f.count("idp_prompt_control")).isEqualTo(1);
    }

    @Test void originalKeyIsScopedToExactSubjectAndIssuer(){
        String key=hash("same");var first=f.prepared("human",key,"One");f.reserve(first);
        assertThat(f.find(new ProductPromptCreateRequest.Key(f.workspace.id(),"issuer","other",key))).isNull();
        assertThat(f.find(new ProductPromptCreateRequest.Key(f.workspace.id(),"other-issuer","human",key))).isNull();
        var second=f.prepared("other",key,"Two");f.reserve(second);
        assertThat(f.find(first.key())).isEqualTo(first);assertThat(f.find(second.key())).isEqualTo(second);
        assertThat(f.count("idp_prompt_request")).isEqualTo(2);
    }

    @Test void requestInsertFailureRollsBackControlEvenWhenCallerCatches(){
        var request=f.prepared("human",hash("key"),"Original");f.failRequestInsert=true;
        assertThatThrownBy(()->f.authorityTx.executeWithoutResult(status->{
            assertThatThrownBy(()->f.requests.reserve(request)).isInstanceOf(DataAccessResourceFailureException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(f.count("idp_prompt_request")).isZero();assertThat(f.count("idp_prompt_control")).isZero();
    }

    @Test void existingControlConflictNeverLeavesRequestHalf(){
        var request=f.prepared("human",hash("key"),"Original");
        f.authorityTx.executeWithoutResult(status->f.controls.reserve(request.pending()));
        assertThatThrownBy(()->f.authorityTx.executeWithoutResult(status->{
            assertThatThrownBy(()->f.requests.reserve(request)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(f.count("idp_prompt_request")).isZero();assertThat(f.count("idp_prompt_control")).isEqualTo(1);
    }

    @Test void auditExpiryIsCheckedBeforeSelectingSubjectAndPreparedScalars(){
        var request=f.prepared("human",hash("key"),"Original");f.reserve(request);
        f.now=request.header().auditUntil();f.sensitiveReads.set(0);
        assertThatThrownBy(()->f.find(request.key())).isInstanceOf(IllegalStateException.class);
        assertThat(f.sensitiveReads.get()).isZero();
        assertThatThrownBy(()->f.reserve(request)).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);
    }

    @Test void intentExpiryDoesNotEraseAuditMaterialOrAllowReserveAgain(){
        var request=f.prepared("human",hash("key"),"Original");f.reserve(request);
        f.now=request.pending().intentUntil();
        assertThat(f.find(request.key())).isEqualTo(request);
        assertThatThrownBy(()->f.reserve(request)).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"prepared_sha","expected_after_sha","result_sha","record_sha","input_permission_sha"})
    void scalarTamperCannotBeReadAsOriginalPrepared(String column){
        var request=f.prepared("human",hash("key"),"Original");f.reserve(request);
        f.authority.update("UPDATE idp_prompt_request SET "+column+"=?",hash("tamper"));
        assertThatThrownBy(()->f.find(request.key())).isInstanceOf(IllegalStateException.class);
    }

    @Test void forgedFormatOrDigestAndNonCreateNeverReserve(){
        var original=f.prepared("human",hash("key"),"Original");
        assertThatThrownBy(()->new ProductPromptCreateRequest(original.pending(),original.receipt(),original.header(),original.evidence(),
                "PROMPT_PREPARED_SCALARS/v2",original.requestFormat(),original.state(),original.preparedSha())).isInstanceOf(IllegalArgumentException.class);
        var forged=new ProductPromptCreateRequest(original.pending(),original.receipt(),original.header(),original.evidence(),
                original.preparedFormat(),original.requestFormat(),original.state(),hash("forged"));
        assertThatThrownBy(()->f.reserve(forged)).isInstanceOf(IllegalStateException.class);
        var p=original.pending();var copy=new Pending(p.promptId(),p.workspaceId(),p.kind(),0,null,p.beforeSha(),p.afterSha(),p.targetRevisionId(),p.intentId(),
                Operation.COPY,p.keySha(),p.requestSha(),p.intentUntil(),p.containerUntil(),p.controlUntil());
        assertThatThrownBy(()->new ProductPromptCreateRequest(copy,original.receipt(),original.header(),original.evidence(),
                original.preparedFormat(),original.requestFormat(),original.state(),original.preparedSha())).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.count("idp_prompt_request")).isZero();assertThat(f.count("idp_prompt_control")).isZero();
    }

    @Test void noTransactionOrWrongDatasourceCannotReserveOrRead(){
        var request=f.prepared("human",hash("key"),"Original");
        assertThatThrownBy(()->f.requests.reserve(request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.requests.find(request.key())).isInstanceOf(IllegalStateException.class);
        f.mainTx.executeWithoutResult(status->{
            assertThatThrownBy(()->f.requests.reserve(request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->f.requests.find(request.key())).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(()->new ProductPromptAuthorityStore(f.authority,f.mainManager,f.clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.count("idp_prompt_request")).isZero();
    }

    @Test void fixedReceiptPreparationPreservesValidationAndRejectsChangedMaterial(){
        Write write=f.write("human",hash("key"),"Original");String receiptId=id();
        PreparedWrite prepared=f.mainTx.execute(status->f.store.prepare(write,receiptId));
        var request=f.request(prepared);
        f.mainTx.executeWithoutResult(status->{
            assertThat(f.store.prepare(write,receiptId)).isEqualTo(prepared);
            assertThatThrownBy(()->f.store.prepare(write,"not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->f.requests.requirePrepared(request,f.store.prepare(write,id()))).isInstanceOf(IllegalStateException.class);
            assertThat(f.store.prepare(write).receipt().id()).isNotEqualTo(receiptId);
        });
        f.now=write.bodyUntil();
        assertThatThrownBy(()->f.mainTx.execute(status->f.store.prepare(write,receiptId))).isInstanceOf(IllegalStateException.class);
        assertThat(f.mainCount("conp_prompt")).isZero();
    }

    @Test void changedWorkspaceBindingCannotBeReserved(){
        var request=f.prepared("human",hash("key"),"Original");
        f.authority.update("UPDATE idp_workspace SET policy_sha=?",hash("different"));
        assertThatThrownBy(()->f.reserve(request)).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("idp_prompt_request")).isZero();assertThat(f.count("idp_prompt_control")).isZero();
    }

    @Test void limitedOriginalAuditPeriodCannotBeExpanded(){
        var original=f.prepared("human",hash("key"),"Original");var p=original.pending();var r=original.receipt();var h=original.header();
        Instant extended=h.submittedAt().plus(Duration.ofDays(181));
        var extendedPending=new Pending(p.promptId(),p.workspaceId(),p.kind(),0,null,p.beforeSha(),p.afterSha(),p.targetRevisionId(),p.intentId(),
                Operation.CREATE,p.keySha(),p.requestSha(),p.intentUntil(),p.containerUntil(),extended);
        var extendedReceipt=new Receipt(r.id(),r.workspaceId(),r.issuer(),r.subject(),r.operation(),r.keySha(),r.requestSha(),r.promptId(),r.revisionId(),
                r.generation(),r.contentSha(),r.resultSha(),r.submittedAt(),extended);
        var extendedHeader=new Header(h.revisionId(),h.promptId(),h.number(),h.contentSha(),h.recordSha(),h.submittedAt(),h.bodyUntil(),extended,h.bodyState());
        assertThatThrownBy(()->new ProductPromptCreateRequest(extendedPending,extendedReceipt,extendedHeader,original.evidence(),
                original.preparedFormat(),original.requestFormat(),original.state(),original.preparedSha())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void changedIndependentControlOrUnsupportedReadyCannotPretendOriginalPending(){
        var request=f.prepared("human",hash("key"),"Original");f.reserve(request);
        f.authority.update("UPDATE idp_prompt_control SET expected_after_sha=?",hash("drift"));
        assertThatThrownBy(()->f.find(request.key())).isInstanceOf(IllegalStateException.class);
        f.authority.update("UPDATE idp_prompt_control SET expected_after_sha=?",request.pending().afterSha());
        f.authority.update("UPDATE idp_prompt_request SET state='READY'");
        assertThatThrownBy(()->f.find(request.key())).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"scaled-decimal","precise-decimal","large-integer"})
    void fixedPreparedRebuildPreservesExactNumericDefault(String numeric){
        Object value=switch(numeric){
            case "scaled-decimal"->new java.math.BigDecimal("1.00");
            case "precise-decimal"->new java.math.BigDecimal("123456789012345678901234567890.12345678901234567890");
            default->new java.math.BigInteger("1234567890123456789012345678901234567890");
        };
        var type=value instanceof java.math.BigInteger?VariableDefinition.Type.INTEGER:VariableDefinition.Type.DECIMAL;
        var variables=List.of(new VariableDefinition("amount",type,false,"source:original-value",true,value,false));
        Write base=f.write("human",hash("numeric-key"),"Amount {{amount}}");var v=base.revision();var ref=v.reference();
        var exact=PromptRevision.referenceFor(ref.promptId(),ref.revisionId(),ref.version(),v.kind(),v.name(),v.body(),v.scenarioIds(),v.intentIds(),
                v.audienceRef(),v.language(),v.displayOrder(),variables);
        var revision=new PromptRevision(exact,v.kind(),v.name(),v.body(),v.scenarioIds(),v.intentIds(),v.audienceRef(),v.language(),v.displayOrder(),variables,v.provenance());
        var write=new Write(base.workspaceId(),revision,0,Operation.CREATE,base.issuer(),base.subject(),base.keySha(),base.requestSha(),base.bodyUntil(),base.auditUntil());
        var prepared=f.mainTx.execute(status->f.store.prepare(write));var request=f.request(prepared);f.reserve(request);
        var reopened=f.reopen();var found=f.authorityTx.execute(status->reopened.find(request.key()));
        f.mainTx.executeWithoutResult(status->{
            var rebuilt=f.store.prepare(write,found.receipt().id());reopened.requirePrepared(found,rebuilt);
            assertThat(rebuilt).isEqualTo(prepared);
            assertThat(rebuilt.write().revision().variables().getFirst().defaultValue()).isEqualTo(value);
        });
    }

    @Test void generationSourceReferenceKeepsExistingDomainTextContract(){
        var origin=new ProductPromptCreateRequest.Evidence(1,f.workspace.registrySha(),"approval-v1",f.workspace.registrySha(),
                f.workspace.policyRef(),f.workspace.policySha(),"input-permission-v1",hash("input"),"source:项目原稿/版本 1");
        var base=f.write("human",hash("origin-key"),"Original");var v=base.revision();var p=v.provenance();
        var provenance=new PromptRevision.Provenance(origin.generationSourceRef(),p.createdBy(),p.createdAt(),p.modifiedBy(),p.modifiedAt(),
                p.changeDescription(),p.testRecordRefs(),p.contentApprovalRef(),p.retentionPolicyRef());
        var revision=new PromptRevision(v.reference(),v.kind(),v.name(),v.body(),v.scenarioIds(),v.intentIds(),v.audienceRef(),v.language(),
                v.displayOrder(),v.variables(),provenance);
        var write=new Write(base.workspaceId(),revision,0,Operation.CREATE,base.issuer(),base.subject(),base.keySha(),base.requestSha(),base.bodyUntil(),base.auditUntil());
        var prepared=f.mainTx.execute(status->f.store.prepare(write));var material=f.requestWithEvidence(prepared,origin);f.reserve(material);
        assertThat(f.find(material.key()).evidence().generationSourceRef()).isEqualTo("source:项目原稿/版本 1");
    }

    @Test void saveConstraintUpgradePreservesExistingCreateBytesAndAcceptsSaveWithoutNewColumns()throws Exception{
        var create=f.prepared("human",hash("same"),"Original");f.reserve(create);
        var before=f.authority.queryForMap("SELECT * FROM idp_prompt_request WHERE request_id=?",create.pending().intentId());
        ProductPromptCreateRequestFixture.migrate(f.authoritySource,"db/identity-authority/V009__product_prompt_save_request.sql");
        assertThat(f.authority.queryForMap("SELECT * FROM idp_prompt_request WHERE request_id=?",create.pending().intentId())).isEqualTo(before);
        assertThat(f.find(create.key())).isEqualTo(create);
        var save=saveColumns(create);insertRequestColumns(save);
        assertThat(f.count("idp_prompt_request")).isEqualTo(2);
        assertThat(f.authority.queryForObject("SELECT operation FROM idp_prompt_request WHERE request_id=?",String.class,save.get("request_id"))).isEqualTo("SAVE");
        assertThat(f.authority.queryForMap("SELECT * FROM idp_prompt_request WHERE request_id=?",create.pending().intentId())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"format","command-format","null-base","zero-base","max-base","wrong-target","short-container","short-control","container-after-control"})
    void saveMigrationRejectsMalformedPersistentRows(String fault)throws Exception{
        var create=f.prepared("human",hash("same"),"Original");f.reserve(create);
        ProductPromptCreateRequestFixture.migrate(f.authoritySource,"db/identity-authority/V009__product_prompt_save_request.sql");
        var save=saveColumns(create);
        switch(fault){
            case "format"->save.put("prepared_format",ProductPromptCreateRequest.PREPARED_FORMAT);
            case "command-format"->save.put("request_format",ProductPromptCreateRequest.REQUEST_FORMAT);
            case "null-base"->save.put("expected_revision_id",null);
            case "zero-base"->{save.put("expected_generation",0L);save.put("target_generation",1L);}
            case "max-base"->{save.put("expected_generation",Long.MAX_VALUE);save.put("target_generation",Long.MIN_VALUE);}
            case "wrong-target"->save.put("target_generation",3L);
            case "short-container"->save.put("container_until",create.header().bodyUntil().minusMillis(1));
            case "short-control"->save.put("control_until",create.header().auditUntil().minusMillis(1));
            case "container-after-control"->save.put("container_until",create.header().auditUntil().plusMillis(1));
            default->throw new AssertionError(fault);
        }
        assertThatThrownBy(()->insertRequestColumns(save)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.count("idp_prompt_request")).isEqualTo(1);assertThat(f.find(create.key())).isEqualTo(create);
    }

    private Map<String,Object> saveColumns(ProductPromptCreateRequest create){
        var row=create.columns();row.put("request_id",id());row.put("receipt_id",id());row.put("target_revision_id",id());
        row.put("operation","SAVE");row.put("expected_generation",1L);row.put("expected_revision_id",create.header().revisionId());row.put("target_generation",2L);
        row.put("prepared_format",ProductPromptCreateRequest.SAVE_PREPARED_FORMAT);row.put("request_format",ProductPromptCreateRequest.SAVE_REQUEST_FORMAT);return row;
    }
    private void insertRequestColumns(Map<String,Object> columns){
        f.authority.update("INSERT INTO idp_prompt_request("+String.join(",",columns.keySet())+") VALUES("
                        +String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                columns.values().stream().map(value->value instanceof Instant at?ProductPromptJdbc.ts(at):value).toArray());
    }

    @Test void eightIndependentJvmsProduceIdenticalContentRecordResultClosureAndPreparedDigests()throws Exception{
        var results=new ArrayList<String>();var processes=new HashSet<Long>();
        for(int run=0;run<8;run++){
            var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",
                    System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx128m","-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    ProductPromptCreateRequestStoreTest.class.getName(),"canonical-probe").redirectErrorStream(true).start();
            processes.add(child.pid());
            try{
                assertThat(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).as("independent JVM completed").isTrue();
                String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(child.exitValue()).as(output).isZero();
                var lines=output.lines().filter(line->line.startsWith("PROMPT_CANONICAL_PROBE ")).toList();
                assertThat(lines).hasSize(1);String result=lines.getFirst().substring("PROMPT_CANONICAL_PROBE ".length());
                assertThat(result).matches("[a-f0-9]{64}( [a-f0-9]{64}){4}");results.add(result);
                System.out.println("PROMPT_CANONICAL_JVM "+run+" "+result);
            }finally{
                if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
            }
        }
        assertThat(processes).hasSize(8);assertThat(new HashSet<>(results)).hasSize(1);
    }

    @Test void legacyPendingPermutationMismatchStaysClosedWithoutRewritingAnyOriginalScalar(){
        Write write=canonicalWrite(f);PreparedWrite prepared=f.mainTx.execute(status->f.store.prepare(write,"40000000-0000-0000-0000-000000000004"));
        ProductComparisonJson json=exactJson(f);
        var oldTree=json.stored(json.canonical(write.revision()),com.fasterxml.jackson.databind.node.ObjectNode.class);
        oldTree.putArray("scenarioIds").add("zeta").add("middle").add("alpha");
        oldTree.putArray("intentIds").add("search").add("query").add("answer");
        String oldRaw=json.canonical(oldTree),oldRecordSha=json.sha(oldTree);
        assertThat(oldRecordSha).isNotEqualTo(prepared.header().recordSha());
        String oldAfter=f.mainTx.execute(status->{
            f.store.append(prepared);
            f.main.update("UPDATE conp_revision SET revision_json=?,record_sha=? WHERE revision_id=?",oldRaw,oldRecordSha,prepared.header().revisionId());
            String closure=f.store.closureSha(prepared.after().promptId());status.setRollbackOnly();return closure;
        });
        var h=prepared.header();var r=prepared.receipt();
        var header=new Header(h.revisionId(),h.promptId(),h.number(),h.contentSha(),oldRecordSha,h.submittedAt(),h.bodyUntil(),h.auditUntil(),h.bodyState());
        var pending=new Pending(r.promptId(),r.workspaceId(),write.revision().kind(),0,null,"0".repeat(64),oldAfter,r.revisionId(),id(),Operation.CREATE,
                r.keySha(),r.requestSha(),f.now.plusSeconds(600),h.bodyUntil(),h.auditUntil());
        var provisional=new ProductPromptCreateRequest(pending,r,header,f.evidence,ProductPromptCreateRequest.PREPARED_FORMAT,
                ProductPromptCreateRequest.REQUEST_FORMAT,"PENDING","0".repeat(64));
        var columns=provisional.columns();columns.remove("state");columns.remove("prepared_sha");columns.put("before",null);
        columns.put("after",provisional.after());columns.put("header",header);columns.put("receipt",r);
        var legacy=new ProductPromptCreateRequest(pending,r,header,f.evidence,provisional.preparedFormat(),provisional.requestFormat(),"PENDING",json.sha(columns));
        f.reserve(legacy);var recovered=f.find(legacy.key());assertThat(recovered).isEqualTo(legacy);
        assertThatThrownBy(()->f.mainTx.executeWithoutResult(status->f.requests.requirePrepared(recovered,f.store.prepare(write,r.id()))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.find(legacy.key())).isEqualTo(legacy);
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt"))assertThat(f.mainCount(table)).isZero();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
    }

    public static void main(String[] args)throws Exception{
        if(args.length!=1||!"canonical-probe".equals(args[0]))throw new IllegalArgumentException("test probe argument");
        var fixture=new ProductPromptCreateRequestFixture("10000000-0000-0000-0000-000000000001");var write=canonicalWrite(fixture);
        var prepared=fixture.mainTx.execute(status->fixture.store.prepare(write,"40000000-0000-0000-0000-000000000004"));
        var r=prepared.receipt();var h=prepared.after();
        var pending=new Pending(r.promptId(),r.workspaceId(),write.revision().kind(),0,null,"0".repeat(64),h.scalarSha(),r.revisionId(),
                "50000000-0000-0000-0000-000000000005",Operation.CREATE,r.keySha(),r.requestSha(),fixture.now.plusSeconds(600),h.containerUntil(),h.controlUntil());
        var request=fixture.requests.prepared(pending,prepared,fixture.evidence);fixture.reserve(request);
        var reopened=fixture.reopen();var found=fixture.authorityTx.execute(status->reopened.find(request.key()));
        fixture.mainTx.executeWithoutResult(status->{
            var restartedMain=new ProductPromptStore(fixture.main,fixture.mainManager,fixture.mapper,fixture.clock);
            reopened.requirePrepared(found,restartedMain.prepare(write,found.receipt().id()));
        });
        System.out.println("PROMPT_CANONICAL_PROBE "+r.contentSha()+" "+prepared.header().recordSha()+" "+r.resultSha()+" "+h.scalarSha()+" "+request.preparedSha());
    }

    private static Write canonicalWrite(ProductPromptCreateRequestFixture fixture){
        var scenarios=Set.of("zeta","middle","alpha");var intents=Set.of("search","query","answer");
        var variables=List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"declared-source",true,new java.math.BigDecimal("1.00"),false));
        var reference=PromptRevision.referenceFor("20000000-0000-0000-0000-000000000002","30000000-0000-0000-0000-000000000003",1,
                PromptRevision.Kind.SYSTEM,"Draft","Ratio {{ratio}}",scenarios,intents,"internal","zh",0,variables);
        var origin=new PromptRevision.Provenance(fixture.evidence.generationSourceRef(),"human",fixture.now,"human",fixture.now,"CREATE_DRAFT",List.of(),
                fixture.evidence.inputPermissionRef(),fixture.workspace.policyRef());
        var revision=new PromptRevision(reference,PromptRevision.Kind.SYSTEM,"Draft","Ratio {{ratio}}",scenarios,intents,"internal","zh",0,variables,origin);
        return new Write(fixture.workspace.id(),revision,0,Operation.CREATE,"issuer","human",hash("fixed-key"),hash("fixed-command"),
                fixture.now.plus(Duration.ofDays(90)),fixture.now.plus(Duration.ofDays(180)));
    }
    private static ProductComparisonJson exactJson(ProductPromptCreateRequestFixture fixture){return new ProductComparisonJson(fixture.mapper.copy()
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));}

    private static String id(){return UUID.randomUUID().toString();}
    private static String hash(String value){return ProductComparisonJson.hash(value);}

}
