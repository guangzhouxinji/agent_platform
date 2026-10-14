package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonInputs.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonPreparationTest.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Product input transaction with actual capture and mechanical H2/authority ports, not DM8/Worker I. */
class ProductComparisonInputTest {
    final ProductComparisonPreparationTest base=new ProductComparisonPreparationTest();
    final java.time.Clock clock=base.clock;
    final ProductComparisonJson json=base.json;
    final com.fasterxml.jackson.databind.ObjectMapper mapper=base.mapper;
    final ProductConsultationAccess access=base.access;
    final ProductComparisonDocuments documents=base.documents;
    final org.springframework.security.oauth2.jwt.Jwt jwt=base.jwt;
    final java.time.Instant until=base.until;
    org.springframework.jdbc.core.JdbcTemplate db;
    org.h2.jdbcx.JdbcDataSource main;
    ProductComparisonStore store;
    ProductTaskGoalService.Result goal;
    ProductComparisonDocuments.Loaded candidate;
    ProductComparisonContracts.PreparationView prepare() {return base.prepare();}
    ProductComparisonContracts.FeatureView confirm(ProductComparisonContracts.PreparationView p,String key,long previous) {return base.confirm(p,key,previous);}
    long count(String table) {return base.count(table);}
    ProductComparisonInputApplication comparisons;
    ProductComparisonInputStore inputStore;
    ProductComparisonContracts.FeatureView confirmed;
    Create create;
    @BeforeEach void inputSetup() throws Exception {
        base.setup();db=base.db;main=base.main;store=base.store;goal=base.goal;candidate=base.candidate;
        ProductSourceScopeGrantTest.script(main,"db/dm8/V095__product_comparison_input.sql");
        var directory=new JdbcProductTaskDirectory(db,clock);
        var targets=new ProductComparisonTargetReader(db,directory,access,clock,65536);
        var loader=new ProductComparisonPreparationLoader(db,directory,access,targets,documents,json,clock);
        inputStore=new ProductComparisonInputStore(db,json);
        comparisons=new ProductComparisonInputApplication(loader,store,inputStore,new TransactionTemplate(new DataSourceTransactionManager(main)),json,clock);
        confirmed=confirm(prepare(),"features-for-input-C",0);
        create=commandFor(confirmed,null);
    }
    Create commandFor(ProductComparisonContracts.FeatureView f,String prior) {
        return new Create(goal.taskVersion(),f.revision().revisionId(),f.revision().revisionNumber(),f.packageSha(),prior,"PATENT_FEATURE_MATRIX_V1");
    }
    @Test void createBindsConfirmedFeaturesAndRealOriginalCandidatesWithoutClaimingExecution() {
        var view=comparisons.create(goal.taskId(),create,"input-C",jwt);
        assertThat(view.input().confirmedFeatures()).isEqualTo(confirmed);
        assertThat(view.input().candidates()).containsExactly(new Candidate(candidate.material().key(),0));
        assertThat(db.queryForObject("SELECT status FROM con5_comparison_candidate",String.class)).isEqualTo("QUEUED");
        assertThat(view.candidateProgress()).containsExactly(new Progress(candidate.material().key(),"QUEUED"));
        assertThat(comparisons.read(goal.taskId(),view.input().comparisonId(),jwt)).isEqualTo(view);
        assertThat(comparisons.consume(goal.taskId(),view.input().comparisonId(),jwt)).isEqualTo(view);
        assertThat(view.input().currentPreparationSnapshot()).doesNotContain(candidate.original().text());
    }
    @Test void idempotencyAndPreviousPointerCasPreserveOldInputs() {
        var first=comparisons.create(goal.taskId(),create,"input-C",jwt);
        assertThat(comparisons.create(goal.taskId(),create,"input-C",jwt)).isEqualTo(first);
        status(()->comparisons.create(goal.taskId(),create,"new-but-stale-C",jwt),HttpStatus.CONFLICT);
        var next=commandFor(confirmed,first.input().comparisonId());
        status(()->comparisons.create(goal.taskId(),next,"input-C",jwt),HttpStatus.CONFLICT);
        var second=comparisons.create(goal.taskId(),next,"input-2-C",jwt);
        assertThat(second.input().previousComparisonId()).isEqualTo(first.input().comparisonId());
        assertThat(comparisons.read(goal.taskId(),first.input().comparisonId(),jwt)).isEqualTo(first);
        status(()->comparisons.consume(goal.taskId(),first.input().comparisonId(),jwt),HttpStatus.CONFLICT);
        assertThat(count("con5_comparison_input")).isEqualTo(2);
    }
    @Test void newerFeatureRevisionInvalidatesOldInputConsumptionButPreservesHistory() {
        var view=comparisons.create(goal.taskId(),create,"input-C",jwt);
        var second=confirm(prepare(),"features-2-C",1);
        status(()->comparisons.create(goal.taskId(),create,"stale-feature-C",jwt),HttpStatus.CONFLICT);
        status(()->comparisons.consume(goal.taskId(),view.input().comparisonId(),jwt),HttpStatus.CONFLICT);
        assertThat(comparisons.read(goal.taskId(),view.input().comparisonId(),jwt)).isEqualTo(view);
        var fresh=comparisons.create(goal.taskId(),commandFor(second,view.input().comparisonId()),"fresh-feature-C",jwt);
        assertThat(fresh.input().confirmedFeatures()).isEqualTo(second);
    }
    @Test void comparePermissionIsRequiredAndReadDoesNotGrantExecution() {
        when(access.permits(any(),any(),eq(ProductAccessPolicy.Action.COMPARE))).thenReturn(false);
        status(()->comparisons.create(goal.taskId(),create,"denied-C",jwt),HttpStatus.NOT_FOUND);
        assertThat(count("con5_comparison_input")).isZero();
    }
    @Test void changedAuthorityInvalidatesInputConsumptionButNotAuthorizedHistoricalMaterial() {
        var view=comparisons.create(goal.taskId(),create,"input-C",jwt);var a=candidate.authority();
        var moved=new ProductComparisonDocuments.Loaded(candidate.reference(),candidate.original(),candidate.material(),
                new ProductComparisonDocuments.Authority(a.source(),a.display(),new ProductPatentDocumentAccess.Authority(
                        a.comparison().scopeId(),a.comparison().scopeVersion(),goal.taskVersion(),a.comparison().grantId(),2),until));
        when(documents.read(anyString(),any(),any(),anyBoolean())).thenReturn(moved);
        status(()->comparisons.consume(goal.taskId(),view.input().comparisonId(),jwt),HttpStatus.CONFLICT);
        assertThat(comparisons.read(goal.taskId(),view.input().comparisonId(),jwt)).isEqualTo(view);
    }
    @Test void missingEventAndLastAuthorityFailureRollBackAllNewInputState() {
        db.execute("DROP TABLE con5_comparison_event");
        assertThatThrownBy(()->comparisons.create(goal.taskId(),create,"missing-C",jwt)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("con5_comparison_input","con5_comparison_candidate","con5_comparison_current")) assertThat(count(table)).isZero();
    }
    @Test void authorityFailureAfterWriteRollsBackReceiptAndCandidateSlots() {
        when(access.permits(any(),any(),any())).thenAnswer(call->count("con5_comparison_input")==0);
        status(()->comparisons.create(goal.taskId(),create,"last-check-C",jwt),HttpStatus.NOT_FOUND);
        for(String table:List.of("con5_comparison_input","con5_comparison_candidate","con5_comparison_current","con5_comparison_event")) assertThat(count(table)).isZero();
    }
    @Test void candidateLossAndInputNativeTamperRejectReads() {
        var view=comparisons.create(goal.taskId(),create,"input-C",jwt);
        db.update("DELETE FROM con5_comparison_candidate WHERE comparison_id=?",view.input().comparisonId());
        assertThatThrownBy(()->comparisons.read(goal.taskId(),view.input().comparisonId(),jwt)).hasMessage("Comparison input closure or digest invalid");
    }
    @Test void inputExpiryDuringFinalNativeReadRejectsDelivery() {
        var view=comparisons.create(goal.taskId(),create,"terminal-expiry-C",jwt);
        var now=new java.util.concurrent.atomic.AtomicReference<>(base.now);
        var readingClock=mock(java.time.Clock.class);
        when(readingClock.instant()).thenAnswer(call->now.get());
        var reader=readerAfterFinalNativeRead(view,readingClock,()->now.set(view.input().expiresAt()));
        status(()->reader.read(goal.taskId(),view.input().comparisonId(),jwt),HttpStatus.CONFLICT);
    }
    @Test void readRevocationDuringFinalNativeReadRejectsDelivery() {
        var view=comparisons.create(goal.taskId(),create,"terminal-revocation-C",jwt);
        var reader=readerAfterFinalNativeRead(view,clock,()->when(access.permits(any(),any(),eq(ProductAccessPolicy.Action.READ))).thenReturn(false));
        status(()->reader.read(goal.taskId(),view.input().comparisonId(),jwt),HttpStatus.NOT_FOUND);
    }
    @Test void sourceLossDuringFinalNativeReadRejectsDelivery() {
        var view=comparisons.create(goal.taskId(),create,"terminal-source-C",jwt);
        var reader=readerAfterFinalNativeRead(view,clock,()->when(documents.read(anyString(),any(),any(),anyBoolean()))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND,"SOURCE_UNAVAILABLE")));
        status(()->reader.read(goal.taskId(),view.input().comparisonId(),jwt),HttpStatus.NOT_FOUND);
    }
    @Test void stableTerminalReadPreservesReceiptAndAllNativeRows() {
        var view=comparisons.create(goal.taskId(),create,"terminal-stable-C",jwt);
        var reader=readerAfterFinalNativeRead(view,clock,()->{});
        assertThat(reader.read(goal.taskId(),view.input().comparisonId(),jwt)).isEqualTo(view);
        for(String table:List.of("con5_comparison_input","con5_comparison_candidate","con5_comparison_current","con5_comparison_event"))
            assertThat(count(table)).isEqualTo(1);
        assertThat(inputStore.read(goal.taskId(),view.input().comparisonId())).isEqualTo(view);
    }
    private ProductComparisonInputApplication readerAfterFinalNativeRead(View view,java.time.Clock readingClock,Runnable onFinalRead) {
        var nativeInputs=spy(inputStore);
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            var result=call.callRealMethod();
            if(reads.incrementAndGet()==2)onFinalRead.run();
            return result;
        }).when(nativeInputs).read(goal.taskId(),view.input().comparisonId());
        var directory=new JdbcProductTaskDirectory(db,readingClock);
        var targets=new ProductComparisonTargetReader(db,directory,access,readingClock,65536);
        var loader=new ProductComparisonPreparationLoader(db,directory,access,targets,documents,json,readingClock);
        return new ProductComparisonInputApplication(loader,store,nativeInputs,
                new TransactionTemplate(new DataSourceTransactionManager(main)),json,readingClock);
    }
    @Test void frozenMethodAndPackageMismatchRejectWithoutWrites() {
        status(()->comparisons.create(goal.taskId(),new Create(goal.taskVersion(),create.featureRevisionId(),1,HASH,null,"OTHER"),"method-C",jwt),HttpStatus.UNPROCESSABLE_ENTITY);
        status(()->comparisons.create(goal.taskId(),new Create(goal.taskVersion(),create.featureRevisionId(),1,HASH,null,create.methodVersion()),"sha-C",jwt),HttpStatus.CONFLICT);
        assertThat(count("con5_comparison_input")).isZero();
    }
    @Test void nonemptyInputReverseRefusesWithoutDeletingHistory() throws Exception {
        var view=comparisons.create(goal.taskId(),create,"input-C",jwt);
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(main,"db/dm8/U095__product_comparison_input.sql"))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(inputStore.read(goal.taskId(),view.input().comparisonId())).isEqualTo(view);
    }
    @Test void emptyReverseAndReapplyPreserveConfirmedFeatures() throws Exception {
        ProductSourceScopeGrantTest.script(main,"db/dm8/U095__product_comparison_input.sql");
        assertThat(count("con5_feature_revision")).isEqualTo(1);
        ProductSourceScopeGrantTest.script(main,"db/dm8/V095__product_comparison_input.sql");
        assertThat(count("con5_comparison_input")).isZero();
    }
    @Test void formalApiCreatesAndReadsExactInputAndRejectsMalformedOrAnonymous() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ProductComparisonInputController(comparisons,json))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
        String path="/v2/consultation/tasks/"+goal.taskId()+"/patent-comparisons";
        var matchers=org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-input-C")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("null")).andExpect(matchers.isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-input-C")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.canonical(create))).andExpect(matchers.isUnauthorized());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        try {
            String body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","http-input-C")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.canonical(create)))
                    .andExpect(matchers.isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("currentPreparationSnapshot","authorityBindings","actorIssuer");
            var view=json.request(body,Receipt.class);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/"+view.comparisonId()))
                    .andExpect(matchers.isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.inputSha").value(view.inputSha()));
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
