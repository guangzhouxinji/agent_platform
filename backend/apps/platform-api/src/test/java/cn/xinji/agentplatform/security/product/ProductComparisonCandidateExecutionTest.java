package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent H2 candidate transaction/fence tests, no actual model, DM8, browser or professional acceptance. */
class ProductComparisonCandidateExecutionTest {
    final ProductComparisonWorkerMaterialsTest source=new ProductComparisonWorkerMaterialsTest();
    ProductComparisonCandidateExecution execution;
    ProductComparisonCandidateExecution.Scope scope;
    TransactionTemplate transactions;
    @BeforeEach void setup() throws Exception {
        source.setup();var f=source.fixture;
        ProductSourceScopeGrantTest.script(f.input.main,"db/dm8/V043__product_task_cancel_epoch.sql");
        ProductSourceScopeGrantTest.script(f.input.main,"db/dm8/V045__product_task_execution_fence.sql");
        ProductSourceScopeGrantTest.script(f.input.main,"db/dm8/V099__product_comparison_candidate_lease.sql");
        transactions=new TransactionTemplate(new DataSourceTransactionManager(f.input.main));
        execution=new ProductComparisonCandidateExecution(f.db,transactions,source.materials,f.input.json,f.input.clock);
        scope=new ProductComparisonCandidateExecution.Scope(f.task,f.view.input().comparisonId(),f.command.candidateId(),f.view.inputSha(),source.intent.delegationId());
    }
    ProductComparisonCandidateExecution.Lease acquire(String key) {return execution.acquire(source.request,scope,key,Duration.ofSeconds(60));}
    String status() {return source.fixture.db.queryForObject("SELECT status FROM con5_comparison_candidate WHERE comparison_id=? AND candidate_id=?",
            String.class,scope.comparison(),scope.candidate());}
    long count(String table) {return source.fixture.count(table);}
    @Test void startsOneCandidateAndReplaysOnlyExactCurrentLeaseWithoutCommittingResult() {
        var lease=acquire("candidate-one");assertThat(lease.token()).isEqualTo(1);
        assertThat(lease.until()).isEqualTo(source.fixture.input.clock.instant().plusSeconds(60));
        assertThat(acquire("candidate-one")).isEqualTo(lease);execution.require(source.request,scope,lease);
        assertThat(status()).isEqualTo("RUNNING");assertThat(count("con5_candidate_attempt")).isEqualTo(1);
        assertThat(count("con5_candidate_lease")).isEqualTo(1);
        assertThat(source.fixture.db.queryForObject("SELECT COUNT(*) FROM con5_candidate_attempt WHERE status='COMMITTED'",Long.class)).isZero();
        assertThatThrownBy(()->acquire("candidate-another")).hasMessageContaining("Stale comparison");
        assertThatThrownBy(()->execution.acquire(source.request,scope,"candidate-one",Duration.ofSeconds(61))).hasMessageContaining("Stale comparison");
    }
    @Test void boundsLeaseByCurrentMaterialAndDelegationDeadline() {
        var lease=execution.acquire(source.request,scope,"candidate-bound",Duration.ofDays(1));
        assertThat(lease.until()).isEqualTo(source.intent.expiresAt());
    }
    @Test void knownFailurePreservesAttemptAndNewKeyGetsNewFence() throws Exception {
        ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/V100__product_comparison_matrix.sql");
        var old=acquire("candidate-failure");execution.fail(source.request,scope,old,"MODEL_UNAVAILABLE");
        assertThat(status()).isEqualTo("FAILED");
        assertThatThrownBy(()->acquire("candidate-failure")).hasMessageContaining("Stale comparison");
        var next=acquire("candidate-retry");assertThat(next.token()).isEqualTo(2);assertThat(next.attemptId()).isNotEqualTo(old.attemptId());
        assertThat(count("con5_candidate_attempt")).isEqualTo(2);assertThat(status()).isEqualTo("RUNNING");
        assertThatThrownBy(()->execution.require(source.request,scope,old)).hasMessageContaining("Stale comparison");
        assertThatThrownBy(()->execution.fail(source.request,scope,old,"MODEL_UNAVAILABLE")).hasMessageContaining("Stale comparison");
        assertThat(source.fixture.db.queryForObject("SELECT error_code FROM con5_candidate_attempt WHERE attempt_id=?",String.class,old.attemptId()))
                .isEqualTo("MODEL_UNAVAILABLE");execution.require(source.request,scope,next);
    }
    @Test void taskCancellationEpochAndForgedTokenCannotUseCurrentSlot() {
        var lease=acquire("candidate-token");
        assertThatThrownBy(()->execution.require(source.request,scope,new ProductComparisonCandidateExecution.Lease(lease.attemptId(),2,lease.until())))
                .hasMessageContaining("Stale comparison");
        source.fixture.db.update("UPDATE con3_task SET cancel_epoch=cancel_epoch+1 WHERE task_id=?",scope.task());
        assertThatThrownBy(()->execution.require(source.request,scope,lease)).hasMessageContaining("Stale comparison");
    }
    @Test void expiredActiveLeaseRequiresReconciliationWithoutAnotherAttemptOrBlindRetry() {
        var lease=acquire("candidate-expired");
        var later=new ProductComparisonCandidateExecution(source.fixture.db,transactions,source.materials,source.fixture.input.json,
                Clock.fixed(lease.until().plusMillis(1),ZoneOffset.UTC));
        assertThatThrownBy(()->later.acquire(source.request,scope,"candidate-new",Duration.ofSeconds(60)))
                .isInstanceOf(ProductComparisonCandidateExecution.ReconciliationRequired.class);
        assertThat(count("con5_candidate_attempt")).isEqualTo(1);assertThat(status()).isEqualTo("FAILED");
        assertThat(source.fixture.db.queryForObject("SELECT status FROM con5_candidate_lease",String.class)).isEqualTo("RECONCILE_REQUIRED");
        assertThatThrownBy(()->later.acquire(source.request,scope,"candidate-new",Duration.ofSeconds(60))).hasMessageContaining("Stale comparison");
    }
    @Test void missingCurrentLeaseTableAndLateSourceFailureRollBackAttemptAndProgress() {
        source.fixture.db.execute("DROP TABLE con5_candidate_lease");
        assertThatThrownBy(()->acquire("candidate-table-failure")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("con5_candidate_attempt")).isZero();assertThat(status()).isEqualTo("QUEUED");
    }
    @Test void failureBetweenInsertAndReturnRollsBackEveryCandidateWrite() {
        var spy=spy(source.materials);var loaded=source.consume();
        doReturn(loaded).doReturn(loaded).doThrow(new IllegalStateException("Late source withdrawal"))
                .when(spy).consume(any(),anyString(),anyString(),anyString(),anyString(),anyString());
        var guarded=new ProductComparisonCandidateExecution(source.fixture.db,transactions,spy,source.fixture.input.json,source.fixture.input.clock);
        assertThatThrownBy(()->guarded.acquire(source.request,scope,"candidate-late-failure",Duration.ofSeconds(60)))
                .hasMessage("Late source withdrawal");
        assertThat(count("con5_candidate_attempt")).isZero();assertThat(count("con5_candidate_lease")).isZero();assertThat(status()).isEqualTo("QUEUED");
    }
    @Test void twoCapturedCandidatesHaveIndependentAttemptsAndOneFailurePreservesTheOther() throws Exception {
        ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/V100__product_comparison_matrix.sql");
        var f=source.fixture;var input=f.input;var base=input.base;
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
        when(input.documents.read(eq(f.task),eq(docRef),any(),anyBoolean())).thenReturn(second);
        when(input.documents.readDelegated(eq(f.task),eq(docRef),any())).thenReturn(second);
        var old=base.command;base.command=new ProductComparisonContracts.Prepare(old.expectedTaskVersion(),old.goalId(),old.goalRevision(),
                old.inputId(),old.purpose(),old.target(),List.of(input.candidate.reference(),docRef));
        var prepared=base.prepare();var features=base.confirm(prepared,"paired-features",input.confirmed.revision().revisionNumber());
        var command=new ProductComparisonInputs.Create(old.expectedTaskVersion(),features.revision().revisionId(),features.revision().revisionNumber(),
                features.packageSha(),f.view.input().comparisonId(),"PATENT_FEATURE_MATRIX_V1");
        var paired=input.comparisons.create(f.task,command,"paired-input",f.human);
        var firstIntent=f.application.issue(f.task,paired.input().comparisonId(),new ProductComparisonWorkerDelegations.Issue(
                f.service,input.candidate.material().key(),paired.inputSha(),f.command.expiresAt()),f.human,"paired-first");
        var secondIntent=f.application.issue(f.task,paired.input().comparisonId(),new ProductComparisonWorkerDelegations.Issue(
                f.service,material.key(),paired.inputSha(),f.command.expiresAt()),f.human,"paired-second");
        var firstScope=new ProductComparisonCandidateExecution.Scope(f.task,paired.input().comparisonId(),input.candidate.material().key(),paired.inputSha(),firstIntent.delegationId());
        var secondScope=new ProductComparisonCandidateExecution.Scope(f.task,paired.input().comparisonId(),material.key(),paired.inputSha(),secondIntent.delegationId());
        var first=execution.acquire(source.request,firstScope,"paired-lease",Duration.ofSeconds(60));
        var other=execution.acquire(source.request,secondScope,"paired-lease",Duration.ofSeconds(60));
        assertThat(other.attemptId()).isNotEqualTo(first.attemptId());assertThat(other.token()).isEqualTo(1);
        execution.fail(source.request,secondScope,other,"MODEL_UNAVAILABLE");execution.require(source.request,firstScope,first);
        assertThat(input.inputStore.read(f.task,paired.input().comparisonId()).candidateProgress()).containsExactly(
                new ProductComparisonInputs.Progress(input.candidate.material().key(),"RUNNING"),new ProductComparisonInputs.Progress(material.key(),"FAILED"));
        assertThat(count("con5_candidate_attempt")).isEqualTo(2);assertThat(count("con5_candidate_lease")).isEqualTo(2);
    }
    @Test void currentShorterReadDeadlineCannotReuseAnOlderLongerLease() {
        var lease=acquire("candidate-current-deadline");var loaded=source.consume();
        var changed=new ProductComparisonWorkerMaterials.Loaded(loaded.input(),loaded.target(),loaded.targetPatent(),loaded.candidate(),
                loaded.actor(),source.fixture.input.clock.instant().plusSeconds(30));
        var port=mock(ProductComparisonWorkerMaterials.class);when(port.consume(any(),anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(changed);
        var guarded=new ProductComparisonCandidateExecution(source.fixture.db,transactions,port,source.fixture.input.json,source.fixture.input.clock);
        assertThatThrownBy(()->guarded.require(source.request,scope,lease)).hasMessageContaining("Stale comparison");
    }
    @Test void exactScopeAndCurrentRightsAreRequiredBeforeLeaseOrFailureMutation() {
        var wrong=new ProductComparisonCandidateExecution.Scope(scope.task(),scope.comparison(),"foreign",scope.inputSha(),scope.delegation());
        assertThatThrownBy(()->execution.acquire(source.request,wrong,"candidate-foreign",Duration.ofSeconds(60))).isInstanceOf(RuntimeException.class);
        var lease=acquire("candidate-rights");
        source.fixture.application.revoke(scope.task(),scope.comparison(),scope.delegation(),1,source.fixture.human,"candidate-revoke");
        assertThatThrownBy(()->execution.require(source.request,scope,lease)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->execution.fail(source.request,scope,lease,"MODEL_UNAVAILABLE")).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("RUNNING");
    }
    @Test void emptyReverseRoundTripAndNonemptyHistoryRejectsRollback() throws Exception {
        ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/U099__product_comparison_candidate_lease.sql");
        ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/V099__product_comparison_candidate_lease.sql");
        acquire("candidate-retained");
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/U099__product_comparison_candidate_lease.sql"))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(count("con5_candidate_attempt")).isEqualTo(1);assertThat(status()).isEqualTo("RUNNING");
    }
    @Test void reservedUnknownCallCannotBecomeRetryableByMarkingLeaseFailed() throws Exception {
        ProductSourceScopeGrantTest.script(source.fixture.input.main,"db/dm8/V100__product_comparison_matrix.sql");
        var old=acquire("owned-before-unknown");var f=source.fixture;
        f.db.update("INSERT INTO con5_matrix_invocation VALUES(?,?,?,?,'RESERVED',NULL,NULL,?)",
                old.attemptId(),"a".repeat(64),"b".repeat(64),"owned-configuration",java.sql.Timestamp.from(f.input.clock.instant()));
        assertThatThrownBy(()->execution.fail(source.request,scope,old,"MODEL_UNAVAILABLE"))
                .isInstanceOf(ProductComparisonCandidateExecution.ReconciliationRequired.class);
        assertThat(status()).isEqualTo("RUNNING");
        // Old/damaged FAILED projections also cannot authorize a new attempt.
        f.db.update("UPDATE con5_candidate_attempt SET status='FAILED',error_code='MODEL_UNAVAILABLE'");
        f.db.update("UPDATE con5_candidate_lease SET status='FAILED'");
        f.db.update("UPDATE con5_comparison_candidate SET status='FAILED'");
        assertThatThrownBy(()->acquire("new-blind-key")).isInstanceOf(ProductComparisonCandidateExecution.ReconciliationRequired.class);
        assertThat(count("con5_candidate_attempt")).isEqualTo(1);
        assertThat(f.db.queryForObject("SELECT status FROM con5_matrix_invocation",String.class)).isEqualTo("RESERVED");
    }

}
