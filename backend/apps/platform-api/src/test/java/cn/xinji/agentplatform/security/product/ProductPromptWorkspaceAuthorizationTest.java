package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ProductPromptWorkspaceAuthorizationTest {
    @TempDir Path originals;
    ProductPromptWorkspaceAuthorizationFixture f;
    @BeforeEach void setup()throws Exception{f=new ProductPromptWorkspaceAuthorizationFixture(originals);}

    @ParameterizedTest @EnumSource(value=Action.class,names={"CREATE","READ","SAVE"})
    void authenticatesExactSqlWorkspaceActionUsingSignedJwt(Action action){
        f.grant(action);
        String subject=f.authorization().execute(f.token,f.workspaceId,action,lease->{
            assertThat(lease.workspace(f.workspaceId).organization()).isEqualTo(f.organization);
            assertThat(lease.action()).isEqualTo(action);lease.requireCurrent();return lease.subject();
        });
        assertThat(subject).isEqualTo("member");assertThat(f.bodyReads.get()).isZero();
    }
    @Test void previewRequiresBothGrantsButDoesNotClaimTestAdmission(){
        f.grant(Action.PREVIEW);refused(f.authorization(),Action.PREVIEW);
        f.grant(Action.READ);
        Boolean admitted=f.authorization().execute(f.token,f.workspaceId,Action.PREVIEW,lease->lease.testAdmitted());
        assertThat(admitted).isFalse();
    }
    @Test void copyRequiresSourceReadAndTargetCopyAndSamePersistedOrganization()throws Exception{
        String target=id();f.addWorkspace(target,f.organization);f.grant(Action.READ);f.grant(target,Action.CREATE);
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,target,lease->{f.bodyAttempt();return null;})).isInstanceOf(RuntimeException.class);
        assertThat(f.bodyReads.get()).isZero();f.grant(target,Action.COPY);
        String subject=f.authorization().executeCopy(f.token,f.workspaceId,target,lease->lease.subject());
        assertThat(subject).isEqualTo("member");
        String foreign=id();f.addWorkspace(foreign,id());f.grant(foreign,Action.COPY);
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,foreign,lease->{f.bodyAttempt();return null;})).isInstanceOf(RuntimeException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @Test void createCannotReadOrSaveAndTaskPermissionCannotRead(){
        f.grant(Action.CREATE);refused(f.authorization(),Action.READ);refused(f.authorization(),Action.SAVE);
        f.taskOnlyGrant();refused(f.authorization(),Action.READ);
    }
    @Test void readCannotPreviewAndGrantForOtherWorkspaceCannotSubstitute()throws Exception{
        f.grant(Action.READ);refused(f.authorization(),Action.PREVIEW);
        String other=id();f.addWorkspace(other,f.organization);f.grant(other,Action.SAVE);refused(f.authorization(),Action.SAVE);
    }
    @ParameterizedTest @ValueSource(strings={"service","session","client","organization","grant","epoch-mismatch","scope-version","pending","closed","main-drift","registry-drift","missing-independent"})
    void rejectsCurrentSqlDriftBeforeAnyBodyRead(String fault){
        f.grant(Action.READ);
        switch(fault){
            case "service"->f.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");
            case "session"->f.db.update("UPDATE id2_session SET status='REVOKED'");
            case "client"->f.db.update("UPDATE id2_client_current SET status='REVOKED'");
            case "organization"->f.db.update("UPDATE con3_organization SET status='DISABLED'");
            case "grant"->f.revoke(Action.READ);
            case "epoch-mismatch"->f.watermark.change(()->{f.db.update("UPDATE id2_grant SET grant_generation=grant_generation+1");return null;});
            case "scope-version"->f.db.update("UPDATE conp_workspace SET scope_version=2");
            case "pending"->f.independent.update("UPDATE idp_workspace SET control_state='PENDING',registry_intent_id=?",id());
            case "closed"->f.independent.update("UPDATE idp_workspace SET state='CLOSED'");
            case "main-drift"->f.db.update("UPDATE conp_workspace SET policy_sha=?","a".repeat(64));
            case "registry-drift"->f.independent.update("UPDATE idp_workspace SET registry_sha=?","b".repeat(64));
            case "missing-independent"->f.independent.update("DELETE FROM idp_workspace");
            default->throw new AssertionError(fault);
        }
        refused(f.authorization(),Action.READ);
    }
    @Test void forgedSignatureWrongIssuerAndWrongAudienceNeverEnterCallback(){
        f.grant(Action.READ);var authorization=f.authorization();String[] parts=f.token.split("\\.");
        parts[2]=(parts[2].startsWith("A")?"B":"A")+parts[2].substring(1);
        refusedToken(authorization,String.join(".",parts));
        String kid=f.decoder.decode(f.token).getHeaders().get("kid").toString();f.db.update("DELETE FROM id2_session");
        String wrongIssuer=f.token(kid,"https://foreign.example.org",ProductPromptWorkspaceAuthorizationFixture.AUDIENCE);
        refusedToken(authorization,wrongIssuer);
        f.db.update("DELETE FROM id2_session");String wrongAudience=f.token(kid,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"https://foreign-api.example.org");
        refusedToken(authorization,wrongAudience);
    }
    @ParameterizedTest @ValueSource(strings={"unknown","missing","duplicate-operation","wrong-scope","wrong-coverage","inactive","future","expired","coerced-version","fractional-version","epoch-time","offset-time","array-date","unapproved-operation","duplicate-key"})
    void rejectsStrictApprovalEvenWhenOriginalHashAndBothRegistriesMatch(String fault)throws Exception{
        f.grant(Action.READ);
        if("duplicate-key".equals(fault)){
            Binding binding=f.bindings.get(f.workspaceId);String raw=Files.readString(originals.resolve(binding.approvalRef()));
            f.installApproval(raw.substring(0,raw.length()-1)+",\"status\":\"ACTIVE\"}");
        }else f.alterApproval(value->{switch(fault){
            case "unknown"->value.put("authority",true);
            case "missing"->value.remove("responsibleParty");
            case "duplicate-operation"->value.put("operations",List.of("READ","READ"));
            case "wrong-scope"->value.put("workspaceId",id());
            case "wrong-coverage"->value.put("contentCoverage","ANY_DOCUMENT");
            case "inactive"->value.put("status","REVOKED");
            case "future"->value.put("validFrom",f.now.plusSeconds(1));
            case "expired"->value.put("validUntil",f.now);
            case "coerced-version"->value.put("scopeVersion","1");
            case "fractional-version"->value.put("scopeVersion",new java.math.BigDecimal("1.5"));
            case "epoch-time"->value.put("validFrom",f.now.getEpochSecond());
            case "offset-time"->value.put("validFrom",f.now.minusSeconds(1).atOffset(java.time.ZoneOffset.ofHours(8)).toString());
            case "array-date"->value.put("decisionDate",List.of(2026,10,13));
            case "unapproved-operation"->value.put("operations",List.of("CREATE"));
            default->throw new AssertionError(fault);
        }});
        refused(f.authorization(),Action.READ);
    }
    @ParameterizedTest @ValueSource(strings={"body","audit","backup","fractional-days","policy-scope","policy-owner","policy-expiry"})
    void refusesExpandedOrUncoveredPolicyEvenWithExactApprovedHash(String fault)throws Exception{
        f.grant(Action.READ);f.alterPolicy(value->{switch(fault){
            case "body"->value.put("bodyMaxDays",91);
            case "audit"->value.put("auditMaxDays",181);
            case "backup"->value.put("backupMaxDays",30);
            case "fractional-days"->value.put("bodyMaxDays",new java.math.BigDecimal("90.5"));
            case "policy-scope"->value.put("organization",id());
            case "policy-owner"->value.put("responsibleParty","");
            case "policy-expiry"->value.put("validUntil",f.now);
            default->throw new AssertionError(fault);
        }});refused(f.authorization(),Action.READ);
    }
    @Test void missingWrongShaAndMissingBindingAreNotAnAllowPort()throws Exception{
        f.grant(Action.READ);Binding binding=f.bindings.get(f.workspaceId);
        var wrong=new Binding(binding.workspaceId(),binding.organization(),1,binding.approvalRef(),"a".repeat(64),binding.policyRef(),binding.policySha());
        refused(f.authorization(List.of(wrong)),Action.READ);
        Files.delete(originals.resolve(binding.policyRef()));refused(f.authorization(),Action.READ);
        String other=id();f.addWorkspace(other,f.organization);refused(f.authorization(List.of(f.bindings.get(other))),Action.READ);
    }
    @ParameterizedTest @ValueSource(strings={"original","independent","session","grant-deadline","watermark","workspace-deadline","organization"})
    void terminalChangesRollBackCallbackEffects(String fault){
        f.grant(Action.READ);var authorization=f.authorization();
        assertThatThrownBy(()->authorization.execute(f.token,f.workspaceId,Action.READ,lease->{
            f.writeObservation();
            switch(fault){
                case "original"->{try{Files.writeString(originals.resolve(f.bindings.get(f.workspaceId).approvalRef()),"{}",StandardCharsets.UTF_8);}catch(Exception error){throw new AssertionError(error);}}
                case "independent"->f.independent.update("UPDATE idp_workspace SET state='CLOSED'");
                case "session"->f.db.update("UPDATE id2_session SET status='REVOKED'");
                case "grant-deadline"->f.now=f.now.plusSeconds(1700);
                case "watermark"->f.independent.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='owned-component'");
                case "workspace-deadline"->f.now=f.now.plusSeconds(1800);
                case "organization"->f.db.update("UPDATE con3_organization SET status='DISABLED'");
                default->throw new AssertionError(fault);
            }
            return null;
        })).isInstanceOf(RuntimeException.class);assertThat(f.observations()).isZero();
    }
    @Test void leaseExpiresOnCallbackReturnAndCannotMoveThreads()throws Exception{
        f.grant(Action.READ);var authorization=f.authorization();
        var lease=authorization.execute(f.token,f.workspaceId,Action.READ,value->{
            try(var executor=Executors.newSingleThreadExecutor()){
                var future=executor.submit(()->assertThatThrownBy(value::requireCurrent).isInstanceOf(IllegalStateException.class));future.get();
            }catch(Exception error){throw new AssertionError(error);}return value;
        });
        assertThatThrownBy(lease::requireCurrent).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(lease::subject).isInstanceOf(IllegalStateException.class);
    }
    @Test void nestedTransactionsWrongBindingsAndWrongBoundResourcesAreRejected(){
        f.grant(Action.READ);var authorization=f.authorization();
        f.mainTx.executeWithoutResult(status->refused(authorization,Action.READ));
        TransactionSynchronizationManager.bindResource(f.authoritySource,new Object());
        try{refused(authorization,Action.READ);}finally{TransactionSynchronizationManager.unbindResource(f.authoritySource);}
        var evidence=new ProductPromptWorkspaceEvidence(List.copyOf(f.bindings.values()),new ProductSourceEvidenceFiles(originals,1048576));
        assertThatThrownBy(()->new ProductPromptWorkspaceAuthorization(f.store,f.control,new DataSourceTransactionManager(f.authoritySource),
                evidence,f.watermark,f.access,f.decoder,ProductPromptWorkspaceAuthorizationFixture.ISSUER,f.clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->authorization.execute(f.token,f.workspaceId,Action.COPY,value->null)).isInstanceOf(IllegalArgumentException.class);
    }
    private void refused(ProductPromptWorkspaceAuthorization authorization,Action action){
        assertThatThrownBy(()->authorization.execute(f.token,f.workspaceId,action,lease->{f.bodyAttempt();return null;})).isInstanceOf(RuntimeException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    private void refusedToken(ProductPromptWorkspaceAuthorization authorization,String token){
        assertThatThrownBy(()->authorization.execute(token,f.workspaceId,Action.READ,lease->{f.bodyAttempt();return null;})).isInstanceOf(RuntimeException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
}
