package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action.CREATE;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action.SAVE;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptInputPermissionTest {
    @TempDir Path root;
    ProductPromptWorkspaceAuthorizationFixture f;
    ProductPromptInputPermissionFixture inputs;
    @BeforeEach void setup()throws Exception{f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(CREATE);inputs=new ProductPromptInputPermissionFixture(f);}
    @Test void saveUsesItsOwnExplicitPermissionAndDoesNotAcquireRead()throws Exception{
        f.grant(SAVE);inputs.permission.put("operation","SAVE");inputs.install();var permissions=inputs.adapter();
        var saved=new AtomicReference<ProductPromptInputPermission.Witness>();
        f.authorization().execute(f.token,f.workspaceId,SAVE,lease->{
            var witness=permissions.forSaveWrite(lease,f.workspaceId,saveCommand());saved.set(witness);
            assertThat(witness.bodyLimit()).isEqualTo(f.now.plusSeconds(600));assertThat(witness.auditLimit()).isEqualTo(f.now.plusSeconds(1200));
            assertThat(witness.sourceId()).isEqualTo(inputs.sourceId);witness.requireCurrent();return null;
        });
        assertThatThrownBy(saved.get()::evidence).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,ProductPromptWorkspaceEvidence.Action.READ,lease->null))
                .isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"create-permission","create-lease","create-entry","changed-content"})
    void saveCannotBorrowCreatePermissionOrLeaseOrChangeApprovedContent(String fault)throws Exception{
        f.grant(SAVE);
        if(!fault.equals("create-permission")){inputs.permission.put("operation","SAVE");inputs.install();}
        var permissions=inputs.adapter();var action=fault.equals("create-lease")?CREATE:SAVE;
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,action,lease->{
            if(fault.equals("create-entry"))return permissions.forWrite(lease,f.workspaceId,inputs.command);
            var command=saveCommand();
            if(fault.equals("changed-content"))command=new ProductPromptSaveCommand(command.expectedGeneration(),command.expectedRevisionId(),command.kind(),command.name(),
                    command.body()+" changed",command.scenarioIds(),command.intentIds(),command.audienceRef(),command.language(),command.displayOrder(),command.variables(),command.sourceId());
            return permissions.forSaveWrite(lease,f.workspaceId,command);
        })).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @Test void originalCreateTraceCannotBeUsedThroughSaveLease(){
        var original=f.authorization().execute(f.token,f.workspaceId,CREATE,inputs::prepared);f.grant(SAVE);var permissions=inputs.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,SAVE,lease->permissions.forTrace(lease,f.workspaceId,original)))
                .isInstanceOf(IllegalStateException.class);
    }
    private ProductPromptSaveCommand saveCommand(){
        var c=inputs.command;return new ProductPromptSaveCommand(1,UUID.randomUUID().toString(),c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),
                c.audienceRef(),c.language(),c.displayOrder(),c.variables(),c.sourceId());
    }
    @Test void actualHumanLeaseAuthenticatesCompleteContentAndEveryDefaultWithoutReadGrant(){
        var permissions=inputs.adapter();
        var original=f.authorization().execute(f.token,f.workspaceId,CREATE,lease->{
            var witness=permissions.forWrite(lease,f.workspaceId,inputs.command);
            assertThat(witness.evidence().generationSourceRef()).isEqualTo("source:项目原稿/版本 1");
            assertThat(witness.sourceId()).isEqualTo(inputs.sourceId);assertThat(witness.bodyLimit()).isEqualTo(f.now.plusSeconds(600));
            assertThat(witness.auditLimit()).isEqualTo(f.now.plusSeconds(1200));witness.requireCurrent();return inputs.prepared(lease);
        });
        assertThat(permissions.sourceIdFor(original.evidence())).isEqualTo(inputs.sourceId);
        assertThat(f.bodyReads.get()).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isZero();
    }
    @Test void traceSurvivesBodyExpiryWithinSameExplicitPermissionButCannotAuthorizeAnotherWrite(){
        var original=f.authorization().execute(f.token,f.workspaceId,CREATE,inputs::prepared);var permissions=inputs.adapter();
        f.now=f.now.plusSeconds(600);
        f.authorization().execute(f.token,f.workspaceId,CREATE,lease->{
            permissions.forTrace(lease,f.workspaceId,original).requireCurrent();
            assertThatThrownBy(()->permissions.forWrite(lease,f.workspaceId,inputs.command)).isInstanceOf(IllegalStateException.class);return null;
        });assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"validUntil","auditUntil"})
    void traceClosesExactlyAtOriginalAuditOrOverallPermissionExpiry(String deadline)throws Exception{
        if(deadline.equals("validUntil")){inputs.permission.put("validUntil",f.now.plusSeconds(900));inputs.install();}
        var original=f.authorization().execute(f.token,f.workspaceId,CREATE,inputs::prepared);var permissions=inputs.adapter();
        f.now=f.now.plusSeconds(deadline.equals("validUntil")?900:1200);
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,CREATE,lease->permissions.forTrace(lease,f.workspaceId,original))).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"subject","issuer","organization","workspaceId","scopeVersion","operation","contentSha256","policySha","contentCoverage","traceCoverage","status","validFrom","bodyUntil","unknown","missing"})
    void pinnedButInvalidPermissionNeverBecomesSaveAuthority(String fault)throws Exception{
        switch(fault){
            case "scopeVersion"->inputs.permission.put(fault,2);
            case "contentSha256","policySha"->inputs.permission.put(fault,"b".repeat(64));
            case "workspaceId","organization"->inputs.permission.put(fault,UUID.randomUUID().toString());
            case "validFrom"->inputs.permission.put(fault,f.now.plusSeconds(1));
            case "bodyUntil"->inputs.permission.put(fault,f.now);
            case "unknown"->inputs.permission.put("unexpected","unapproved");
            case "missing"->inputs.permission.remove("traceCoverage");
            default->inputs.permission.put(fault,"OTHER");
        }
        inputs.install();var permissions=inputs.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,CREATE,lease->permissions.forWrite(lease,f.workspaceId,inputs.command))).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"directory","permission","default","scope"})
    void finalRecheckRejectsOriginalDriftAndChangedDefault(String fault)throws Exception{
        var permissions=inputs.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,CREATE,lease->{
            var witness=permissions.forWrite(lease,f.workspaceId,inputs.command);
            try{
                if(fault.equals("directory"))Files.writeString(root.resolve(inputs.directoryRef),"{}");
                else if(fault.equals("permission"))Files.writeString(root.resolve(inputs.permissionRef),"{}");
                else if(fault.equals("scope"))f.revoke(CREATE);
                else {
                    var c=inputs.command;var v=c.variables().getFirst();
                    var changed=new cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition(v.name(),v.type(),v.required(),v.sourceRef(),true,new java.math.BigDecimal("2.00"),v.allowEmpty());
                    permissions.forWrite(lease,f.workspaceId,new ProductPromptCreateCommand(0,c.kind(),c.name(),c.body(),c.scenarioIds(),c.intentIds(),c.audienceRef(),c.language(),c.displayOrder(),List.of(changed),c.sourceId()));
                }
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            witness.requireCurrent();return null;
        })).isInstanceOf(RuntimeException.class);
    }
    @Test void witnessCannotEscapeLeaseCrossThreadOrRunDuringAuthoritySuspend(){
        var saved=new AtomicReference<ProductPromptInputPermission.Witness>();var permissions=inputs.adapter();
        f.authorization().execute(f.token,f.workspaceId,CREATE,lease->{
            var witness=permissions.forWrite(lease,f.workspaceId,inputs.command);saved.set(witness);
            var error=new AtomicReference<Throwable>();var thread=new Thread(()->{try{witness.evidence();}catch(Throwable failed){error.set(failed);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            assertThat(error.get()).isInstanceOf(IllegalStateException.class);
            var suspend=new TransactionTemplate(f.mainManager);suspend.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspend.executeWithoutResult(status->f.authorityTx.executeWithoutResult(independent->{
                assertThatThrownBy(witness::evidence).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::bodyLimit).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::auditLimit).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::sourceId).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class);
            }));witness.requireCurrent();return null;
        });assertThatThrownBy(saved.get()::evidence).isInstanceOf(IllegalStateException.class);
        f.authorization().execute(f.token,f.workspaceId,CREATE,lease->{assertThatThrownBy(saved.get()::requireCurrent).isInstanceOf(IllegalStateException.class);return null;});
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-id","ambiguous-ref","empty","unknown"})
    void directoryCannotChooseAmbiguousOrUnpinnedSource(String fault)throws Exception{
        var row=Map.of("sourceId",inputs.sourceId,"permissionRef",inputs.permissionRef,"permissionSha",inputs.permissionSha);
        var directory=new TreeMap<String,Object>();directory.put("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1");
        directory.put("bindings",fault.equals("empty")?List.of():fault.equals("duplicate-id")?List.of(row,row):fault.equals("ambiguous-ref")?
                List.of(row,Map.of("sourceId",UUID.randomUUID().toString(),"permissionRef",inputs.permissionRef,"permissionSha",inputs.permissionSha)):List.of(row));
        if(fault.equals("unknown"))directory.put("extra",true);
        inputs.directorySha=f.write(inputs.directoryRef,directory);
        assertThatThrownBy(inputs::adapter).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-key","trailing","fractional-scope","invalid-utf8","unpaired-unicode"})
    void validPinnedHashDoesNotBypassStrictOriginalParsing(String fault)throws Exception{
        String raw=f.mapper.writeValueAsString(inputs.permission);
        if(fault.equals("duplicate-key"))raw=raw.replace("\"operation\":\"CREATE\"","\"operation\":\"CREATE\",\"operation\":\"CREATE\"");
        if(fault.equals("trailing"))raw+=" {}";
        if(fault.equals("fractional-scope"))raw=raw.replace("\"scopeVersion\":1","\"scopeVersion\":1.0");
        if(fault.equals("unpaired-unicode"))raw=raw.replace("Owned component approval input only","\\uD800");
        byte[] bytes=fault.equals("invalid-utf8")?new byte[]{(byte)0xc3,0x28}:raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve(inputs.permissionRef),bytes);inputs.permissionSha=ProductCapturedArchive.digest(bytes);
        inputs.directorySha=f.write(inputs.directoryRef,Map.of("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1","bindings",List.of(
                Map.of("sourceId",inputs.sourceId,"permissionRef",inputs.permissionRef,"permissionSha",inputs.permissionSha))));
        var permissions=inputs.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,CREATE,lease->permissions.forWrite(lease,f.workspaceId,inputs.command))).isInstanceOf(IllegalStateException.class);
    }
}
