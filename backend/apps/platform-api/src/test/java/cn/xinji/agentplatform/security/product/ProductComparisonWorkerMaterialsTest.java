package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

/** Actual user original/captured claims and H2 independent authority; Source port is isolated engineering, not formal admission. */
class ProductComparisonWorkerMaterialsTest {
    final ProductComparisonWorkerDelegationBindingTest fixture=new ProductComparisonWorkerDelegationBindingTest();
    ProductComparisonWorkerMaterials materials;
    ProductComparisonWorkerTaskAuthority taskAuthority;
    ProductComparisonWorkerDelegations.Receipt intent;
    MockHttpServletRequest request;
    @BeforeEach void setup() throws Exception {
        fixture.setup();intent=fixture.issue("worker-materials");
        var input=fixture.input;var directory=new JdbcProductTaskDirectory(fixture.db,input.clock);
        taskAuthority=new ProductComparisonWorkerTaskAuthority(fixture.db,fixture.watermark,directory,input.clock,ProductComparisonPreparationTest.ISSUER);
        var reads=new ProductComparisonWorkerReadAuthority(fixture.db,fixture.watermark,directory,input.clock,ProductComparisonPreparationTest.ISSUER);
        materials=new ProductComparisonWorkerMaterials(taskAuthority,new ProductComparisonWorkerIntentAccess(taskAuthority,fixture.application),
                reads,input.inputStore,new ProductComparisonTargetReader(fixture.db,directory,input.access,input.clock,65536),
                input.documents,input.json,input.clock);
        var certificate=mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn("binding-certificate-C".getBytes(StandardCharsets.UTF_8));
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        request=new MockHttpServletRequest();request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate",new X509Certificate[]{certificate});
        when(input.documents.readDelegated(eq(fixture.task),eq(input.candidate.reference()),any())).thenReturn(input.candidate);
        clearInvocations(input.documents);
    }
    ProductComparisonWorkerMaterials.Loaded consume() {
        return materials.consume(request,fixture.task,fixture.view.input().comparisonId(),fixture.command.candidateId(),
                fixture.view.inputSha(),intent.delegationId());
    }
    @Test void consumesExactUserTargetAndNonemptyCapturedClaimsWithoutJwtOrSideEffects() {
        var result=consume();assertThat(result.target().text()).contains("pressure sensor");
        assertThat(result.candidate().original().text()).startsWith("1. In loading apparatus");
        assertThat(result.input().inputSha()).isEqualTo(fixture.view.inputSha());
        assertThat(result.actor().serviceSubject()).isEqualTo(fixture.service);
        assertThat(result.expiresAt()).isEqualTo(intent.expiresAt());
        verify(fixture.input.documents,never()).read(anyString(),any(),any(),anyBoolean());
        assertThat(fixture.input.inputStore.read(fixture.task,fixture.view.input().comparisonId()).candidateProgress())
                .containsExactly(new ProductComparisonInputs.Progress(fixture.command.candidateId(),"QUEUED"));
        assertThat(fixture.count("con3_worker_delegation_event")).isEqualTo(1);fixture.watermark.assertCurrent();
    }
    @Test void stillCurrentCompareCannotReplaceRevokedHumanRead() {
        fixture.watermark.change(()->fixture.db.update("UPDATE id2_grant SET status='REVOKED' WHERE subject_id='member' "
                + "AND resource_type='TASK' AND action_code='READ'"));
        assertThatThrownBy(this::consume).hasMessageContaining("Task READ");verifyNoInteractions(fixture.input.documents);
    }
    @Test void displayComparisonPurposeFailureIsNotAClaimGapOrEmptySuccess() {
        when(fixture.input.documents.readDelegated(anyString(),any(),any())).thenThrow(new IllegalStateException("COMPARISON withdrawn"));
        assertThatThrownBy(this::consume).hasMessage("COMPARISON withdrawn");
    }
    @Test void returnedDocumentCannotReplaceOriginalInputDocumentVersionOrSha() {
        var old=fixture.input.candidate;var m=old.material();
        var changed=new ProductComparisonContracts.MaterialDocument(m.key(),m.sourceId(),m.documentId(),m.documentVersion(),
                m.sourceVersion(),"f".repeat(64),m.originalRef(),m.jsonPointer(),m.branches(),m.gap());
        when(fixture.input.documents.readDelegated(anyString(),any(),any())).thenReturn(
                new ProductComparisonDocuments.Loaded(old.reference(),old.original(),changed,old.authority()));
        assertThatThrownBy(this::consume).hasMessageContaining("originals or input changed");
    }
    @Test void lateSourceWithdrawalCannotEscapeAfterFirstOriginalRead() {
        when(fixture.input.documents.readDelegated(anyString(),any(),any())).thenReturn(fixture.input.candidate)
                .thenThrow(new IllegalStateException("Source withdrawn after first read"));
        assertThatThrownBy(this::consume).hasMessage("Source withdrawn after first read");
    }
    @Test void lateReadRevocationUnderObservedAuthorityRejectsTheWholeBundle() {
        when(fixture.input.documents.readDelegated(anyString(),any(),any())).thenAnswer(call->{
            fixture.watermark.change(()->fixture.db.update("UPDATE id2_grant SET status='REVOKED' WHERE subject_id='member' "
                    + "AND resource_type='TASK' AND action_code='READ'"));return fixture.input.candidate;
        });
        assertThatThrownBy(this::consume).isInstanceOf(IllegalStateException.class);
    }
    @Test void wrongCandidateAndLostTlsDoNotReadAnyOriginal() {
        assertThatThrownBy(()->materials.consume(request,fixture.task,fixture.view.input().comparisonId(),"foreign",
                fixture.view.inputSha(),intent.delegationId())).isInstanceOf(RuntimeException.class);
        request.setSecure(false);assertThatThrownBy(this::consume).hasMessageContaining("TLS");
        verifyNoInteractions(fixture.input.documents);
    }
}
