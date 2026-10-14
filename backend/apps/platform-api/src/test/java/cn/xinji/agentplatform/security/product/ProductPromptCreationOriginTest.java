package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductPromptCreationOriginTest {
    @TempDir Path root;
    ProductPromptWorkspaceAuthorizationFixture f;
    ProductPromptCreationOrigin origins;
    PreparedWrite first;
    @BeforeEach void setup()throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(root);f.grant(Action.SAVE);
        var command=new ProductPromptCreateCommand(0,PromptRevision.Kind.SYSTEM,"Original","Private old text",List.of(),List.of(),"internal","zh",0,List.of(),id());
        var revision=command.revision(id(),id(),new PromptRevision.Provenance("owned","original-creator",f.now,"original-creator",f.now,"CREATE_DRAFT",List.of(),null,f.bindings.get(f.workspaceId).policyRef()));
        var write=new Write(f.workspaceId,revision,0,Operation.CREATE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"original-creator",
                ProductComparisonJson.hash("create-key"),command.requestSha(f.workspaceId),f.now.plusSeconds(10),f.now.plusSeconds(1200));
        first=f.mainTx.execute(status->f.store.prepare(write));
        var pending=new Pending(first.after().promptId(),f.workspaceId,revision.kind(),0,null,"0".repeat(64),first.after().scalarSha(),
                revision.reference().revisionId(),id(),Operation.CREATE,write.keySha(),write.requestSha(),f.now.plusSeconds(5),write.bodyUntil(),write.auditUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));
        f.mainTx.executeWithoutResult(status->f.store.append(first));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,first.after(),first.header(),first.receipt()));
        origins=new ProductPromptCreationOrigin(f.store,f.control,f.clock);
    }
    @Test void saveOnlyUsesCreationScalarsAfterOldBodyExpiresAndDoesNotGrantRead(){
        f.now=f.now.plusSeconds(20);
        var result=f.authorization().execute(f.token,f.workspaceId,Action.SAVE,lease->origins.requireCurrent(lease,first.after(),null));
        assertThat(result.createdBy()).isEqualTo("original-creator");assertThat(result.createdAt()).isEqualTo(first.receipt().submittedAt());
        assertThat(result.createdBy()).isNotEqualTo("member");assertThat(f.bodyReads.get()).isZero();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,Action.READ,lease->null)).isInstanceOf(ProductPromptAccessDenied.class);
    }
    @Test void originalPendingCanAuthenticateOldWitnessWithoutCurrentReady(){
        var pending=reserveSave();
        var result=f.authorization().execute(f.token,f.workspaceId,Action.SAVE,lease->origins.requireCurrent(lease,first.after(),pending));
        assertThat(result.createdBy()).isEqualTo("original-creator");assertThat(f.bodyReads.get()).isZero();
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,Action.SAVE,lease->origins.requireCurrent(lease,first.after(),null)))
                .isInstanceOf(IllegalStateException.class);
        f.independent.update("UPDATE idp_prompt_control SET expected_after_sha=?","f".repeat(64));
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,Action.SAVE,lease->origins.requireCurrent(lease,first.after(),pending)))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"subject_id","record_sha","content_sha","result_sha","key_sha","request_sha","submitted_at","audit_until"})
    void independentFirstWitnessTamperingCannotRewriteCreation(String column){
        Object value=column.equals("subject_id")?"intruder":column.equals("submitted_at")?Timestamp.from(f.now.plusSeconds(1)):
                column.equals("audit_until")?Timestamp.from(f.now.plusSeconds(1199)):"f".repeat(64);
        f.independent.update("UPDATE idp_revision_witness SET "+column+"=?",value);
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,Action.SAVE,lease->origins.requireCurrent(lease,first.after(),null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"event","receipt","audit-expired","wrong-action"})
    void corruptOrExpiredOriginAndDifferentActionFailClosed(String fault){
        Action action=Action.SAVE;
        if(fault.equals("event"))f.db.update("UPDATE conp_event SET subject_id='intruder'");
        if(fault.equals("receipt"))f.db.update("UPDATE conp_receipt SET subject_id='intruder'");
        if(fault.equals("audit-expired"))f.now=f.now.plusSeconds(1200);
        if(fault.equals("wrong-action")){f.grant(Action.CREATE);action=Action.CREATE;}
        Action selected=action;
        assertThatThrownBy(()->f.authorization().execute(f.token,f.workspaceId,selected,lease->origins.requireCurrent(lease,first.after(),null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    private Pending reserveSave(){
        var command=new ProductPromptSaveCommand(1,first.header().revisionId(),PromptRevision.Kind.SYSTEM,"New","New approved text",List.of(),List.of(),"internal","zh",0,List.of(),id());
        var revision=command.revision(first.after().promptId(),id(),new PromptRevision.Provenance("owned-new","original-creator",first.receipt().submittedAt(),
                "member",f.now,"SAVE_DRAFT",List.of(),null,f.bindings.get(f.workspaceId).policyRef()));
        var write=new Write(f.workspaceId,revision,1,Operation.SAVE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,"member",
                ProductComparisonJson.hash("save-key"),command.requestSha(f.workspaceId,first.after().promptId()),f.now.plusSeconds(600),f.now.plusSeconds(1200));
        var prepared=f.mainTx.execute(status->f.store.prepare(write));
        var pending=new Pending(first.after().promptId(),f.workspaceId,revision.kind(),1,first.header().revisionId(),first.after().scalarSha(),prepared.after().scalarSha(),
                revision.reference().revisionId(),id(),Operation.SAVE,write.keySha(),write.requestSha(),f.now.plusSeconds(300),prepared.after().containerUntil(),prepared.after().controlUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));return pending;
    }
}
