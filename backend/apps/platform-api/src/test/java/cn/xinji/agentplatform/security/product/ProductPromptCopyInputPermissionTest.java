package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptCopyInputPermissionTest {
    @TempDir Path root;
    ProductPromptCopySourceFixture x;
    ProductPromptWorkspaceAuthorizationFixture f;
    @BeforeEach void setup()throws Exception{x=new ProductPromptCopySourceFixture(root);f=x.f;}
    @Test void exactSourceAndDestinationPermissionUsesShorterSourceDeadlines(){
        var permission=x.adapter();
        var original=f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var witness=permission.forWrite(lease,x.command,x.snapshot(lease));
            assertThat(witness.bodyLimit()).isEqualTo(x.header.bodyUntil());assertThat(witness.auditLimit()).isEqualTo(x.header.auditUntil());
            assertThat(witness.evidence().policyRef()).isEqualTo(f.bindings.get(x.targetWorkspace).policyRef());
            assertThat(witness.evidence().generationSourceRef()).isEqualTo("PROMPT_REVISION:"+x.head.promptId()+":"+x.header.revisionId());
            witness.requireCurrent();return x.prepared(lease);
        });
        assertThat(permission.sourceIdFor(original.evidence())).isEqualTo(x.sourceId);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"bodyUntil","auditUntil","validUntil","sourceWorkspace"})
    void everyApplicableShorterLimitIsRetained(String boundary)throws Exception{
        if(boundary.equals("sourceWorkspace")){
            f.db.update("UPDATE conp_workspace SET valid_until=? WHERE workspace_id=?",Timestamp.from(f.now.plusSeconds(500)),f.workspaceId);
            f.independent.update("UPDATE idp_workspace SET valid_until=? WHERE workspace_id=?",Timestamp.from(f.now.plusSeconds(500)),f.workspaceId);
        }else if(boundary.equals("auditUntil")){
            x.permission.put("bodyUntil",f.now.plusSeconds(400));x.permission.put("auditUntil",f.now.plusSeconds(500));x.install();
        }else{x.permission.put(boundary,f.now.plusSeconds(500));x.install();}
        var permission=x.adapter();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var witness=permission.forWrite(lease,x.command,x.snapshot(lease));
            if(boundary.equals("bodyUntil"))assertThat(witness.bodyLimit()).isEqualTo(f.now.plusSeconds(500));
            else assertThat(witness.auditLimit()).isEqualTo(f.now.plusSeconds(500));return null;
        });
    }
    @Test void traceAfterSourceBodyExpiryAndPendingDoesNotReadAnySourcePromptState(){
        var original=f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,x::prepared);
        int reads=f.bodyReads.get();f.now=x.header.bodyUntil();x.sourcePending();
        f.db.execute("ALTER TABLE conp_prompt RENAME TO hidden_source_prompts");
        f.db.execute("ALTER TABLE conp_revision RENAME TO hidden_source_revisions");
        f.independent.execute("ALTER TABLE idp_revision_witness RENAME TO hidden_source_witnesses");
        var permission=x.adapter();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var witness=permission.forTrace(lease,original);witness.requireCurrent();
            assertThat(witness.bodyLimit()).isEqualTo(x.header.bodyUntil());return null;
        });assertThat(f.bodyReads.get()).isEqualTo(reads);
    }
    @ParameterizedTest @ValueSource(strings={"audit","revoked-read","changed-permission","different-subject"})
    void traceStillRequiresCurrentPermissionActorAndAudit(String fault)throws Exception{
        var original=f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,x::prepared);int reads=f.bodyReads.get();
        var permission=x.adapter();
        switch(fault){
            case "audit"->f.now=x.header.auditUntil();case "revoked-read"->f.revoke(Action.READ);
            case "changed-permission"->Files.writeString(root.resolve(x.permissionRef),"{}");
            default->{x.permission.put("subject","other");x.install();permission=x.adapter();}
        }
        var current=permission;
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->current.forTrace(lease,original)))
                .isInstanceOf(RuntimeException.class);assertThat(f.bodyReads.get()).isEqualTo(reads);
    }
    @ParameterizedTest @ValueSource(strings={"format","operation","contentCoverage","traceCoverage","subject","issuer","workspaceId","organization",
            "scopeVersion","policySha","contentSha256","generationSourceRef","sourceWorkspaceId","sourcePromptId","sourceRevisionId","sourceRevisionNumber",
            "sourceRecordSha","sourceContentSha256","sourceBodyUntil","sourceAuditUntil","sourceScopeVersion","sourceRegistrySha","sourcePolicyRef","sourcePolicySha",
            "status","validFrom","unknown","missing"})
    void pinnedBytesCannotAuthorizeWrongExactSourceOrCopyScope(String field)throws Exception{
        if(List.of("scopeVersion","sourceScopeVersion","sourceRevisionNumber").contains(field))x.permission.put(field,2);
        else if(field.equals("validFrom"))x.permission.put(field,f.now.plusSeconds(1));
        else if(field.equals("sourceBodyUntil"))x.permission.put(field,x.header.bodyUntil().plusSeconds(1));
        else if(field.equals("sourceAuditUntil"))x.permission.put(field,x.header.auditUntil().plusSeconds(1));
        else if(field.equals("unknown"))x.permission.put("extra",true);
        else if(field.equals("missing"))x.permission.remove("sourceRecordSha");
        else if(field.endsWith("Sha")||field.endsWith("Sha256"))x.permission.put(field,"f".repeat(64));
        else if(field.endsWith("Id")||field.equals("organization"))x.permission.put(field,id());
        else x.permission.put(field,"OTHER");
        x.install();var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->permission.forWrite(lease,x.command,x.snapshot(lease))))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","fractional","string-number","null-number","invalid-utf8","unpaired-unicode"})
    void pinnedHashesDoNotBypassStrictCopyOriginalParsing(String fault)throws Exception{
        String raw=f.mapper.writeValueAsString(x.permission);
        raw=switch(fault){
            case "duplicate"->raw.substring(0,raw.length()-1)+",\"operation\":\"COPY\"}";
            case "trailing"->raw+" {}";case "fractional"->raw.replace("\"sourceRevisionNumber\":1","\"sourceRevisionNumber\":1.0");
            case "string-number"->raw.replace("\"sourceRevisionNumber\":1","\"sourceRevisionNumber\":\"1\"");
            case "null-number"->raw.replace("\"sourceRevisionNumber\":1","\"sourceRevisionNumber\":null");
            case "unpaired-unicode"->raw.replace("owned-component","\\uD800");default->raw;
        };
        byte[] bytes=fault.equals("invalid-utf8")?new byte[]{(byte)0xc3,0x28}:raw.getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(x.permissionRef),bytes);
        x.permissionSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        x.directorySha=f.write(x.directoryRef,Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",List.of(
                Map.of("sourceId",x.sourceId,"permissionRef",x.permissionRef,"permissionSha",x.permissionSha))));
        var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->permission.forWrite(lease,x.command,x.snapshot(lease))))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"directory","permission","body-deadline","source-pending"})
    void finalCheckDetectsDriftWithoutAnotherBodyRead(String fault){
        var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var witness=permission.forWrite(lease,x.command,x.snapshot(lease));
            try{
                if(fault.equals("directory"))Files.writeString(root.resolve(x.directoryRef),"{}");
                else if(fault.equals("permission"))Files.writeString(root.resolve(x.permissionRef),"{}");
                else if(fault.equals("body-deadline"))f.now=x.header.bodyUntil();else x.sourcePending();
            }catch(java.io.IOException failed){throw new IllegalStateException(failed);}
            witness.requireCurrent();return null;
        })).isInstanceOf(RuntimeException.class);assertThat(f.bodyReads.get()).isEqualTo(1);
    }
    @Test void sourceAndTargetRolesCannotBeSwappedEvenWhenBothWorkspacesParticipate(){
        f.grant(Action.COPY);f.grant(x.targetWorkspace,Action.READ);
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,x.targetWorkspace,f.workspaceId,x::snapshot)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            assertThatThrownBy(()->lease.requireCopySource(x.targetWorkspace)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->lease.requireCopyTarget(f.workspaceId)).isInstanceOf(IllegalStateException.class);
            var reversed=new ProductPromptCopyCommand(0,x.command.sourceRevisionId(),x.command.sourceContentSha(),f.workspaceId,x.sourceId);
            assertThatThrownBy(()->x.adapter().forWrite(lease,reversed,x.snapshot(lease))).isInstanceOf(IllegalStateException.class);return null;
        });
    }
    @Test void witnessCannotEscapeLeaseThreadOrSuspendedMainTransaction(){
        var held=new AtomicReference<ProductPromptCopyInputPermission.Witness>();var permission=x.adapter();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var witness=permission.forWrite(lease,x.command,x.snapshot(lease));held.set(witness);var failure=new AtomicReference<Throwable>();
            var thread=new Thread(()->{try{witness.evidence();}catch(Throwable error){failure.set(error);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            var suspend=new TransactionTemplate(f.mainManager);suspend.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspend.executeWithoutResult(status->f.authorityTx.executeWithoutResult(independent->{
                assertThatThrownBy(witness::evidence).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class);
            }));witness.requireCurrent();return null;
        });assertThatThrownBy(held.get()::bodyLimit).isInstanceOf(IllegalStateException.class);
    }
}
