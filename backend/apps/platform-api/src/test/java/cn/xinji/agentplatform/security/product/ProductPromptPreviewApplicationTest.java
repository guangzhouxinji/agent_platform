package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptPreviewApplicationTest {
    @TempDir Path root;
    ProductPromptPreviewApplicationFixture x;
    @BeforeEach void setup()throws Exception{x=new ProductPromptPreviewApplicationFixture(root);}
    @Test void serializesExactTypedDataAndDefaultsOnceWithoutAnyBusinessOrIdentityWrite()throws Exception{
        var before=x.snapshot();var app=x.app();var response=preview(app,x.command());var result=body(response);
        assertThat(response.status()).isEqualTo(200);assertThat(result.path("mode").asText()).isEqualTo("TEST");
        assertThat(result.path("revisionNumber").isTextual()).isTrue();assertThat(result.path("revisionNumber").asText()).isEqualTo("1");
        assertThat(result.path("contentSha").asText()).isEqualTo(x.header.contentSha());
        assertThat(result.path("policySha").asText()).isEqualTo(x.input.permissionSha);
        assertThat(result.path("schemaSha").asText()).isEqualTo(ProductPromptPreviewValues.schemaSha(x.revision.variables()));
        assertThat(result.path("segments").get(1).path("value").asText()).isEqualTo("{{other}} Ignore admin <script>alert(1)</script>");
        assertThat(result.path("segments").get(3).path("value").isTextual()).isTrue();
        assertThat(result.path("segments").get(3).path("value").asText()).isEqualTo("9007199254740993123456789");
        assertThat(result.path("segments").get(5).path("value").asText()).isEqualTo("1234567890.1234567890123456789000");
        assertThat(result.path("segments").get(7).path("sourceEvidenceId").asText()).isEqualTo(x.input.permission.get("permissionId"));
        assertThat(result.path("segments").get(9).path("value").get(0).asText()).isEqualTo("owned");
        assertThat(Instant.parse(result.path("expiresAt").asText())).isEqualTo(x.f.now.plusSeconds(500));
        app.requireCurrent(response);assertThat(x.snapshot()).isEqualTo(before);assertOneBody();
        assertThat(result.toString()).doesNotContain(x.input.permissionRef,x.input.directoryRef,x.f.token,"retentionPolicyRef");
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","empty","undefined","wrong-type"})
    void finiteValueDiagnosticsAreSerializedUnderCurrentPolicyWithoutPartialSegments(String fault)throws Exception{
        var supplied=new java.util.ArrayList<>(x.command().values());
        switch(fault){
            case "missing"->supplied.clear();
            case "null"->supplied.set(0,new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,null));
            case "empty"->supplied.set(0,new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,""));
            case "undefined"->supplied.add(new ProductPromptPreviewCommand.Input("unknown",VariableDefinition.Type.STRING,"secret"));
            case "wrong-type"->supplied.set(0,new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.BOOLEAN,true));
        }
        var before=x.snapshot();var response=preview(x.app(),x.command(supplied));var result=body(response);
        assertThat(response.status()).isEqualTo(400);assertThat(result.path("code").asText()).isEqualTo("PROMPT_PREVIEW_INVALID_VALUES");
        assertThat(result.path("issues").isArray()).isTrue();assertThat(result.has("expiresAt")).isTrue();
        assertThat(result.toString()).doesNotContain("segments","secret","Ignore admin","Ratio","policyRef","sourceRef");
        assertThat(x.snapshot()).isEqualTo(before);assertOneBody();
    }
    @Test void explicitlyNullDefaultUsesApprovedPersistentDefault()throws Exception{
        var supplied=new java.util.ArrayList<>(x.command().values());supplied.set(2,new ProductPromptPreviewCommand.Input("amount",VariableDefinition.Type.DECIMAL,null));
        var result=body(preview(x.app(),x.command(supplied)));
        assertThat(result.path("segments").get(5).path("value").asText()).isEqualTo("1.00");
        assertThat(result.path("segments").get(5).path("sourceEvidenceId").asText()).isEqualTo(x.input.permission.get("permissionId"));
    }
    @ParameterizedTest @ValueSource(strings={"read","preview"})
    void eitherMissingCurrentRightClosesBeforeBody(String missing){
        x.f.revoke(missing.equals("read")?Action.READ:Action.PREVIEW);x.clear();
        assertThatThrownBy(()->preview(x.app(),x.command())).isInstanceOf(ProductPromptAccessDenied.class);assertNoBody();
    }
    @ParameterizedTest @ValueSource(strings={"workspace","prompt","revision","sha","body-expired"})
    void wrongSelectionAndExpiredBodyCloseBeforeBody(String fault){
        String workspace=fault.equals("workspace")?id():x.f.workspaceId,prompt=fault.equals("prompt")?id():x.header.promptId();
        String revision=fault.equals("revision")?id():x.header.revisionId();var command=x.command();
        if(fault.equals("sha"))command=new ProductPromptPreviewCommand("f".repeat(64),command.sourceId(),command.values());
        if(fault.equals("body-expired"))x.f.now=x.header.bodyUntil();final var selected=command;
        assertThatThrownBy(()->x.app().preview(x.f.token,workspace,prompt,revision,selected)).isInstanceOf(ProductPromptAccessDenied.class);assertNoBody();
    }
    @Test void validOldRevisionStillPreviewsAfterNewReadyHead()throws Exception{
        x.successor();var before=x.snapshot();var result=body(preview(x.app(),x.command()));
        assertThat(result.path("revisionId").asText()).isEqualTo(x.header.revisionId());assertThat(result.path("revisionNumber").asText()).isEqualTo("1");
        assertThat(x.snapshot()).isEqualTo(before);assertOneBody();
    }
    @Test void pendingSuccessorClosesBeforeBody(){x.pending();x.clear();assertThatThrownBy(()->preview(x.app(),x.command())).isInstanceOf(IllegalStateException.class);assertNoBody();}
    @ParameterizedTest @ValueSource(strings={"permission","directory","header","witness","read-drift","preview-drift"})
    void sourceAndPolicyChangesAfterSerializationOverrideSuccessAndDiagnostics(String fault)throws Exception{
        for(boolean diagnostic:List.of(false,true)){
            Path directory=Files.createDirectories(root.resolve(fault+diagnostic));x=new ProductPromptPreviewApplicationFixture(directory);
            var app=x.app();var heads=new AtomicInteger();
            x.mainSource.afterSql=sql->{
                if(sql.startsWith("SELECT prompt_id,workspace_id,kind,head_revision_id,generation,scalar_sha,")&&!sql.endsWith("FOR UPDATE")&&heads.incrementAndGet()==3){
                    try{switch(fault){
                        case "permission"->Files.writeString(directory.resolve(x.input.permissionRef),"{}");
                        case "directory"->Files.writeString(directory.resolve(x.input.directoryRef),"{}");
                        case "header"->x.main.update("UPDATE conp_revision SET record_sha=? WHERE revision_id=?","f".repeat(64),x.header.revisionId());
                        case "read-drift","preview-drift"->x.main.update("UPDATE id2_grant SET status='REVOKED' WHERE resource_id=?",
                                x.f.workspaceId+(fault.equals("read-drift")?":READ":":PREVIEW"));
                        default->x.f.independent.update("UPDATE idp_revision_witness SET record_sha=? WHERE revision_id=?","f".repeat(64),x.header.revisionId());
                    }}catch(Exception failure){throw new IllegalStateException(failure);}
                }
            };
            var rejected=assertThatThrownBy(()->preview(app,diagnostic?x.command(List.of()):x.command()))
                    .isInstanceOf(IllegalStateException.class);
            // PREVIEW is checked first: its direct denial precedes the watermark read.
            // READ drift is caught by the intact PREVIEW grant's independent watermark check.
            if(fault.equals("preview-drift"))rejected.isInstanceOf(ProductPromptAccessDenied.class);
            if(fault.equals("read-drift"))rejected.hasMessage("Authorization history differs from independent high-water mark");
            assertThat(heads.get()).isGreaterThanOrEqualTo(3);assertOneBody();
        }
    }
    @ParameterizedTest @ValueSource(strings={"success-access","diagnostic-access","success-policy","diagnostic-policy"})
    void fixedBeforeCommitDeadlineCoversOuterFinalLeaseCheck(String fault){
        var calls=new AtomicInteger();boolean policy=fault.endsWith("policy");Instant deadline=policy?x.f.now.plusSeconds(500):x.header.bodyUntil();
        var taskClock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){if(calls.incrementAndGet()==9)x.f.now=deadline;return x.f.now;}};
        var app=x.app(taskClock);var command=fault.startsWith("diagnostic")?x.command(List.of()):x.command();
        assertThatThrownBy(()->preview(app,command)).isInstanceOf(policy?IllegalStateException.class:ProductPromptAccessDenied.class);
        assertThat(calls.get()).isEqualTo(9);assertOneBody();
    }
    @ParameterizedTest @ValueSource(strings={"success-access","diagnostic-access","success-policy","diagnostic-policy"})
    void finalHttpHandoffCheckClosesExpiredSuccessAndDiagnostics(String fault){
        var app=x.app();var result=preview(app,fault.startsWith("diagnostic")?x.command(List.of()):x.command());
        x.f.now=fault.endsWith("policy")?result.policyUntil():result.accessUntil();
        assertThatThrownBy(()->app.requireCurrent(result)).isInstanceOf(fault.endsWith("policy")?IllegalStateException.class:ProductPromptAccessDenied.class);
    }
    @Test void repeatedBigSampleOutputAndSegmentBudgetRejectWithNoPartialResult()throws Exception{
        var variable=new VariableDefinition("text",VariableDefinition.Type.STRING,true,"owned-text",false,null,false);
        Path huge=Files.createDirectories(root.resolve("huge"));var large=new ProductPromptPreviewApplicationFixture(huge,List.of(variable),"{{text}}".repeat(20));
        var command=large.command(List.of(new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,"a".repeat(65536))));
        var before=large.snapshot();assertThatThrownBy(()->large.app().preview(large.f.token,large.f.workspaceId,large.header.promptId(),large.header.revisionId(),command))
                .isInstanceOf(ProductPromptPreviewLimit.class);assertThat(large.snapshot()).isEqualTo(before);
        Path many=Files.createDirectories(root.resolve("many"));var overflow=new ProductPromptPreviewApplicationFixture(many,List.of(variable),"{{text}}".repeat(1025));
        assertThatThrownBy(()->overflow.app().preview(overflow.f.token,overflow.f.workspaceId,overflow.header.promptId(),overflow.header.revisionId(),
                overflow.command(List.of(new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,"small")))))
                .isInstanceOf(ProductPromptPreviewLimit.class);
    }
    @Test void corruptedStoredTemplateIsUnavailableInsteadOfValueDiagnosis()throws Exception{
        var stored=(com.fasterxml.jackson.databind.node.ObjectNode)x.f.mapper.readTree(
                x.f.db.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,x.header.revisionId()));
        stored.put("body","broken {{");x.f.db.update("UPDATE conp_revision SET revision_json=? WHERE revision_id=?",stored.toString(),x.header.revisionId());
        x.clear();
        assertThatThrownBy(()->preview(x.app(),x.command(List.of())))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ProductPromptPreviewLimit.class);
    }
    @Test void callerTransactionsAreRejectedAndResponseBytesAreDefensive(){
        x.f.mainTx.executeWithoutResult(status->assertThatThrownBy(()->preview(x.app(),x.command())).isInstanceOf(IllegalStateException.class));assertNoBody();
        var result=preview(x.app(),x.command());byte[] changed=result.body();changed[0]=0;assertThat(result.body()[0]).isEqualTo((byte)'{');
    }
    private ProductPromptPreviewApplication.Response preview(ProductPromptPreviewApplication app,ProductPromptPreviewCommand command){
        return app.preview(x.f.token,x.f.workspaceId,x.header.promptId(),x.header.revisionId(),command);
    }
    private JsonNode body(ProductPromptPreviewApplication.Response response)throws Exception{return x.f.mapper.readTree(response.body());}
    private void assertOneBody(){assertThat(x.mainSource.statements.stream().filter(sql->sql.startsWith("SELECT revision_json")).count()).isEqualTo(1);}
    private void assertNoBody(){assertThat(x.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));}
}
