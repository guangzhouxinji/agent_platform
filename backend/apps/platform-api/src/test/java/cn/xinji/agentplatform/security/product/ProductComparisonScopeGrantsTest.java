package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Mechanical H2/authority C. These identities/decisions are never registered in a product runtime. */
class ProductComparisonScopeGrantsTest {
    private static final String ISSUER="https://identity.example.org", HASH="a".repeat(64);
    private static final String EMPTY="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private JdbcTemplate db;
    private ProductGrantWatermark watermark;
    private ProductGrants grants;
    private ProductComparisonScopeGrants service;
    private ProductComparisonScopeGrants.Input input;
    private ProductMaintenanceProof maintenance;

    @BeforeEach void setup() throws Exception { setup(false); }
    void setup(boolean modelUse) throws Exception {
        var main=ProductSourceScopeGrantTest.database(); var independent=ProductSourceScopeGrantTest.database();
        for (String name:new String[]{"V032__product_identity.sql","V034__product_consultation_initial_task.sql",
                "V036__product_source_candidates.sql","V037__product_source_admission.sql",
                "V038__product_task_source_scope.sql","V039__product_source_verification.sql",
                "V041__product_task_authorization_version.sql","V042__product_consultation_goal.sql",
                "V046__product_workload_subject.sql",
                "V053__product_source_use_decision.sql","V056__product_query_only_source_scope.sql",
                "V092__product_source_comparison_use.sql"})
            ProductSourceScopeGrantTest.script(main,"db/dm8/"+name);
        ProductSourceScopeGrantTest.script(independent,"db/identity-authority/V001__grant_watermark.sql");
        ProductSourceScopeGrantTest.script(independent,"db/identity-authority/V003__consultation_source_watermark.sql");
        db=new JdbcTemplate(main); var authority=new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,EMPTY);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) "
                + "VALUES(?,'member','mechanical-C-only','encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(main));
        watermark=new ProductGrantWatermark(db,authority,ISSUER);
        grants=new ProductGrants(db,tx,Clock.systemUTC());
        var directory=new JdbcProductTaskDirectory(db,Clock.systemUTC());
        var initial=new ProductInitialTaskService(directory,grants,watermark,tx,new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(),ISSUER,new ProductRetentionPolicy("mechanical-C-retention",1,HASH,Duration.ofHours(2)));
        Instant until=Instant.now().plusSeconds(1800);
        var task=initial.create(new ProductInitialTaskService.Input("organization","Mechanical C", "member",
                "mechanical-C-retention",1,until),"task-C","mechanical-C-maintainer","organization");
        // State is a mechanical precondition for this command C, not a claimed goal-confirmation product I.
        db.update("UPDATE con3_task SET state='IN_PROGRESS' WHERE task_id=?",task.taskId());
        var sources=new ProductSourceWatermark(db,authority,tx);
        sources.change("source-1",intent->{
            ProductSourceScopeGrantTest.admit(db,until,task.taskId(),task.dataScope().split(":")[0],intent);
            db.update("INSERT INTO con4_source_field_use VALUES('11111111-1111-4111-8111-111111111111','CLAIMS','COMPARISON',?)",Timestamp.from(until));
            if(modelUse) db.update("INSERT INTO con4_source_field_use VALUES('11111111-1111-4111-8111-111111111111','CLAIMS','MODEL',?)",Timestamp.from(until));
            db.update("INSERT INTO con4_source_verification VALUES(?,'source-1','contract-1','capture-1',1,'mechanical-C-only',"
                    + "?,?,?,1,1,'mechanical-C-verifier',CURRENT_TIMESTAMP)",java.util.UUID.randomUUID().toString(),HASH,HASH,HASH);
            return null;
        });
        var scopes=new ProductSourceScopeGrants(db,tx,directory,grants,watermark,sources,null,Clock.systemUTC(),ISSUER);
        var scope=scopes.authorizeVerified(new ProductSourceScopeGrants.Input(task.taskId(),"member","source-1",
                task.version(),1,until,"query-C"),"mechanical-C-maintainer","organization");
        input=new ProductComparisonScopeGrants.Input(task.taskId(),"member",task.version(),1,1,
                scope.scopeId(),scope.scopeVersion(),Set.of("CLAIMS"),until.minusSeconds(60),0);
        maintenance=mock(ProductMaintenanceProof.class);
        service=new ProductComparisonScopeGrants(db,tx,directory,grants,watermark,sources,
                new ProductSourceAvailability(db,sources,Clock.systemUTC()),maintenance,Clock.systemUTC(),ISSUER);
    }
    private ProductGrants.Receipt issue(String request) {
        return service.authorizeVerified("source-1",input,request,"mechanical-C-maintainer","organization");
    }
    @Test void exactGrantAndReplayAreAuditedWithoutAddingOtherUses() {
        int before=db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class);
        var receipt=issue("compare-C"); assertThat(receipt.status()).isEqualTo("ACTIVE");
        assertThat(issue("compare-C")).isEqualTo(receipt);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isEqualTo(before+1);
        assertThat(db.queryForObject("SELECT action_code || ':' || purpose FROM id2_grant WHERE grant_id=?",
                String.class,receipt.grantId())).isEqualTo("COMPARE:COMPARISON");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant_event WHERE request_id='compare-C'",Integer.class)).isEqualTo(1);
        watermark.assertCurrent();
    }
    @Test void crossOrganizationOldTaskAndSourceVersionsRejectWithoutPartialGrant() {
        int before=db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class);
        assertThatThrownBy(()->service.authorizeVerified("source-1",input,"cross-C","maintainer","other-org"))
                .isInstanceOf(IllegalStateException.class);
        var old=new ProductComparisonScopeGrants.Input(input.taskId(),input.memberSubject(),input.taskVersion()+1,
                1,1,input.scopeId(),input.scopeVersion(),input.requiredFields(),input.expiresAt(),0);
        assertThatThrownBy(()->service.authorizeVerified("source-1",old,"old-C","maintainer","organization"))
                .isInstanceOf(IllegalStateException.class);
        var oldSource=new ProductComparisonScopeGrants.Input(input.taskId(),input.memberSubject(),input.taskVersion(),
                2,1,input.scopeId(),input.scopeVersion(),input.requiredFields(),input.expiresAt(),0);
        assertThatThrownBy(()->service.authorizeVerified("source-1",oldSource,"source-C","maintainer","organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM id2_grant",Integer.class)).isEqualTo(before);
        watermark.assertCurrent();
    }
    @Test void disabledMemberAndRevokedGrantCannotReplay() {
        var receipt=issue("compare-C");
        watermark.change(()->{db.update("UPDATE id2_subject SET status='DISABLED' WHERE issuer=? AND subject_id='member'",ISSUER);return null;});
        assertThatThrownBy(()->issue("compare-C")).isInstanceOf(IllegalStateException.class);
        watermark.change(()->{db.update("UPDATE id2_subject SET status='ACTIVE' WHERE issuer=? AND subject_id='member'",ISSUER);return null;});
        watermark.change(()->grants.revoke(receipt.grantId(),1,"maintainer","revoke-C",HASH));
        assertThatThrownBy(()->issue("compare-C")).hasMessage("Replayed grant is no longer current");
        watermark.assertCurrent();
    }
    @Test void unapprovedFieldAndExpiredInputReject() {
        var field=new ProductComparisonScopeGrants.Input(input.taskId(),"member",input.taskVersion(),1,1,
                input.scopeId(),1,Set.of("TITLE","CLAIMS"),input.expiresAt(),0);
        assertThatThrownBy(()->service.authorizeVerified("source-1",field,"fields-C","maintainer","organization"))
                .isInstanceOf(IllegalStateException.class);
        var expired=new ProductComparisonScopeGrants.Input(input.taskId(),"member",input.taskVersion(),1,1,
                input.scopeId(),1,Set.of("CLAIMS"),Instant.now().minusSeconds(1),0);
        assertThatThrownBy(()->service.authorizeVerified("source-1",expired,"expired-C","maintainer","organization"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replayWithChangedExpiryConflicts() {
        issue("compare-C");
        var changed=new ProductComparisonScopeGrants.Input(input.taskId(),"member",input.taskVersion(),1,1,
                input.scopeId(),1,Set.of("CLAIMS"),input.expiresAt().minusSeconds(1),0);
        assertThatThrownBy(()->service.authorizeVerified("source-1",changed,"compare-C","mechanical-C-maintainer","organization"))
                .hasMessage("Request id conflict");
    }
    @Test void bothMaintenanceRolesAndOrganizationsMustBeTheSame() {
        var request=new MockHttpServletRequest();
        when(maintenance.require(request,ProductMaintenanceProof.Role.SOURCES)).thenReturn("actor-C");
        when(maintenance.requireOrganization(request,ProductMaintenanceProof.Role.SOURCES)).thenReturn("organization");
        when(maintenance.require(request,ProductMaintenanceProof.Role.GRANTS)).thenReturn("other-actor-C");
        assertThatThrownBy(()->service.authorize("source-1",input,"roles-C",request)).isInstanceOf(SecurityException.class);
        when(maintenance.require(request,ProductMaintenanceProof.Role.GRANTS)).thenReturn("actor-C");
        when(maintenance.requireOrganization(request,ProductMaintenanceProof.Role.GRANTS)).thenReturn("other-org");
        assertThatThrownBy(()->service.authorize("source-1",input,"roles-C",request)).isInstanceOf(SecurityException.class);
        when(maintenance.requireOrganization(request,ProductMaintenanceProof.Role.GRANTS)).thenReturn("organization");
        assertThat(service.authorize("source-1",input,"roles-C",request).status()).isEqualTo("ACTIVE");
    }
    @Test void nullOrUnimplementedFieldIsAnInputErrorNotAnAuthorizationBypass() {
        var invalidFields=new java.util.HashSet<String>(); invalidFields.add(null);
        for (Set<String> fields:java.util.List.of(invalidFields,Set.of("DESCRIPTION"))) {
            var invalid=new ProductComparisonScopeGrants.Input(input.taskId(),"member",input.taskVersion(),1,1,
                    input.scopeId(),1,fields,input.expiresAt(),0);
            assertThatThrownBy(()->service.authorizeVerified("source-1",invalid,"invalid-fields-C","maintainer","organization"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void modelDisclosureNeedsItsOwnApprovedUseAndDoesNotReplaceComparison() throws Exception {
        assertThatThrownBy(()->service.authorizeModelInputVerified("source-1",input,"model-missing","maintainer","organization")).isInstanceOf(IllegalStateException.class);
        // Separate approved fixture: do not tamper with independently sealed source history.
        setup(true);
        var compare=issue("compare-before-model");
        var model=service.authorizeModelInputVerified("source-1",input,"model-exact","maintainer","organization");
        assertThat(model.grantId()).isNotEqualTo(compare.grantId());
        assertThat(db.queryForObject("SELECT purpose FROM id2_grant WHERE grant_id=?",String.class,model.grantId())).isEqualTo("MODEL_INPUT");
        assertThat(service.authorizeModelInputVerified("source-1",input,"model-exact","maintainer","organization")).isEqualTo(model);
        assertThat(issue("compare-before-model")).isEqualTo(compare);
        db.update("DELETE FROM con4_source_field_use WHERE use_code='MODEL'");
        assertThatThrownBy(()->service.authorizeModelInputVerified("source-1",input,"model-exact","maintainer","organization")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->issue("compare-before-model")).hasMessage("Source history differs from independent authority");
        watermark.assertCurrent();
    }
}
