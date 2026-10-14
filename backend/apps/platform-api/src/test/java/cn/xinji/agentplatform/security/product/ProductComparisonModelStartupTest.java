package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** Existing approval contract validated at startup; controlled local evidence, not real approval. */
class ProductComparisonModelStartupTest extends ProductComparisonModelAdmissionTest {
    @Test void validDeploymentDoesNotCallSourcesOrGrantAuthority() throws Exception {
        policy(approval).validateDeployment();verifyNoInteractions(sources,access);
    }
    @Test void absentApprovalFileRejectsBeforeModelDispatch() throws Exception {
        var p=policy(approval);Files.delete(root.resolve("approval.json"));assertThatThrownBy(p::validateDeployment).isInstanceOf(IllegalStateException.class);
    }
    @Test void alteredApprovalCannotOpenDeployment() throws Exception {
        var p=policy(approval);Files.writeString(root.resolve("approval.json"),"{}");assertThatThrownBy(p::validateDeployment).isInstanceOf(IllegalStateException.class);
    }
    @Test void expiredApprovalCannotOpenDeployment() throws Exception {
        var expired=new ProductComparisonModelAdmission.Approval(approval.version(),approval.responsibleParty(),approval.approvedAt(),approval.validFrom(),approval.approvedAt(),approval.taskId(),approval.inputSha(),approval.candidates(),approval.maxCalls(),approval.configuration());
        assertThatThrownBy(()->policy(expired).validateDeployment()).hasMessageContaining("NOT_APPROVED");
    }
    @Test void insecureModelEndpointCannotOpenDeployment() throws Exception {
        var c=approval.configuration();var http=new cn.xinji.agentplatform.runtime.ModelGateway.Configuration(c.id(),c.version(),c.provider(),c.model(),java.net.URI.create("http://example.invalid/api"),c.protocol(),c.timeout(),c.maxInputBytes(),c.maxResponseBytes(),c.purposes(),c.dataClasses());
        var insecure=new ProductComparisonModelAdmission.Approval(approval.version(),approval.responsibleParty(),approval.approvedAt(),approval.validFrom(),approval.expiresAt(),approval.taskId(),approval.inputSha(),approval.candidates(),approval.maxCalls(),http);
        assertThatThrownBy(()->policy(insecure).validateDeployment()).hasMessage("APPROVED_MODEL_TLS_CONFIGURATION_REQUIRED");
    }
}
