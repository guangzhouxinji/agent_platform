package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.*;
import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static org.assertj.core.api.Assertions.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Production SAVE application over real independent SQL/identity stores and owned component permissions. */
class ProductPromptSaveApplicationTest {
    @TempDir Path root;
    ProductPromptCreateApplicationTest.Fixture f;
    Receipt created;
    final List<Map<String,String>> permissions=new ArrayList<>();
    String directorySha;

    @BeforeEach void setup()throws Exception{
        f=new ProductPromptCreateApplicationTest.Fixture(root);
        migrate(f.base.authoritySource,"identity-authority/V009__product_prompt_save_request.sql");
        created=f.app().request(f.base.token,f.base.workspaceId,"same-key",f.input.command);
        f.base.grant(Action.SAVE);f.mainSource.statements.clear();
    }

    @Test void saveOnlyAppendsNewRevisionAndCreateSameTextKeyRemainsIndependent()throws Exception{
        var command=permit(created,"member","Full new draft {{ratio}}");
        Receipt saved=app().request(f.base.token,created.promptId(),"same-key",command);
        assertThat(saved.operation()).isEqualTo(Operation.SAVE);assertThat(saved.generation()).isEqualTo(2);
        assertThat(saved.promptId()).isEqualTo(created.promptId());assertThat(saved.revisionId()).isNotEqualTo(created.revisionId());
        assertThat(app().lookup(f.base.token,f.base.workspaceId,"same-key")).isEqualTo(saved);
        assertThat(app().request(f.base.token,created.promptId(),"same-key",command)).isEqualTo(saved);
        assertThat(f.app().lookup(f.base.token,f.base.workspaceId,"same-key")).isEqualTo(created);
        assertThat(f.mainCount("conp_prompt")).isEqualTo(1);assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.mainCount("conp_event")).isEqualTo(2);assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
        assertThat(f.mainSource.timeouts).isNotEmpty().allMatch(n->n>=1&&n<=5);
        assertThat(f.authoritySource.timeouts).isNotEmpty().allMatch(n->n>=1&&n<=5);
    }

    @Test void differentSaveOnlyActorPreservesOriginalCreatorWithoutReadingOldBody()throws Exception{
        String writerToken=writer("writer-b");f.base.now=f.base.now.plusSeconds(5);
        var command=permit(created,"writer-b","B supplied complete new draft {{ratio}}");
        Receipt saved=app().request(writerToken,created.promptId(),"writer-save",command);
        assertThat(saved.subject()).isEqualTo("writer-b");
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
        var provenance=f.base.mapper.readTree(f.base.db.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,saved.revisionId())).get("provenance");
        assertThat(provenance.get("createdBy").asText()).isEqualTo("member");
        assertThat(Instant.parse(provenance.get("createdAt").asText())).isEqualTo(created.submittedAt());
        assertThat(provenance.get("modifiedBy").asText()).isEqualTo("writer-b");
        assertThat(Instant.parse(provenance.get("modifiedAt").asText())).isEqualTo(saved.submittedAt());
        assertThat(provenance.get("changeDescription").asText()).isEqualTo("SAVE_DRAFT");
        assertThatThrownBy(()->app().lookup(f.base.token,f.base.workspaceId,"writer-save")).isInstanceOf(ProductPromptAccessDenied.class);
    }

    @Test void expiredOldBodyDoesNotBlockApprovedNewFullDraftOrExtendOldDeadlines()throws Exception{
        var old=f.base.db.queryForMap("SELECT body_until,audit_until,record_sha,revision_json FROM conp_revision WHERE revision_id=?",created.revisionId());
        Instant oldBody=((Timestamp)old.get("BODY_UNTIL")).toInstant();
        f.base.now=f.base.now.plusSeconds(10);
        var successor=permit(created,"member","Still live draft {{ratio}}");
        Receipt live=app().request(f.base.token,created.promptId(),"live-successor",successor);
        f.base.now=oldBody;
        var command=permit(live,"member","Entire fresh draft {{ratio}}");
        Receipt saved=app().request(f.base.token,created.promptId(),"expired-old-body",command);
        assertThat(saved.generation()).isEqualTo(3);
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
        assertThat(f.base.db.queryForMap("SELECT body_until,audit_until,record_sha,revision_json FROM conp_revision WHERE revision_id=?",created.revisionId())).isEqualTo(old);
        assertThat(f.base.db.queryForObject("SELECT body_until FROM conp_revision WHERE revision_id=?",Timestamp.class,saved.revisionId()).toInstant()).isAfter(f.base.now);
    }

    @Test void independentReserveSurvivesMainRollbackAndResumesExactOriginalIdsTimesAndLimits()throws Exception{
        var command=permit(created,"member","Recovered full draft {{ratio}}");
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"before",command)).isInstanceOf(RuntimeException.class);
        assertThat(f.mainSource.faultTriggered).isTrue();assertThat(f.mainCount("conp_revision")).isEqualTo(1);
        var original=savedPrepared();
        assertThatThrownBy(()->app().lookup(f.base.token,f.base.workspaceId,"before")).isInstanceOf(IllegalStateException.class);
        f.base.now=f.base.now.plusSeconds(20);
        Receipt saved=app().request(f.base.token,created.promptId(),"before",command);
        assertThat(savedPrepared()).isEqualTo(original);
        assertThat(saved.id()).isEqualTo(original.get("RECEIPT_ID"));assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
    }

    @Test void mainCommitWithLostReadySettlesAfterIntentAndNewBodyExpireUsingScalarsOnly()throws Exception{
        var command=permit(created,"member","Committed full draft {{ratio}}");
        f.authoritySource.failOnce(sql->sql.startsWith("INSERT INTO idp_revision_witness"));
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"after",command)).isInstanceOf(RuntimeException.class);
        assertThat(f.authoritySource.faultTriggered).isTrue();assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
        var original=savedPrepared();
        var mainReceipts=f.base.db.queryForList("SELECT * FROM conp_receipt ORDER BY generation");
        var head=f.base.db.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",created.promptId());
        f.base.now=f.base.now.plusSeconds(601);f.mainSource.statements.clear();
        assertThat(((Timestamp)head.get("CONTAINER_UNTIL")).toInstant()).isBefore(f.base.now);
        Receipt saved=app().lookup(f.base.token,f.base.workspaceId,"after");
        assertThat(saved.generation()).isEqualTo(2);assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(2);
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("SELECT revision_json"));
        assertThat(app().request(f.base.token,created.promptId(),"after",command)).isEqualTo(saved);
        assertThat(savedPrepared()).isEqualTo(original);
        assertThat(f.base.db.queryForList("SELECT * FROM conp_receipt ORDER BY generation")).isEqualTo(mainReceipts);
        assertThat(f.base.db.queryForMap("SELECT * FROM conp_prompt WHERE prompt_id=?",created.promptId())).isEqualTo(head);
    }

    @Test void expiredAllBeforeIntentCannotRebuildAndDifferentKeyCannotTakePendingControl()throws Exception{
        var command=permit(created,"member","Never committed {{ratio}}");
        f.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"pending",command)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"takeover",command)).isInstanceOf(IllegalStateException.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);
        f.base.now=f.base.now.plusSeconds(300);
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"pending",command)).isInstanceOf(IllegalStateException.class);
        assertThat(f.mainCount("conp_revision")).isEqualTo(1);assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"generation","revision","kind"})
    void explicitWrongBaseIsTypedConflictBeforePermissionOrReserve(String fault)throws Exception{
        var c=permit(created,"member","New draft {{ratio}}");
        var wrong=new ProductPromptSaveCommand(fault.equals("generation")?2:c.expectedGeneration(),fault.equals("revision")?id():c.expectedRevisionId(),
                fault.equals("kind")?PromptRevision.Kind.GUIDE:c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"wrong-base",wrong)).isInstanceOf(ProductPromptSaveBaseConflict.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    @Test void oldSaveKeyRemainsRecoverableAfterSuccessorAndDoesNotMoveHeadBack()throws Exception{
        var first=permit(created,"member","First full draft {{ratio}}");
        Receipt one=app().request(f.base.token,created.promptId(),"save-one",first);
        f.base.now=f.base.now.plusSeconds(1);
        var second=permit(one,"member","Second full draft {{ratio}}");
        Receipt two=app().request(f.base.token,created.promptId(),"save-two",second);
        assertThat(app().lookup(f.base.token,f.base.workspaceId,"save-one")).isEqualTo(one);
        assertThat(app().request(f.base.token,created.promptId(),"save-one",first)).isEqualTo(one);
        assertThat(f.base.db.queryForObject("SELECT head_revision_id FROM conp_prompt WHERE prompt_id=?",String.class,created.promptId())).isEqualTo(two.revisionId());
        assertThat(f.authority.queryForObject("SELECT head_revision_id FROM idp_prompt_control WHERE prompt_id=?",String.class,created.promptId())).isEqualTo(two.revisionId());
        assertThat(f.mainCount("conp_revision")).isEqualTo(3);
    }

    @Test void originalKeyRejectsDifferentContentPromptOrCasBeforeFreshPermission()throws Exception{
        var c=permit(created,"member","First full draft {{ratio}}");
        app().request(f.base.token,created.promptId(),"same-save",c);
        var changed=new ProductPromptSaveCommand(c.expectedGeneration(),c.expectedRevisionId(),c.kind(),c.name(),c.body()+" changed",c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"same-save",changed)).isInstanceOf(ProductPromptCreateConflict.class);
        var changedCas=new ProductPromptSaveCommand(2,id(),c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"same-save",changedCas)).isInstanceOf(ProductPromptCreateConflict.class);
        Receipt another=f.app().request(f.base.token,f.base.workspaceId,"another-create",f.input.command);
        assertThatThrownBy(()->app().request(f.base.token,another.promptId(),"same-save",c)).isInstanceOf(ProductPromptCreateConflict.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(3);
    }

    @Test void createPermissionCannotAuthorizeSaveAndRevokedSaveCannotRecover()throws Exception{
        var c=f.input.command;
        var wrong=new ProductPromptSaveCommand(1,created.revisionId(),c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
        var deniedApp=new ProductPromptSaveApplication(f.store,f.control,f.requests,f.authorization(),f.input.adapter(),f.registry,f.mainManager,f.authorityManager,f.base.clock);
        assertThatThrownBy(()->deniedApp.request(f.base.token,created.promptId(),"not-save-licensed",wrong)).isInstanceOf(IllegalStateException.class);
        var valid=permit(created,"member","Full draft {{ratio}}");app().request(f.base.token,created.promptId(),"saved",valid);
        f.base.revoke(Action.SAVE);
        assertThatThrownBy(()->app().lookup(f.base.token,f.base.workspaceId,"saved")).isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(f.mainCount("conp_revision")).isEqualTo(2);
    }

    @Test void corruptFirstWitnessOrHeadCannotCreateNewPrepared()throws Exception{
        var c=permit(created,"member","Full draft {{ratio}}");
        f.authority.update("UPDATE idp_revision_witness SET subject_id='other' WHERE revision_id=?",created.revisionId());
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"corrupt-origin",c)).isInstanceOf(IllegalStateException.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
        f.authority.update("UPDATE idp_revision_witness SET subject_id='member' WHERE revision_id=?",created.revisionId());
        f.base.db.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=?","f".repeat(64),created.promptId());
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"corrupt-head",c)).isInstanceOf(IllegalStateException.class);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
    }

    @Test void concurrentDifferentKeysAtSameBaseProduceOnlyOneNewRevision()throws Exception{
        var c=permit(created,"member","Concurrent complete draft {{ratio}}");
        var application=app();ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Object>> attempts=new ArrayList<>();
            for(String key:List.of("race-a","race-b"))attempts.add(workers.submit(()->{start.await();try{return application.request(f.base.token,created.promptId(),key,c);}catch(RuntimeException failure){return failure;}}));
            start.countDown();var results=List.of(attempts.get(0).get(20,TimeUnit.SECONDS),attempts.get(1).get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(Receipt.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(RuntimeException.class::isInstance).count()).isEqualTo(1);
            assertThat(f.mainCount("conp_revision")).isEqualTo(2);assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);
        }finally{workers.shutdownNow();}
    }

    @Test void grantExpiryDuringIndependentReadyRollsBackWitnessAndRequestTogether()throws Exception{
        var c=permit(created,"member","New draft {{ratio}}");Instant cutoff=f.base.now.plusSeconds(10);
        f.base.watermark.change(()->f.base.db.update("UPDATE id2_grant SET expires_at=? WHERE grant_id=?",Timestamp.from(cutoff),f.base.receipts.get(f.base.workspaceId+":SAVE").grantId()));
        f.authoritySource.afterSql=sql->{if(sql.startsWith("INSERT INTO idp_revision_witness"))f.base.now=cutoff;};
        assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"grant-deadline",c)).isInstanceOf(RuntimeException.class);
        assertThat(f.base.now).isEqualTo(cutoff);assertThat(f.mainCount("conp_revision")).isEqualTo(2);
        assertThat(f.authorityCount("idp_revision_witness")).isEqualTo(1);
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_request WHERE operation='SAVE'",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,created.promptId())).isEqualTo("PENDING");
    }

    @Test void callerOwnedTransactionIsRejectedBeforeLocateOrReserve()throws Exception{
        var c=permit(created,"member","New draft {{ratio}}");
        new TransactionTemplate(f.mainManager).executeWithoutResult(status->{
            assertThatThrownBy(()->app().request(f.base.token,created.promptId(),"nested",c)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->app().lookup(f.base.token,f.base.workspaceId,"nested")).isInstanceOf(IllegalStateException.class);
        });
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);
    }

    @Test void exactWorkspaceOverloadSavesAndReplaysThroughTheSameOriginalKey()throws Exception{
        var command=permit(created,"member","Scoped full draft {{ratio}}");
        var saved=app().request(f.base.token,f.base.workspaceId,created.promptId(),"scoped-save",command);
        assertThat(saved.workspaceId()).isEqualTo(f.base.workspaceId);assertThat(saved.generation()).isEqualTo(2);
        assertThat(app().request(f.base.token,f.base.workspaceId,created.promptId(),"scoped-save",command)).isEqualTo(saved);
        assertThat(app().lookup(f.base.token,f.base.workspaceId,"scoped-save")).isEqualTo(saved);
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(2);assertThat(f.mainCount("conp_revision")).isEqualTo(2);
    }

    @Test void wrongWorkspaceIsDeniedBeforeAnySaveWriteOrReservation()throws Exception{
        var command=permit(created,"member","Scoped full draft {{ratio}}");var application=app();
        f.mainSource.statements.clear();f.authoritySource.statements.clear();
        assertThatThrownBy(()->application.request(f.base.token,id(),created.promptId(),"wrong-workspace",command)).isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(f.mainSource.statements).noneMatch(s->s.startsWith("INSERT ")||s.startsWith("UPDATE ")||s.startsWith("DELETE "));
        assertThat(f.authoritySource.statements).noneMatch(s->s.startsWith("INSERT ")||s.startsWith("UPDATE ")||s.startsWith("DELETE "));
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);assertThat(f.mainCount("conp_revision")).isEqualTo(1);
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control WHERE prompt_id=?",String.class,created.promptId())).isEqualTo("READY");
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"","not-a-uuid","11111111-1111-4111-8111-11111111111Z","AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"})
    void exactWorkspaceOverloadRejectsInvalidOrAbsentUuidBeforeLocate(String expectedWorkspace)throws Exception{
        var command=permit(created,"member","Scoped full draft {{ratio}}");var application=app();
        f.mainSource.statements.clear();f.authoritySource.statements.clear();
        assertThatThrownBy(()->application.request(f.base.token,expectedWorkspace,created.promptId(),"invalid-workspace",command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.mainSource.statements).isEmpty();assertThat(f.authoritySource.statements).isEmpty();
        assertThat(f.authorityCount("idp_prompt_request")).isEqualTo(1);assertThat(f.mainCount("conp_revision")).isEqualTo(1);
    }

    Map<String,Object> savedPrepared(){return f.authority.queryForMap("SELECT prompt_id,expected_generation,expected_revision_id,target_revision_id,receipt_id,prepared_at,body_until,audit_until,prepared_sha FROM idp_prompt_request WHERE operation='SAVE'");}
    ProductPromptSaveCommand permit(Receipt base,String subject,String body)throws Exception{
        var c=f.input.command;String source=id(),ref="save-input-"+source+".json";
        var command=new ProductPromptSaveCommand(base.generation(),base.revisionId(),c.kind(),"Saved draft",body,c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),c.variables(),source);
        var permission=new TreeMap<String,Object>(f.input.permission);
        permission.put("permissionId",id());permission.put("operation","SAVE");permission.put("subject",subject);
        permission.put("contentSha256",command.contentSha());permission.put("generationSourceRef","source:新稿/"+source);
        permission.put("validFrom",f.base.now.minusSeconds(1));permission.put("validUntil",f.base.now.plusSeconds(1500));
        permission.put("bodyUntil",f.base.now.plusSeconds(600));permission.put("auditUntil",f.base.now.plusSeconds(1200));
        String sha=f.base.write(ref,permission);permissions.add(Map.of("sourceId",source,"permissionRef",ref,"permissionSha",sha));
        directorySha=f.base.write("save-input-directory.json",Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",permissions));
        return command;
    }
    ProductPromptSaveApplication app(){
        var input=new ProductPromptInputPermission(new ProductSourceEvidenceFiles(root,1048576),"save-input-directory.json",directorySha,f.base.clock,ISSUER);
        return new ProductPromptSaveApplication(f.store,f.control,f.requests,f.authorization(),input,f.registry,f.mainManager,f.authorityManager,f.base.clock);
    }
    private String writer(String subject){
        String sid=id(),jti=id(),kid=f.base.db.queryForObject("SELECT active_kid FROM id2_key_control WHERE issuer=?",String.class,ISSUER);
        f.base.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,'encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER,subject,subject);
        String token=new NimbusJwtEncoder(f.base.keys).encode(JwtEncoderParameters.from(JwtClaimsSet.builder().issuer(ISSUER).subject(subject).audience(List.of(AUDIENCE))
                .issuedAt(f.base.now).notBefore(f.base.now.minusSeconds(1)).expiresAt(f.base.now.plusSeconds(3600)).id(jti).claim("sid",sid).build())).getTokenValue();
        f.base.db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)",sid,jti,ISSUER,subject,"registered",kid,Timestamp.from(f.base.now),Timestamp.from(f.base.now.plusSeconds(3600)),"ACTIVE",null);
        String scope="PROMPT_WORKSPACE:"+f.base.workspaceId+":1";
        f.base.watermark.change(()->f.base.grants.issue(new ProductGrants.Scope(ISSUER,subject,f.base.organization,"CONSULTATION",scope,scope,"PROMPT_WORKSPACE",f.base.workspaceId+":SAVE",1,"WRITE","TASK_WORK"),
                f.base.now.plusSeconds(1700),0,"component-maintainer",id(),ProductComparisonJson.hash(id())));
        return token;
    }
}
