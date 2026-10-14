package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real capture bytes with mechanical H2/actor/Source-port C. Never a human/source admission or DM8 HTTP I. */
class ProductComparisonPreparationTest {
    static final String ISSUER="https://identity.example.org",HASH="a".repeat(64);
    final Instant now=Instant.parse("2026-10-05T12:00:00Z"),until=now.plusSeconds(3600);
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final ProductComparisonJson json=new ProductComparisonJson(mapper);
    final ProductConsultationAccess access=mock(ProductConsultationAccess.class);
    final ProductComparisonDocuments documents=mock(ProductComparisonDocuments.class);
    final Jwt jwt=Jwt.withTokenValue("mechanical-C-only").header("alg","RS256").issuer(ISSUER).subject("member").build();
    JdbcTemplate db; org.h2.jdbcx.JdbcDataSource main;
    ProductComparisonPreparation application; ProductComparisonStore store;
    ProductTaskGoalService.Result goal;
    ProductComparisonDocuments.Loaded candidate;
    Prepare command;

    @BeforeEach void setup() throws Exception {
        main=ProductSourceScopeGrantTest.database();var independent=ProductSourceScopeGrantTest.database();
        for(String file:List.of("V032__product_identity.sql","V034__product_consultation_initial_task.sql",
                "V036__product_source_candidates.sql","V037__product_source_admission.sql","V038__product_task_source_scope.sql",
                "V039__product_source_verification.sql","V041__product_task_authorization_version.sql","V042__product_consultation_goal.sql",
                "V053__product_source_use_decision.sql","V056__product_query_only_source_scope.sql",
                "V072__product_search_metadata_filters.sql","V092__product_source_comparison_use.sql",
                "V094__product_comparison_feature_confirmation.sql")) ProductSourceScopeGrantTest.script(main,"db/dm8/"+file);
        ProductSourceScopeGrantTest.script(independent,"db/identity-authority/V001__grant_watermark.sql");
        ProductSourceScopeGrantTest.script(independent,"db/identity-authority/V003__consultation_source_watermark.sql");
        db=new JdbcTemplate(main);var authority=new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES(?,'member','mechanical-C-only','encoded','ACTIVE',1,?)",ISSUER,Timestamp.from(now));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(main));
        var watermark=new ProductGrantWatermark(db,authority,ISSUER);var grants=new ProductGrants(db,tx,clock);
        var directory=new JdbcProductTaskDirectory(db,clock);
        var policy=new ProductRetentionPolicy("mechanical-C-retention",1,HASH,Duration.ofHours(2));
        var initial=new ProductInitialTaskService(directory,grants,watermark,tx,mapper,clock,ISSUER,policy);
        var task=initial.create(new ProductInitialTaskService.Input("organization","Mechanical C","member",
                policy.reference(),1,until),"initial-C","maintainer-C","organization");
        when(access.permits(any(),any(),any())).thenReturn(true);
        goal=new ProductTaskGoalService(db,directory,access,tx,policy,mapper,clock,65536).confirm(task.taskId(),jwt,"goal-C",
                new ProductTaskGoalService.Input(1,"A valve with a pressure sensor.","SEARCH","valve",List.of("source-1"),
                        List.of("TITLE"),List.of(),true,policy.reference(),1));
        var sources=new ProductSourceWatermark(db,authority,tx);
        sources.change("source-1",intent->{ProductSourceScopeGrantTest.admit(db,until,task.taskId(),task.dataScope().split(":")[0],intent,clock);
            db.update("INSERT INTO con4_source_field_use VALUES('11111111-1111-4111-8111-111111111111','CLAIMS','COMPARISON',?)",Timestamp.from(until));return null;});
        var scope=new ProductSourceScopeGrants(db,tx,directory,grants,watermark,sources,null,clock,ISSUER).authorizeVerified(
                new ProductSourceScopeGrants.Input(task.taskId(),"member","source-1",goal.taskVersion(),1,until,"scope-C"),"maintainer-C","organization");
        var compare=watermark.change(()->grants.issueSourceScope(new ProductGrants.Scope(ISSUER,"member","organization","CONSULTATION",
                task.taskId(),scope.scopeId()+":1","SOURCE_SCOPE",scope.scopeId(),1,"COMPARE","COMPARISON"),until,0,
                "maintainer-C","compare-C",HASH,sources));
        String ref="data/patents/patentsview-real-candidate-20261002-v3/records/3931897.json";
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isDirectory(root.resolve("data/patents"))) root=root.getParent();
        if(root==null) throw new IllegalStateException("Real capture root required");
        byte[] raw=Files.readAllBytes(root.resolve(ref));String rawSha=ProductCapturedArchive.digest(raw);
        var original=new ProductPatentClaimOriginal(ref,raw,rawSha,"RAW_SHA256:"+rawSha,"claimsText",mapper);
        var graph=PatentClaimGraph.parse(original);
        var branches=graph.branches(1,4000).stream().map(b->new Branch(b.selectedClaim(),b.inheritedClaims().stream()
                .map(c->new Claim(c.number(),c.alternatives(),Span.of(c.original()))).toList())).toList();
        var docRef=new DocumentRef("source-1","3931897",original.evidence(0,original.text().length()).documentVersion(),List.of(1),null,null,null,null);
        // The source/actor binding is explicitly mechanical; original bytes/IDs/hash are actual captured material.
        var material=new MaterialDocument(ProductComparisonJson.hash(docRef.sourceId()+"\n"+docRef.documentId()+"\n"+docRef.documentVersion()),"source-1","3931897",docRef.documentVersion(),"capture-1",rawSha,ref,"/claimsText",branches,"");
        var active=new ProductSourceAvailability.Active("source-1","contract-1","capture-1",
                "11111111-1111-4111-8111-111111111111",1,1,"mechanical-C-only",HASH,HASH);
        candidate=new ProductComparisonDocuments.Loaded(docRef,original,material,new ProductComparisonDocuments.Authority(active,
                new ProductPatentDocumentAccess.Authority(scope.scopeId(),1,goal.taskVersion(),scope.displayGrantId(),1),
                new ProductPatentDocumentAccess.Authority(scope.scopeId(),1,goal.taskVersion(),compare.grantId(),1),until));
        when(documents.read(eq(task.taskId()),any(),eq(jwt),anyBoolean())).thenReturn(candidate);
        var targetReader=new ProductComparisonTargetReader(db,directory,access,clock,65536);
        var loader=new ProductComparisonPreparationLoader(db,directory,access,targetReader,documents,json,clock);
        store=new ProductComparisonStore(db,json);application=new ProductComparisonPreparation(loader,store,tx,json,clock,65536);
        command=new Prepare(goal.taskVersion(),goal.goalId(),1,goal.inputId(),"TECHNICAL_SIMILARITY",new Target(goal.messageId(),null),List.of(docRef));
    }
    PreparationView prepare() {return application.prepare(goal.taskId(),command,jwt);}
    Confirm confirmCommand(PreparationView view,long previous) {
        return new Confirm(goal.taskVersion(),view.preparationId(),view.prepareSha(),previous,true,"I confirm these inputs only",
                List.of(new FeatureInput("F2",15,23,"Pressure feature","F1"),new FeatureInput("F1",2,7,"Valve feature",null)));
    }
    FeatureView confirm(PreparationView view,String key,long previous) {return application.confirm(goal.taskId(),confirmCommand(view,previous),key,jwt);}
    long count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    @Test void prepareThenExplicitConfirmationPersistsOriginalSpansAndAtomicReceipt() {
        var prepared=prepare();assertThat(prepared.targetText()).isEqualTo("A valve with a pressure sensor.");
        assertThat(prepared.featureSuggestions()).isEmpty();assertThat(count("con5_feature_revision")).isZero();
        assertThat(prepared.candidates().getFirst().claimsText()).isEqualTo(candidate.original().text());
        var revision=confirm(prepared,"confirm-C",0);
        assertThat(revision.revision().features()).extracting(Feature::featureId).containsExactly("F2","F1");
        assertThat(count("con5_feature_revision")).isEqualTo(1);assertThat(count("con5_feature_event")).isEqualTo(1);
        assertThat(application.read(goal.taskId(),revision.revision().revisionId(),jwt)).isEqualTo(revision);
        assertThat(db.queryForObject("SELECT branch_json FROM con5_prepared_document",String.class)).doesNotContain("\"text\":",candidate.original().text());
    }
    @Test void samePayloadReplayDoesNotIncrementVersionAndDifferentPayloadRejects() {
        var prepared=prepare();var first=confirm(prepared,"confirm-C",0);assertThat(confirm(prepared,"confirm-C",0)).isEqualTo(first);
        var other=new Confirm(goal.taskVersion(),prepared.preparationId(),prepared.prepareSha(),0,true,"Changed user statement",confirmCommand(prepared,0).features());
        status(()->application.confirm(goal.taskId(),other,"confirm-C",jwt),HttpStatus.CONFLICT);
        assertThat(count("con5_feature_revision")).isEqualTo(1);assertThat(count("con5_feature_event")).isEqualTo(1);
    }
    @Test void casSecondRevisionKeepsFirstHistoryAndRejectsOldCounter() {
        var prepared=prepare();var first=confirm(prepared,"first-C",0);
        status(()->confirm(prepared,"old-C",0),HttpStatus.CONFLICT);
        var second=confirm(prepared,"second-C",1);
        assertThat(second.revision().previousRevisionId()).isEqualTo(first.revision().revisionId());
        assertThat(store.revision(goal.taskId(),first.revision().revisionId())).isEqualTo(first);
        assertThat(store.current(goal.taskId()).number()).isEqualTo(2);
    }
    @Test void noImplicitConfirmationUnknownParentCycleAndHalfCharacterRejectWithoutWrites() {
        var prepared=prepare();var baseline=confirmCommand(prepared,0);
        status(()->application.confirm(goal.taskId(),new Confirm(goal.taskVersion(),prepared.preparationId(),prepared.prepareSha(),0,false,"",baseline.features()),"no-C",jwt),HttpStatus.UNPROCESSABLE_ENTITY);
        for(var features:List.of(List.of(new FeatureInput("F1",2,7,"Valve","missing")),
                List.of(new FeatureInput("F1",2,7,"Valve","F2"),new FeatureInput("F2",15,23,"Pressure","F1")),
                List.of(new FeatureInput("F1",-1,7,"Bad span",null)))) {
            status(()->application.confirm(goal.taskId(),new Confirm(goal.taskVersion(),prepared.preparationId(),prepared.prepareSha(),0,true,"",features),"bad-C",jwt),HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(count("con5_feature_revision")).isZero();assertThat(count("con5_feature_current")).isZero();
    }
    @Test void sourceAuthorityDriftAndRevocationRejectBeforeReplayOrWrites() {
        var prepared=prepare();confirm(prepared,"confirm-C",0);
        var authority=candidate.authority();var changed=new ProductComparisonDocuments.Loaded(candidate.reference(),candidate.original(),candidate.material(),
                new ProductComparisonDocuments.Authority(authority.source(),authority.display(),
                        new ProductPatentDocumentAccess.Authority(authority.comparison().scopeId(),1,goal.taskVersion(),authority.comparison().grantId(),2),until));
        when(documents.read(anyString(),any(),any(),anyBoolean())).thenReturn(changed);
        status(()->confirm(prepared,"confirm-C",0),HttpStatus.CONFLICT);assertThat(count("con5_feature_revision")).isEqualTo(1);
        // Current authority may advance for reading the SAME immutable material; old snapshot grants are not authority.
        assertThat(application.read(goal.taskId(),store.current(goal.taskId()).revisionId(),jwt).revision().revisionNumber()).isEqualTo(1);
        when(documents.read(anyString(),any(),any(),anyBoolean())).thenThrow(new IllegalStateException("Source comparison revoked"));
        assertThatThrownBy(()->confirm(prepared,"confirm-C",0)).hasMessage("Source comparison revoked");
    }
    @Test void originalHashTaskVersionAndCrossTaskMismatchReject() {
        var prepared=prepare();
        db.update("UPDATE con3_message SET raw_text='Changed original' WHERE message_id=?",goal.messageId());
        assertThatThrownBy(()->confirm(prepared,"hash-C",0)).hasMessage("TECHNICAL_ORIGINAL_SHA_MISMATCH");
        assertThat(count("con5_feature_revision")).isZero();
        db.update("UPDATE con3_task SET version=version+1 WHERE task_id=?",goal.taskId());
        status(()->confirm(prepared,"version-C",0),HttpStatus.CONFLICT);
        status(()->application.confirm(UUID.randomUUID().toString(),confirmCommand(prepared,0),"cross-C",jwt),HttpStatus.NOT_FOUND);
    }
    @Test void finalPermissionFailureRollsBackRevisionFeaturesPointerEventTogether() {
        var prepared=prepare();
        when(access.permits(any(),any(),any())).thenAnswer(invocation->count("con5_feature_revision")==0);
        status(()->confirm(prepared,"revoked-during-C",0),HttpStatus.NOT_FOUND);
        for(String table:List.of("con5_feature_revision","con5_feature","con5_feature_current","con5_feature_event")) assertThat(count(table)).isZero();
    }
    @Test void missingEventTableRollsBackAllBusinessRows() {
        var prepared=prepare();db.execute("DROP TABLE con5_feature_event");
        assertThatThrownBy(()->confirm(prepared,"missing-table-C",0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        for(String table:List.of("con5_feature_revision","con5_feature","con5_feature_current")) assertThat(count(table)).isZero();
    }
    @Test void nativeProjectionAndFeaturePackageTamperingAreDetected() {
        var prepared=prepare();var revision=confirm(prepared,"confirm-C",0);
        db.update("UPDATE con5_feature SET span_sha=? WHERE feature_id='F1'","b".repeat(64));
        assertThatThrownBy(()->application.read(goal.taskId(),revision.revision().revisionId(),jwt)).hasMessage("Comparison persisted closure or digest invalid");
        db.update("UPDATE con5_prepared_document SET branch_json='[]'");
        assertThatThrownBy(()->confirm(prepared,"tamper-C",0)).hasMessage("Comparison persisted closure or digest invalid");
    }
    @Test void patentTargetClaimScopeAndUnavailablePurposeNeverFallBackToQuery() {
        var patent=new Prepare(command.expectedTaskVersion(),command.goalId(),1,command.inputId(),"CLAIM_ALIGNMENT",new Target(null,candidate.reference()),command.candidates());
        var prepared=application.prepare(goal.taskId(),patent,jwt);
        var confirmed=new Confirm(goal.taskVersion(),prepared.preparationId(),prepared.prepareSha(),0,true,"Patent feature",
                List.of(new FeatureInput("P1",0,8,"Exact claim phrase",null)));
        assertThat(application.confirm(goal.taskId(),confirmed,"patent-C",jwt).revision().features().getFirst().claimNumber()).isEqualTo(1);
        for(String purpose:List.of("DESIGN_ASSISTANCE","NOVELTY_ASSISTANCE")) status(()->application.prepare(goal.taskId(),
                new Prepare(goal.taskVersion(),goal.goalId(),1,goal.inputId(),purpose,command.target(),command.candidates()),jwt),HttpStatus.UNPROCESSABLE_ENTITY);
        status(()->application.prepare(goal.taskId(),new Prepare(goal.taskVersion(),goal.goalId(),1,goal.inputId(),"TECHNICAL_SIMILARITY",
                new Target(goal.messageId(),candidate.reference()),command.candidates()),jwt),HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void nonemptyReverseMigrationIsRejectedWithoutDeletingHistory() throws Exception {
        var prepared=prepare();confirm(prepared,"confirm-C",0);
        assertThatThrownBy(()->ProductSourceScopeGrantTest.script(main,"db/dm8/U094__product_comparison_feature_confirmation.sql"))
                .isInstanceOf(org.springframework.jdbc.datasource.init.ScriptException.class);
        assertThat(count("con5_feature_revision")).isEqualTo(1);assertThat(count("con5_preparation")).isEqualTo(1);
    }
    @Test void emptyReverseAndReapplyPreserveTaskAndSourceTables() throws Exception {
        ProductSourceScopeGrantTest.script(main,"db/dm8/U094__product_comparison_feature_confirmation.sql");
        assertThat(count("con3_task")).isEqualTo(1);assertThat(count("id2_grant")).isEqualTo(4);
        ProductSourceScopeGrantTest.script(main,"db/dm8/V094__product_comparison_feature_confirmation.sql");
        assertThat(count("con5_preparation")).isZero();
    }
    static void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
