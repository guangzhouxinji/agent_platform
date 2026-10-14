package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptPreviewPermissionTest {
    @TempDir Path root;
    ProductPromptWorkspaceAuthorizationFixture f;
    ProductPromptPreviewPermissionFixture x;
    @BeforeEach void setup()throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(READ);f.grant(PREVIEW);x=new ProductPromptPreviewPermissionFixture(f);
    }
    @Test void currentDualGrantLeaseMapsApprovedDefaultAndTestEvidenceWithoutPersistentWrites(){
        var permission=x.adapter();
        f.authorization().execute(f.token,f.workspaceId,PREVIEW,lease->{
            var witness=permission.admit(lease,f.workspaceId,x.header,x.revision,x.sourceId);
            assertThat(lease.testAdmitted()).isFalse();
            assertThat(witness.policy().policyRef()).isEqualTo(x.permission.get("permissionId")).doesNotContain("/");
            assertThat(witness.policySha()).isEqualTo(x.permissionSha);
            assertThat(witness.schemaSha()).isEqualTo(ProductPromptPreviewValues.schemaSha(x.revision.variables()));
            assertThat(witness.expiresAt()).isEqualTo(f.now.plusSeconds(500));
            assertThat(witness.evidenceIds()).containsExactly(Map.entry("ratio","sample-test:ratio"));
            var result=new TemplateRenderer().render(x.revision,Map.of(),witness.policy(),TemplateRenderer.Mode.TEST,f.now);
            var data=(TemplateRenderer.DataSegment)result.segments().get(1);
            assertThat(data.sourceEvidenceRef()).isEqualTo(x.permission.get("permissionId"));
            assertThat(data.value()).isEqualTo(new java.math.BigDecimal("1.00"));
            witness.requireCurrent();return null;
        });
        assertThat(f.bodyReads.get()).isZero();
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt","idp_prompt_control","idp_revision_witness"))
            assertThat((table.startsWith("idp_")?f.independent:f.db).queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void noVariableRevisionStillNeedsExactTestPermission()throws Exception{
        var revision=ProductPromptPreviewPermissionFixture.revision(f,List.of(),"Owned literal");
        var header=new Header(revision.reference().revisionId(),revision.reference().promptId(),1,revision.reference().contentSha256(),
                ProductComparisonJson.hash("literal record"),f.now,f.now.plusSeconds(600),f.now.plusSeconds(1200),"PRESENT");
        var empty=new ProductPromptPreviewPermissionFixture(f,header,revision);
        f.authorization().execute(f.token,f.workspaceId,PREVIEW,lease->{
            var witness=empty.admit(lease);assertThat(witness.evidenceIds()).isEmpty();
            assertThat(witness.expiresAt()).isEqualTo(f.now.plusSeconds(900));
            assertThat(new TemplateRenderer().render(revision,Map.of(),witness.policy(),TemplateRenderer.Mode.TEST,f.now).segments())
                    .containsExactly(new TemplateRenderer.LiteralSegment("Owned literal"));
            assertThatThrownBy(()->empty.adapter().admit(lease,f.workspaceId,header,revision,UUID.randomUUID().toString()))
                    .isInstanceOf(IllegalStateException.class);return null;
        });
    }
    @ParameterizedTest @ValueSource(strings={"READ","PREVIEW"})
    void eitherGrantMissingBlocksAdmissionBeforePolicyCanAuthorizeRendering(String revoked){
        f.revoke(ProductPromptWorkspaceEvidence.Action.valueOf(revoked));
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,PREVIEW,x::admit)).isInstanceOf(ProductPromptAccessDenied.class);
    }
    @ParameterizedTest @ValueSource(strings={"READ","CREATE","SAVE"})
    void otherLeaseActionsCannotReuseTestPermission(String selected){
        var action=ProductPromptWorkspaceEvidence.Action.valueOf(selected);if(action!=READ)f.grant(action);
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,action,x::admit)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"prompt","revision","number","content","submitted","state"})
    void headerMustDescribeTheSameAuthenticatedImmutableRevision(String fault){
        Header h=x.header;
        var changed=new Header(fault.equals("revision")?UUID.randomUUID().toString():h.revisionId(),
                fault.equals("prompt")?UUID.randomUUID().toString():h.promptId(),fault.equals("number")?2:h.number(),
                fault.equals("content")?"b".repeat(64):h.contentSha(),h.recordSha(),
                fault.equals("submitted")?h.submittedAt().minusSeconds(1):h.submittedAt(),h.bodyUntil(),h.auditUntil(),
                fault.equals("state")?"CLEARED":h.bodyState());
        var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,PREVIEW,
                lease->permission.admit(lease,f.workspaceId,changed,x.revision,x.sourceId))).isInstanceOf(IllegalStateException.class);
    }
    @Test void contentAndRecordApprovalCannotSubstituteForApprovalOfChangedDefault()throws Exception{
        var old=x.revision;var variable=old.variables().getFirst();
        var changedVariable=new cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition(variable.name(),variable.type(),variable.required(),
                variable.sourceRef(),true,new java.math.BigDecimal("2.00"),variable.allowEmpty());
        var variables=List.of(changedVariable);
        var ref=cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision.referenceFor(old.reference().promptId(),old.reference().revisionId(),
                old.reference().version(),old.kind(),old.name(),old.body(),old.scenarioIds(),old.intentIds(),old.audienceRef(),old.language(),old.displayOrder(),variables);
        var revision=new cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision(ref,old.kind(),old.name(),old.body(),old.scenarioIds(),old.intentIds(),
                old.audienceRef(),old.language(),old.displayOrder(),variables,old.provenance());
        var header=new Header(ref.revisionId(),ref.promptId(),ref.version(),ref.contentSha256(),ProductComparisonJson.hash("changed default record"),
                x.header.submittedAt(),x.header.bodyUntil(),x.header.auditUntil(),"PRESENT");
        x.permission.put("contentSha",header.contentSha());x.permission.put("recordSha",header.recordSha());x.install();
        assertThat(x.permission.get("schemaSha")).isNotEqualTo(ProductPromptPreviewValues.schemaSha(variables));
        var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,PREVIEW,
                lease->permission.admit(lease,f.workspaceId,header,revision,x.sourceId))).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"issuer","subject","workspaceId","organization","scopeVersion","registrySha","policyRef","policySha",
            "promptId","revisionId","revisionNumber","recordSha","contentSha","schemaSha","operation","mode","inputMode","outputCoverage",
            "format","permissionId","status","validFrom","validUntil","unknown","missing"})
    void pinnedBytesDoNotAuthorizeWrongScopeRevisionOrPurpose(String fault)throws Exception{
        switch(fault){
            case "workspaceId","organization","promptId","revisionId"->x.permission.put(fault,UUID.randomUUID().toString());
            case "scopeVersion","revisionNumber"->x.permission.put(fault,2);
            case "registrySha","policySha","recordSha","contentSha","schemaSha"->x.permission.put(fault,"b".repeat(64));
            case "permissionId"->x.permission.put(fault,"internal/path.json");
            case "validFrom"->x.permission.put(fault,f.now.plusSeconds(1));
            case "validUntil"->x.permission.put(fault,f.now);
            case "unknown"->x.permission.put("unexpected","value");
            case "missing"->x.permission.remove("schemaSha");
            default->x.permission.put(fault,"OTHER");
        }
        x.install();assertRejected();
    }
    @ParameterizedTest @ValueSource(strings={"name","sourceRef","evidenceId","purpose","status","validFrom","validUntil","blank-source",
            "blank-version","blank-owner","missing","unknown","duplicate","empty","extra"})
    void everySchemaVariableNeedsItsExactCurrentTestSourceRule(String fault)throws Exception{
        switch(fault){
            case "evidenceId"->x.variable(0).put(fault,"private/source.json");
            case "validFrom"->x.variable(0).put(fault,f.now.plusSeconds(1));
            case "validUntil"->x.variable(0).put(fault,f.now);
            case "blank-source"->x.variable(0).put("source","");
            case "blank-version"->x.variable(0).put("decisionVersion","");
            case "blank-owner"->x.variable(0).put("responsibleParty","");
            case "missing"->x.variable(0).remove("purpose");
            case "unknown"->x.variable(0).put("extra","unapproved");
            case "duplicate"->x.permission.put("variables",List.of(x.variable(0),x.variable(0)));
            case "empty"->x.permission.put("variables",List.of());
            case "extra"->{var extra=new TreeMap<>(x.variable(0));extra.put("name","other");x.permission.put("variables",List.of(x.variable(0),extra));}
            default->x.variable(0).put(fault,"OTHER");
        }
        x.install();assertRejected();
    }
    @ParameterizedTest @ValueSource(strings={"directory","permission","source-deadline","policy-deadline","READ","PREVIEW"})
    void finalCheckAndPolicyAccessCloseOnOriginalDriftOrCurrentAuthorityLoss(String fault)throws Exception{
        if(fault.equals("policy-deadline")){x.permission.put("validUntil",f.now.plusSeconds(400));x.install();}
        var permission=x.adapter();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,PREVIEW,lease->{
            var witness=permission.admit(lease,f.workspaceId,x.header,x.revision,x.sourceId);
            try{
                if(fault.equals("directory"))Files.writeString(root.resolve(x.directoryRef),"{}");
                else if(fault.equals("permission"))Files.writeString(root.resolve(x.permissionRef),"{}");
                else if(fault.equals("source-deadline"))f.now=f.now.plusSeconds(500);
                else if(fault.equals("policy-deadline"))f.now=f.now.plusSeconds(400);
                else f.revoke(ProductPromptWorkspaceEvidence.Action.valueOf(fault));
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            assertThatThrownBy(witness::policy).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(witness::evidenceIds).isInstanceOf(RuntimeException.class);
            witness.requireCurrent();return null;
        })).isInstanceOf(RuntimeException.class);
    }
    @Test void witnessCannotBeUsedOutsideOwningLeaseThreadOrDuringSuspension(){
        var held=new AtomicReference<ProductPromptPreviewPermission.Witness>();var permission=x.adapter();
        f.authorization().execute(f.token,f.workspaceId,PREVIEW,lease->{
            var witness=permission.admit(lease,f.workspaceId,x.header,x.revision,x.sourceId);held.set(witness);
            var failure=new AtomicReference<Throwable>();var other=new Thread(()->{try{witness.policy();}catch(Throwable error){failure.set(error);}});
            other.start();try{other.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            var suspend=new TransactionTemplate(f.mainManager);suspend.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspend.executeWithoutResult(status->f.authorityTx.executeWithoutResult(independent->{
                assertThatThrownBy(witness::policy).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::evidenceIds).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::schemaSha).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::policySha).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::expiresAt).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(witness::requireCurrent).isInstanceOf(IllegalStateException.class);
            }));witness.requireCurrent();return null;
        });
        assertThatThrownBy(held.get()::policy).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(held.get()::expiresAt).isInstanceOf(IllegalStateException.class);
        f.authorization().execute(f.token,f.workspaceId,PREVIEW,lease->{assertThatThrownBy(held.get()::requireCurrent).isInstanceOf(IllegalStateException.class);return null;});
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-key","trailing","fractional-scope","string-revision","invalid-utf8","unpaired-unicode",
            "bad-date","noncanonical-time","submillisecond-time","null-source","variables-object","variables-null"})
    void matchingHashCannotBypassStrictOriginalTypesOrEncoding(String fault)throws Exception{
        String raw=f.mapper.writeValueAsString(x.permission);
        raw=switch(fault){
            case "duplicate-key"->raw.replace("\"mode\":\"TEST\"","\"mode\":\"TEST\",\"mode\":\"TEST\"");
            case "trailing"->raw+" {}";
            case "fractional-scope"->raw.replace("\"scopeVersion\":1","\"scopeVersion\":1.0");
            case "string-revision"->raw.replace("\"revisionNumber\":1","\"revisionNumber\":\"1\"");
            case "unpaired-unicode"->raw.replace("Owned TEST component approval input only","\\uD800");
            default->raw;
        };
        if(fault.equals("bad-date")){x.variable(0).put("decisionDate","2026-02-30");raw=f.mapper.writeValueAsString(x.permission);}
        if(fault.equals("noncanonical-time")){x.permission.put("validUntil","2099-01-01T00:00:00+00:00");raw=f.mapper.writeValueAsString(x.permission);}
        if(fault.equals("submillisecond-time")){x.variable(0).put("validUntil","2099-01-01T00:00:00.000001Z");raw=f.mapper.writeValueAsString(x.permission);}
        if(fault.equals("null-source")){x.variable(0).put("source",null);raw=f.mapper.writeValueAsString(x.permission);}
        if(fault.equals("variables-object")||fault.equals("variables-null")){
            x.permission.put("variables",fault.equals("variables-object")?Map.of():null);raw=f.mapper.writeValueAsString(x.permission);
        }
        x.installBytes(fault.equals("invalid-utf8")?new byte[]{(byte)0xc3,0x28}:raw.getBytes(StandardCharsets.UTF_8));assertRejected();
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-id","duplicate-ref","empty","too-many","unknown","wrong-format","path","noncanonical-id"})
    void directoryRejectsAmbiguityWrongFormatAndUncontrolledReference(String fault)throws Exception{
        var row=new TreeMap<String,Object>(x.binding());var directory=new TreeMap<String,Object>();
        directory.put("format","PROMPT_VARIABLE_TEST_BINDINGS/v1");directory.put("bindings",List.of(row));
        switch(fault){
            case "duplicate-id"->directory.put("bindings",List.of(row,row));
            case "duplicate-ref"->{var other=new TreeMap<>(row);other.put("sourceId",UUID.randomUUID().toString());directory.put("bindings",List.of(row,other));}
            case "empty"->directory.put("bindings",List.of());
            case "too-many"->directory.put("bindings",Collections.nCopies(129,row));
            case "unknown"->directory.put("extra",true);
            case "wrong-format"->directory.put("format","PROMPT_INPUT_PERMISSION_BINDINGS/v1");
            case "path"->row.put("permissionRef","../permission.json");
            case "noncanonical-id"->row.put("sourceId",x.sourceId.toUpperCase(Locale.ROOT));
        }
        x.directorySha=f.write(x.directoryRef,directory);assertThatThrownBy(x::adapter).isInstanceOf(IllegalStateException.class);
    }
    private void assertRejected(){
        var permission=x.adapter();assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,PREVIEW,
                lease->permission.admit(lease,f.workspaceId,x.header,x.revision,x.sourceId))).isInstanceOf(IllegalStateException.class);
    }
}
