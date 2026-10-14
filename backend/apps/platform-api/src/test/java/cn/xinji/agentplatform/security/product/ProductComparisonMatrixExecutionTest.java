package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import cn.xinji.agentplatform.runtime.ModelGateway;

/** Current source fixture and controlled model port: engineering contract, not professional Golden or formal usage. */
class ProductComparisonMatrixExecutionTest {
    ProductComparisonCandidateExecutionTest f;ProductComparisonMatrixExecution execution;
    ProductComparisonMatrixStore store;ProductComparisonModelAdmission admission;ProductComparisonMatrixCalculation calculation;
    ProductComparisonModelAdmission.Permit permit;ModelGateway gateway;AtomicInteger calls;
    @BeforeEach void setup() throws Exception {
        setup(true);
    }
    private void setup(boolean withLineage) throws Exception {
        f=new ProductComparisonCandidateExecutionTest();f.setup();
        ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/V100__product_comparison_matrix.sql");
        if(withLineage) ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/V108__product_comparison_lineage.sql");
        var input=f.source.fixture.input;
        store=new ProductComparisonMatrixStore(f.source.fixture.db,input.json,f.transactions,input.clock);
        calculation=new ProductComparisonMatrixCalculation(input.json);admission=mock(ProductComparisonModelAdmission.class);
        var config=new ModelGateway.Configuration("matrix-model","approved-model-v1","managed-provider","model-name",URI.create("https://model.example.invalid/v1/chat/completions"),
                "OPENAI_CHAT_COMPLETIONS",Duration.ofSeconds(20),1048576,1048576,Set.of("PATENT_COMPARISON"),Set.of("APPROVED_COMPARISON_ORIGINALS"));
        var approval=new ProductComparisonModelAdmission.Approval("approval-v1","engineering-fixture-not-formal-owner",input.clock.instant(),input.clock.instant(),
                f.source.intent.expiresAt(),f.scope.task(),f.scope.inputSha(),Set.of(f.scope.candidate()),3,config);
        permit=new ProductComparisonModelAdmission.Permit(approval,"c".repeat(64));when(admission.require(any())).thenReturn(permit);
        calls=new AtomicInteger();gateway=call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(f.count("con5_matrix_invocation")).isGreaterThan(0);calls.incrementAndGet();
            return new ModelGateway.Result(config.version(),200,response(),new ModelGateway.Usage(null,null),1);};
        rebuild();
    }
    void rebuild(){execution=new ProductComparisonMatrixExecution(f.execution,f.source.materials,admission,calculation,store,gateway,f.source.fixture.input.clock);}
    String response() {
        var loaded=f.source.materials.consume(f.source.request,f.scope.task(),f.scope.comparison(),f.scope.candidate(),f.scope.inputSha(),f.scope.delegation());var snapshot=calculation.snapshot(loaded);var original=loaded.candidate().original();
        
        var rows=snapshot.candidates().getFirst().branches().stream().flatMap(b->snapshot.confirmedFeatures().stream().map(feature->
                new ProductComparisonMatrixCalculation.Suggestion(b.inheritedClaims().stream().map(c->c.number()).toList(),feature.id(),
                        cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Relation.PARTIAL,
                        List.of("Engineering suggestion requires professional review"),List.of("No professional equivalence established"),
                        "Quoted exact candidate scope; mechanical association fixture only","Professional meaning not accepted",b.inheritedClaims().stream().map(c->{int start=original.text().indexOf(c.original().text());return new ProductComparisonMatrixCalculation.Range(start,start+c.original().text().length());}).toList(),
                        List.of(new ProductComparisonMatrixCalculation.Range(original.text().indexOf(b.inheritedClaims().getFirst().original().text()),original.text().indexOf(b.inheritedClaims().getFirst().original().text())+b.inheritedClaims().getFirst().original().text().length()))))).toList();
        var result=new ProductComparisonMatrixCalculation.Suggestions(f.scope.inputSha(),f.scope.candidate(),rows);
        return f.source.fixture.input.json.canonical(Map.of("choices",List.of(Map.of("message",Map.of("content",f.source.fixture.input.json.canonical(result))))));
    }
    @Test void claimPreflightChecksIndependentModelInputAndWindowWithoutCallingModelOrCreatingLease() {
        execution.authorizeClaim(f.source.request,f.scope,Duration.ofSeconds(60));
        assertThat(calls.get()).isZero();assertThat(f.count("con5_candidate_attempt")).isZero();
        assertThatThrownBy(()->execution.authorizeClaim(f.source.request,f.scope,Duration.ofSeconds(20))).hasMessageContaining("WINDOW_INSUFFICIENT");
        assertThat(f.count("con5_candidate_attempt")).isZero();
        when(admission.require(any())).thenThrow(new IllegalStateException("MODEL_INPUT withdrawn"));
        assertThatThrownBy(()->execution.authorizeClaim(f.source.request,f.scope,Duration.ofSeconds(60))).hasMessageContaining("withdrawn");
        assertThat(calls.get()).isZero();assertThat(f.count("con5_candidate_attempt")).isZero();
    }    @Test void commitsNonemptyRowsCitationsReceiptAndFenceThenReplaysWithoutAnotherInvocation() {
        var lease=f.acquire("matrix-positive");var receipt=execution.execute(f.source.request,f.scope,lease);
        assertThat(receipt.status()).isEqualTo("COMMITTED");assertThat(f.status()).isEqualTo("COMMITTED");
        var result=store.read(f.scope.task(),f.scope.comparison(),receipt.reportId());
        assertThat(result.result().rows()).isNotEmpty();assertThat(result.result().rows().getFirst().citations()).isNotEmpty();
        assertThat(f.count("con5_matrix_row")).isEqualTo(result.result().rows().size());assertThat(f.count("con5_matrix_citation")).isGreaterThan(0);
        assertThat(execution.execute(f.source.request,f.scope,lease)).isEqualTo(receipt);assertThat(calls).hasValue(1);
    }
    @Test void unknownGatewayOutcomeNeverReissuesOrCreatesAnotherReport() {
        gateway=call->{calls.incrementAndGet();throw new ModelGateway.Failure("TIMEOUT");};rebuild();var lease=f.acquire("matrix-timeout");
        assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessage("TIMEOUT");
        assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessage("MODEL_INVOCATION_UNRESOLVED");
        assertThat(calls).hasValue(1);assertThat(f.count("con5_matrix_report")).isZero();assertThat(f.status()).isEqualTo("RUNNING");
    }
    @Test void missingTableRollsBackEveryResultWriteButPreservesReceivedResponseForExactReconciliation() {
        f.source.fixture.db.execute("DROP TABLE con5_matrix_citation");var lease=f.acquire("matrix-table");
        assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(f.count("con5_matrix_report")).isZero();assertThat(f.count("con5_matrix_row")).isZero();assertThat(f.status()).isEqualTo("RUNNING");
        assertThat(f.source.fixture.db.queryForObject("SELECT status FROM con5_matrix_invocation",String.class)).isEqualTo("RECEIVED");
    }
    @Test void lateAdmissionLossRollsBackReportRowsReceiptAndCommittedFence() {
        when(admission.require(any())).thenAnswer(a->{if(f.count("con5_matrix_report")>0)throw new IllegalStateException("Late MODEL_INPUT revoked");return permit;});
        var lease=f.acquire("matrix-late");assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessage("Late MODEL_INPUT revoked");
        for(String table:List.of("con5_matrix_report","con5_matrix_row","con5_matrix_citation","con5_matrix_receipt"))assertThat(f.count(table)).isZero();
        assertThat(f.status()).isEqualTo("RUNNING");assertThat(f.source.fixture.db.queryForObject("SELECT status FROM con5_candidate_attempt",String.class)).isEqualTo("ACTIVE");
    }
    @Test void invalidModelBindingIsKnownFailureNotAnEmptyCommittedResult() {
        gateway=call->{calls.incrementAndGet();return new ModelGateway.Result("approved-model-v1",200,response().replace(f.scope.inputSha(),"d".repeat(64)),null,1);};rebuild();
        var lease=f.acquire("matrix-invalid");assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessageContaining("MATRIX_MODEL_BINDING_INVALID");
        assertThat(f.count("con5_matrix_report")).isZero();assertThat(f.status()).isEqualTo("FAILED");
        assertThat(f.source.fixture.db.queryForObject("SELECT status FROM con5_matrix_invocation",String.class)).isEqualTo("REJECTED");
    }
    @Test void corruptedNativeCitationCannotBeReadAsVerifiedReport() {
        var receipt=execution.execute(f.source.request,f.scope,f.acquire("matrix-corrupt"));
        f.source.fixture.db.update("DELETE FROM con5_matrix_citation WHERE report_id=?",receipt.reportId());
        assertThatThrownBy(()->store.read(f.scope.task(),f.scope.comparison(),receipt.reportId())).hasMessageContaining("CLOSURE_INVALID");
    }
    @Test void approvalFailureBeforeDispatchHasNoInvocationOrResult() {
        when(admission.require(any())).thenThrow(new IllegalStateException("MODEL_INPUT missing"));var lease=f.acquire("matrix-no-model");
        assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessage("MODEL_INPUT missing");
        assertThat(calls).hasValue(0);assertThat(f.count("con5_matrix_invocation")).isZero();
    }
    @Test void committedFirstCandidateSurvivesSecondFailureAndOnlySecondIsRetried() throws Exception {
        var fixture=f.source.fixture;var input=fixture.input;var base=input.base;
        java.nio.file.Path root=java.nio.file.Path.of("").toAbsolutePath();
        while(root!=null && !java.nio.file.Files.isDirectory(root.resolve("data/patents")))root=root.getParent();
        if(root==null)throw new IllegalStateException("Captured records required");
        String ref="data/patents/patentsview-real-candidate-20261002-v3/records/3933241.json";
        byte[] raw=java.nio.file.Files.readAllBytes(root.resolve(ref));String sha=ProductCapturedArchive.digest(raw);
        var original=new ProductPatentClaimOriginal(ref,raw,sha,"RAW_SHA256:"+sha,"claimsText",input.mapper);
        var docRef=new ProductComparisonContracts.DocumentRef("source-1","3933241","RAW_SHA256:"+sha,List.of(1),null,null,null,null);
        var branches=cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph.parse(original).branches(1,4000).stream()
                .map(b->new ProductComparisonContracts.Branch(b.selectedClaim(),b.inheritedClaims().stream()
                        .map(c->new ProductComparisonContracts.Claim(c.number(),c.alternatives(),ProductComparisonContracts.Span.of(c.original()))).toList())).toList();
        var material=new ProductComparisonContracts.MaterialDocument(ProductComparisonJson.hash("source-1\n3933241\n"+docRef.documentVersion()),
                "source-1","3933241",docRef.documentVersion(),"capture-1",sha,ref,"/claimsText",branches,"");
        var second=new ProductComparisonDocuments.Loaded(docRef,original,material,input.candidate.authority());
        when(input.documents.read(eq(fixture.task),eq(docRef),any(),anyBoolean())).thenReturn(second);
        when(input.documents.readDelegated(eq(fixture.task),eq(docRef),any())).thenReturn(second);
        var old=base.command;base.command=new ProductComparisonContracts.Prepare(old.expectedTaskVersion(),old.goalId(),old.goalRevision(),
                old.inputId(),old.purpose(),old.target(),List.of(input.candidate.reference(),docRef));
        var prepared=base.prepare();var features=base.confirm(prepared,"paired-features",input.confirmed.revision().revisionNumber());
        var command=new ProductComparisonInputs.Create(old.expectedTaskVersion(),features.revision().revisionId(),features.revision().revisionNumber(),
                features.packageSha(),fixture.view.input().comparisonId(),"PATENT_FEATURE_MATRIX_V1");
        var paired=input.comparisons.create(fixture.task,command,"paired-input",fixture.human);
        var firstIntent=fixture.application.issue(fixture.task,paired.input().comparisonId(),new ProductComparisonWorkerDelegations.Issue(
                fixture.service,input.candidate.material().key(),paired.inputSha(),fixture.command.expiresAt()),fixture.human,"paired-first");
        var secondIntent=fixture.application.issue(fixture.task,paired.input().comparisonId(),new ProductComparisonWorkerDelegations.Issue(
                fixture.service,material.key(),paired.inputSha(),fixture.command.expiresAt()),fixture.human,"paired-second");
        var firstScope=new ProductComparisonCandidateExecution.Scope(fixture.task,paired.input().comparisonId(),input.candidate.material().key(),paired.inputSha(),firstIntent.delegationId());
        var secondScope=new ProductComparisonCandidateExecution.Scope(fixture.task,paired.input().comparisonId(),material.key(),paired.inputSha(),secondIntent.delegationId());

        fixture.view=paired;f.scope=firstScope;
        var previousApproval=permit.approval();permit=new ProductComparisonModelAdmission.Permit(new ProductComparisonModelAdmission.Approval(previousApproval.version(),previousApproval.responsibleParty(),
                previousApproval.approvedAt(),previousApproval.validFrom(),previousApproval.expiresAt(),fixture.task,paired.inputSha(),Set.of(firstScope.candidate(),secondScope.candidate()),3,previousApproval.configuration()),permit.evidenceSha());
        when(admission.require(any())).thenReturn(permit);
        var first=execution.execute(f.source.request,firstScope,f.acquire("matrix-paired-a"));
        gateway=call->{calls.incrementAndGet();return new ModelGateway.Result("approved-model-v1",200,response().replace(paired.inputSha(),"d".repeat(64)),null,1);};rebuild();
        f.scope=secondScope;var failed=f.acquire("matrix-paired-b");
        assertThatThrownBy(()->execution.execute(f.source.request,secondScope,failed)).hasMessageContaining("BINDING_INVALID");
        assertThat(store.read(firstScope.task(),firstScope.comparison(),first.reportId()).receipt()).isEqualTo(first);
        assertThat(input.inputStore.read(fixture.task,paired.input().comparisonId()).candidateProgress()).containsExactly(
                new ProductComparisonInputs.Progress(firstScope.candidate(),"COMMITTED"),new ProductComparisonInputs.Progress(secondScope.candidate(),"FAILED"));
        gateway=call->{calls.incrementAndGet();return new ModelGateway.Result("approved-model-v1",200,response(),null,1);};rebuild();
        var secondReceipt=execution.execute(f.source.request,secondScope,f.acquire("matrix-paired-b-retry"));
        assertThat(secondReceipt.candidateId()).isEqualTo(secondScope.candidate());assertThat(calls).hasValue(3);
        assertThat(store.read(firstScope.task(),firstScope.comparison(),first.reportId()).receipt()).isEqualTo(first);
        assertThat(f.count("con5_matrix_report")).isEqualTo(2);
    }
    ProductComparisonMatrixReader reader() {
        var fixture=f.source.fixture;var input=fixture.input;var directory=new JdbcProductTaskDirectory(fixture.db,input.clock);
        var sources=mock(ProductSourceAvailability.class);when(sources.require(anyString(),eq("CLAIMS"),eq("SAVE"))).thenReturn(input.candidate.authority().source());
        return new ProductComparisonMatrixReader(directory,input.access,input.inputStore,input.documents,
                new ProductComparisonTargetReader(fixture.db,directory,input.access,input.clock,65536),sources,store,calculation,input.json,fixture.db,input.clock);
    }
    @Test void humanApiReadsExactCommittedMatrixAndRejectsExpiredOrRevokedOriginal() throws Exception {
        var lease=f.acquire("matrix-human-read");var receipt=execution.execute(f.source.request,f.scope,lease);var reader=reader();
        var value=reader.report(f.scope.task(),f.scope.comparison(),receipt.reportId(),f.source.fixture.human);
        assertThat(value.receipt()).isEqualTo(receipt);assertThat(reader.results(f.scope.task(),f.scope.comparison(),f.source.fixture.human).state()).isEqualTo("COMMITTED");
        when(f.source.fixture.input.documents.read(eq(f.scope.task()),any(),any(),eq(true))).thenThrow(new IllegalStateException("Source withdrawn"));
        assertThatThrownBy(()->reader.report(f.scope.task(),f.scope.comparison(),receipt.reportId(),f.source.fixture.human)).hasMessage("Source withdrawn");
        assertThatThrownBy(()->reader.results(f.scope.task(),f.scope.comparison(),f.source.fixture.human)).hasMessage("Source withdrawn");
    }
    @Test void httpCoordinatesExecuteServerCalculationAndRejectClientRows() throws Exception {
        var input=f.source.fixture.input;var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ProductComparisonMatrixController(f.execution,execution,reader(),input.json)).build();
        var lease=f.acquire("matrix-http");String endpoint="/v2/consultation/tasks/"+f.scope.task()+"/executions/"+lease.attemptId()+"/patent-comparison-result";
        var command=new ProductComparisonMatrixController.Execute(f.scope.comparison(),f.scope.candidate(),f.scope.inputSha(),f.scope.delegation(),lease.token(),lease.until());
        var certificate=f.source.request.getAttribute("jakarta.servlet.request.X509Certificate");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint).secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate",certificate).contentType("application/json").content(input.json.canonical(command)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("COMMITTED"));
        assertThat(calls).hasValue(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint).secure(true)
                .requestAttr("jakarta.servlet.request.X509Certificate",certificate).contentType("application/json")
                .content(input.json.canonical(command).replaceFirst("\\}",",\"rows\":[]} ")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(calls).hasValue(1);
    }
    @Test void emptyPhysicalReverseRoundTripAndRetainedInvocationRejectsRollback() throws Exception {
        // U100's empty round trip is tested at its own schema version, before later foreign keys exist.
        setup(false);
        ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/U100__product_comparison_matrix.sql");
        ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/V100__product_comparison_matrix.sql");
        ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/V108__product_comparison_lineage.sql");
        execution.execute(f.source.request,f.scope,f.acquire("matrix-migration-retained"));
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(f.source.fixture.input.main,"db/dm8/U100__product_comparison_matrix.sql")).isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(f.count("con5_matrix_report")).isEqualTo(1);
    }
    @Test void insufficientRemainingModelInputWindowNeverSendsAndRecordsKnownPreCallFailure() {
        var now=f.source.fixture.input.clock.instant();permit=new ProductComparisonModelAdmission.Permit(permit.approval(),permit.evidenceSha(),now.plusSeconds(5));
        when(admission.require(any())).thenReturn(permit);var lease=f.acquire("short-disclosure-window");
        assertThatThrownBy(()->execution.execute(f.source.request,f.scope,lease)).hasMessage("COMPARISON_EXECUTION_WINDOW_INSUFFICIENT");
        assertThat(calls).hasValue(0);assertThat(f.count("con5_matrix_report")).isZero();assertThat(f.status()).isEqualTo("FAILED");
        assertThat(f.source.fixture.db.queryForObject("SELECT error_code FROM con5_candidate_attempt",String.class)).isEqualTo("EXECUTION_WINDOW_INSUFFICIENT");
    }
    @Test void everyDeadlineMustStrictlyExceedFiniteManagedTimeout() {
        var now=Instant.parse("2026-10-07T06:00:00Z");
        assertThatThrownBy(()->ProductComparisonMatrixExecution.requireDispatchWindow(now,Duration.ofSeconds(20),now.plusSeconds(60),now.plusSeconds(20))).hasMessageContaining("WINDOW_INSUFFICIENT");
        assertThatThrownBy(()->ProductComparisonMatrixExecution.requireDispatchWindow(now,Duration.ZERO,now.plusSeconds(60))).hasMessageContaining("WINDOW_INSUFFICIENT");
        assertThatThrownBy(()->ProductComparisonMatrixExecution.requireDispatchWindow(now,Duration.ofSeconds(20),(Instant)null)).hasMessageContaining("WINDOW_INSUFFICIENT");
        assertThatCode(()->ProductComparisonMatrixExecution.requireDispatchWindow(now,Duration.ofSeconds(20),now.plusSeconds(21),now.plusSeconds(60))).doesNotThrowAnyException();
    }
}

