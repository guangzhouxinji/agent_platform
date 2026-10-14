package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import java.nio.file.*;
import java.time.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Explicit controlled approval evidence and field-use negatives; not a real responsible-party approval. */
class ProductComparisonModelAdmissionTest {
    @TempDir Path root;ProductComparisonWorkerMaterialsTest f;ProductSourceAvailability sources;
    ProductPatentDocumentAccess access;ProductComparisonModelAdmission.Approval approval;
    @BeforeEach void setup() throws Exception {
        f=new ProductComparisonWorkerMaterialsTest();f.setup();sources=mock(ProductSourceAvailability.class);access=mock(ProductPatentDocumentAccess.class);
        var loaded=f.consume();when(sources.require(anyString(),eq("CLAIMS"),anyString())).thenReturn(loaded.candidate().authority().source());
        var config=new ModelGateway.Configuration("model","v1","provider","model",URI.create("https://example.invalid/api"),"OPENAI_CHAT_COMPLETIONS",Duration.ofSeconds(20),65536,65536,
                Set.of("PATENT_COMPARISON"),Set.of("APPROVED_COMPARISON_ORIGINALS"));
        var now=f.fixture.input.clock.instant();
        when(access.modelInputUntil(anyString(),any(),any())).thenReturn(now.plusSeconds(50));
        when(sources.useUntil(anyString(),anyString(),anyString())).thenReturn(now.plusSeconds(45));
        approval=new ProductComparisonModelAdmission.Approval("v1","mechanical-test-not-professional-owner",now,now,now.plusSeconds(60),
                f.fixture.task,f.fixture.view.inputSha(),Set.of(f.fixture.command.candidateId()),2,config);
    }
    ProductComparisonModelAdmission policy(ProductComparisonModelAdmission.Approval value) throws Exception {
        byte[] bytes=f.fixture.input.json.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(root.resolve("approval.json"),bytes);
        return new ProductComparisonModelAdmission(new ProductSourceEvidenceFiles(root,65536),f.fixture.input.json,sources,access,"approval.json",ProductCapturedArchive.digest(bytes),f.fixture.input.clock);
    }
    @Test void exactEvidenceStillRequiresSeparateModelAndSaveUsesAndModelInputGrant() throws Exception {
        var loaded=f.consume();var policy=policy(approval);var permit=policy.require(loaded);assertThat(permit.approval()).isEqualTo(approval);assertThat(permit.expiresAt()).isEqualTo(f.fixture.input.clock.instant().plusSeconds(45));
        verify(sources).require(loaded.candidate().reference().sourceId(),"CLAIMS","MODEL");verify(sources).require(loaded.candidate().reference().sourceId(),"CLAIMS","SAVE");
        verify(access).requireDelegatedModelInput(f.fixture.task,loaded.candidate().authority().source(),loaded.actor());
    }
    @Test void missingModelUseDoesNotBecomeApprovedFromComparison() throws Exception {
        var policy=policy(approval);when(sources.require(anyString(),eq("CLAIMS"),eq("MODEL"))).thenThrow(new IllegalStateException("MODEL use missing"));
        assertThatThrownBy(()->policy.require(f.consume())).hasMessage("MODEL use missing");
    }
    @Test void missingSaveUseRefusesResponseRetention() throws Exception {
        var policy=policy(approval);when(sources.require(anyString(),eq("CLAIMS"),eq("SAVE"))).thenThrow(new IllegalStateException("SAVE use missing"));
        assertThatThrownBy(()->policy.require(f.consume())).hasMessage("SAVE use missing");
    }
    @Test void absentSeparateModelInputGrantRefusesDisclosure() throws Exception {
        var policy=policy(approval);when(access.requireDelegatedModelInput(anyString(),any(),any())).thenThrow(new IllegalStateException("MODEL_INPUT grant missing"));
        assertThatThrownBy(()->policy.require(f.consume())).hasMessage("MODEL_INPUT grant missing");
    }
    @Test void alteredControlledEvidenceIsRejected() throws Exception {
        var policy=policy(approval);Files.writeString(root.resolve("approval.json"),"{}");assertThatThrownBy(()->policy.require(f.consume())).isInstanceOf(IllegalStateException.class);
    }
    @Test void anotherInputCannotUseThisApproval() throws Exception {
        var other=new ProductComparisonModelAdmission.Approval(approval.version(),approval.responsibleParty(),approval.approvedAt(),approval.validFrom(),approval.expiresAt(),
                approval.taskId(),"d".repeat(64),approval.candidates(),approval.maxCalls(),approval.configuration());
        var policy=policy(other);assertThatThrownBy(()->policy.require(f.consume())).hasMessageContaining("NOT_APPROVED");
    }
    @Test void expiredApprovalCannotBeRevivedByLiveTaskGrant() throws Exception {
        var expired=new ProductComparisonModelAdmission.Approval(approval.version(),approval.responsibleParty(),approval.approvedAt(),approval.validFrom(),approval.approvedAt(),
                approval.taskId(),approval.inputSha(),approval.candidates(),approval.maxCalls(),approval.configuration());
        var policy=policy(expired);assertThatThrownBy(()->policy.require(f.consume())).hasMessageContaining("NOT_APPROVED");
    }
    @Test void narrowerLiveModelInputDeadlineCapsDisclosureWithoutChangingApproval() throws Exception {
        var now=f.fixture.input.clock.instant();when(access.modelInputUntil(anyString(),any(),any())).thenReturn(now.plusSeconds(5));
        assertThat(policy(approval).require(f.consume()).expiresAt()).isEqualTo(now.plusSeconds(5));
    }
    @Test void missingDeadlineFailsClosedRatherThanTreatingItAsUnlimited() throws Exception {
        when(sources.useUntil(anyString(),anyString(),anyString())).thenReturn(null);
        assertThatThrownBy(()->policy(approval).require(f.consume())).hasMessageContaining("NOT_APPROVED");
    }
}
