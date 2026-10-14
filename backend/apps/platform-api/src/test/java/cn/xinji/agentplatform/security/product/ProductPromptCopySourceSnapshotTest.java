package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptCopySourceSnapshotTest {
    @TempDir Path root;
    ProductPromptCopySourceFixture x;
    ProductPromptWorkspaceAuthorizationFixture f;
    @BeforeEach void setup()throws Exception{x=new ProductPromptCopySourceFixture(root);f=x.f;}
    @Test void exactContentAndDefaultsAreReadOnceWhileRechecksUseOnlyScalars(){
        var held=new AtomicReference<ProductPromptCopySourceSnapshot.Snapshot>();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var snapshot=x.snapshot(lease);held.set(snapshot);
            assertThat(snapshot.revision()).isEqualTo(x.revision);assertThat(snapshot.source().sourceRecordSha()).isEqualTo(x.header.recordSha());
            assertThat(snapshot.source().sourceBodyUntil()).isEqualTo(x.header.bodyUntil());
            snapshot.requireCurrent();snapshot.revision();snapshot.requireCurrent();assertThat(f.bodyReads.get()).isEqualTo(1);return null;
        });
        assertThatThrownBy(held.get()::source).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(held.get()::revision).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.authorization().execute(f.token,x.targetWorkspace,Action.READ,lease->null)).isInstanceOf(ProductPromptAccessDenied.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_prompt",Integer.class)).isEqualTo(1);
    }
    @Test void exactOldRevisionRemainsCopyableAfterHeadAdvancesReady(){
        x.advanceSource();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var snapshot=x.snapshot(lease);assertThat(snapshot.revision()).isEqualTo(x.revision);
            assertThat(snapshot.source().sourceRevisionNumber()).isEqualTo(1);return null;
        });assertThat(f.bodyReads.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"expired","pending","wrong-source","content","witness"})
    void sourceCannotBeReadThroughExpiredPendingOrMismatchedScalars(String fault){
        if(fault.equals("expired"))f.now=x.header.bodyUntil();if(fault.equals("pending"))x.sourcePending();
        if(fault.equals("witness"))f.independent.update("UPDATE idp_revision_witness SET record_sha=?","f".repeat(64));
        String workspace=fault.equals("wrong-source")?x.targetWorkspace:f.workspaceId;
        String sha=fault.equals("content")?"f".repeat(64):x.header.contentSha();
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->
                x.snapshots().load(lease,workspace,x.head.promptId(),x.header.revisionId(),sha,id()))).isInstanceOf(RuntimeException.class);
        assertThat(f.bodyReads.get()).isZero();
    }
    @Test void storedBodyTamperingIsRejectedByOriginalDigest(){
        f.db.update("UPDATE conp_revision SET revision_json='{}'");
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,x::snapshot)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"body-deadline","header","head","source-pending","source-grant"})
    void finalCheckRejectsDriftWithoutRereadingBody(String fault){
        assertThatThrownBy(()->f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var snapshot=x.snapshot(lease);
            switch(fault){
                case "body-deadline"->f.now=x.header.bodyUntil();
                case "header"->f.db.update("UPDATE conp_revision SET record_sha=?","f".repeat(64));
                case "head"->f.db.update("UPDATE conp_prompt SET scalar_sha=?","f".repeat(64));
                case "source-pending"->x.sourcePending();
                default->f.revoke(Action.READ);
            }
            snapshot.requireCurrent();return null;
        })).isInstanceOf(RuntimeException.class);assertThat(f.bodyReads.get()).isEqualTo(1);
    }
    @Test void snapshotCannotCrossThreadSuspensionOrLaterLease(){
        var held=new AtomicReference<ProductPromptCopySourceSnapshot.Snapshot>();
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            var snapshot=x.snapshot(lease);held.set(snapshot);var failure=new AtomicReference<Throwable>();
            var thread=new Thread(()->{try{snapshot.revision();}catch(Throwable error){failure.set(error);}});
            thread.start();try{thread.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            var suspend=new TransactionTemplate(f.mainManager);suspend.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
            suspend.executeWithoutResult(status->f.authorityTx.executeWithoutResult(independent->{
                assertThatThrownBy(snapshot::requireCurrent).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(snapshot::source).isInstanceOf(IllegalStateException.class);
            }));snapshot.requireCurrent();return null;
        });
        f.authorization().executeCopy(f.token,f.workspaceId,x.targetWorkspace,lease->{
            assertThatThrownBy(held.get()::requireCurrent).isInstanceOf(IllegalStateException.class);return null;
        });assertThat(f.bodyReads.get()).isEqualTo(1);
    }
}
