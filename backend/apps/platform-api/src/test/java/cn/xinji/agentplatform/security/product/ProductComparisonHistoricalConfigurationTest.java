package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real controlled file bytes, isolated metadata tuples; no DB, DDL, native-proof or runtime-admission claim. */
class ProductComparisonHistoricalConfigurationTest {
    @TempDir Path root;
    final ProductComparisonJson json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final Instant now=Instant.parse("2026-10-12T00:00:00Z");
    final ProductSourceAvailability sources=mock(ProductSourceAvailability.class);
    final ProductPatentDocumentAccess access=mock(ProductPatentDocumentAccess.class);
    final ModelGateway.Configuration configuration=config("model","https://component.invalid/v1","OPENAI_CHAT_COMPLETIONS",4096);
    final ProductComparisonInputs.View input=view(List.of(new ProductComparisonInputs.Candidate("A",0),new ProductComparisonInputs.Candidate("B",1)),now.plusSeconds(300));
    ProductComparisonModelAdmission policy;
    @BeforeEach void setup() {
        policy=new ProductComparisonModelAdmission(new ProductSourceEvidenceFiles(root,65536),json,sources,access,
                "current-not-selected.json","a".repeat(64),Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void originalApprovalForAllCandidatesProvesSharedConfigurationNotJustInvokedB() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        var evidence=policy.configurationEvidence("historical.json",material(sha,configuration),input);
        assertThat(evidence.configuration()).isEqualTo(configuration);
        assertThat(evidence.evidenceSha()).isEqualTo(sha);assertThat(evidence.expiresAt()).isEqualTo(input.input().expiresAt());
        verifyNoInteractions(sources,access);
        assertThatThrownBy(policy::approval).hasMessage("Source evidence is unavailable");
    }
    @Test void originalCurrentApprovalAndDeploymentValidationStillUseTheirSelectedFile() throws Exception {
        var value=approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600));
        String sha=save(value);
        var current=new ProductComparisonModelAdmission(new ProductSourceEvidenceFiles(root,65536),json,sources,access,
                "historical.json",sha,Clock.fixed(now,ZoneOffset.UTC));
        assertThat(current.approval()).isEqualTo(value);current.validateDeployment();
        verifyNoInteractions(sources,access);
    }
    @Test void approvalOfOnlyBCannotProveAConfigurationEvenWhenBHasNativeMaterial() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("B"),configuration,now,now.plusSeconds(600)));
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration),input)).hasMessageContaining("NOT_APPROVED");
    }
    @Test void widerCandidateScopeIsNotAnExactInputBinding() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B","C"),configuration,now,now.plusSeconds(600)));
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration),input)).hasMessageContaining("NOT_APPROVED");
    }
    @Test void wrongTaskOrInputOriginalIsRejected() throws Exception {
        for(var a:List.of(approval("other",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)),
                approval("task","d".repeat(64),Set.of("A","B"),configuration,now,now.plusSeconds(600)))) {
            String sha=save(a);
            assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration),input)).hasMessageContaining("NOT_APPROVED");
        }
    }
    @Test void wrongMaterialCoordinatesCannotLocateAConfigurationForThisInput() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        for(var tuple:List.of(List.of("other","parent",input.inputSha(),"root","B"),List.of("task","other",input.inputSha(),"root","B"),
                List.of("task","parent","d".repeat(64),"root","B"),List.of("task","parent",input.inputSha(),"other","B"),
                List.of("task","parent",input.inputSha(),"root","C"))) {
            var m=new ProductComparisonLineageStore.Material("RESERVATION_MATERIAL_V1","attempt",tuple.get(4),sha,"e".repeat(64),
                    tuple.get(0),tuple.get(1),tuple.get(2),tuple.get(3),json.canonical(configuration),List.of(),List.of());
            assertThatThrownBy(()->policy.configurationEvidence("historical.json",m,input)).hasMessageContaining("NOT_APPROVED");
        }
    }
    @Test void missingOriginalOrChangedOriginalBytesCannotBeReplacedByMaterialConfiguration() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        var material=material(sha,configuration);
        Files.writeString(root.resolve("historical.json"),Files.readString(root.resolve("historical.json"))+" ");
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material,input)).hasMessage("Source evidence digest changed");
        Files.delete(root.resolve("historical.json"));
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material,input)).hasMessage("Source evidence is unavailable");
        verifyNoInteractions(sources,access);
    }
    @Test void sameVersionDoesNotAllowDifferentModelEndpointOrBudget() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        for(var changed:List.of(config("other","https://component.invalid/v1","OPENAI_CHAT_COMPLETIONS",4096),
                config("model","https://other.invalid/v1","OPENAI_CHAT_COMPLETIONS",4096),
                config("model","https://component.invalid/v1","OPENAI_CHAT_COMPLETIONS",8192)))
            assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,changed),input)).hasMessageContaining("NOT_APPROVED");
    }
    @Test void expiredOrFutureApprovalCannotBeRevivedAsHistoricalConfiguration() throws Exception {
        for(var a:List.of(approval("task",input.inputSha(),Set.of("A","B"),configuration,now.minusSeconds(20),now),
                approval("task",input.inputSha(),Set.of("A","B"),configuration,now.plusSeconds(1),now.plusSeconds(600)))) {
            String sha=save(a);
            assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration),input)).hasMessageContaining("NOT_APPROVED");
        }
    }
    @Test void approvalDeadlineIsRetainedSeparatelyFromLongerInputLifetime() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(30)));
        assertThat(policy.configurationEvidence("historical.json",material(sha,configuration),input).expiresAt()).isEqualTo(now.plusSeconds(30));
    }
    @Test void insecureEndpointOrUnknownProtocolCannotProvideConfigurationEvidence() throws Exception {
        for(var changed:List.of(config("model","http://component.invalid/v1","OPENAI_CHAT_COMPLETIONS",4096),
                config("model","https://component.invalid/v1","OTHER_PROTOCOL",4096))) {
            String sha=save(approval("task",input.inputSha(),Set.of("A","B"),changed,now,now.plusSeconds(600)));
            assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,changed),input)).hasMessageContaining("NOT_APPROVED");
        }
    }
    @Test void duplicateInputCandidatesOrChangedEnvelopeDigestRejectBeforeAnyAuthorityUse() throws Exception {
        String sha=save(approval("task",input.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        var duplicate=view(List.of(new ProductComparisonInputs.Candidate("A",0),new ProductComparisonInputs.Candidate("A",1)),now.plusSeconds(300));
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration,duplicate),duplicate)).hasMessageContaining("NOT_APPROVED");
        var changed=new ProductComparisonInputs.View(input.input(),"d".repeat(64),input.candidateProgress());
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration),changed)).hasMessageContaining("NOT_APPROVED");
    }
    @Test void liveApprovalCannotExtendExpiredInputEvidence() throws Exception {
        var expired=view(input.input().candidates(),now);
        String sha=save(approval("task",expired.inputSha(),Set.of("A","B"),configuration,now,now.plusSeconds(600)));
        assertThatThrownBy(()->policy.configurationEvidence("historical.json",material(sha,configuration,expired),expired)).hasMessageContaining("NOT_APPROVED");
    }
    private ProductComparisonModelAdmission.Approval approval(String task,String inputSha,Set<String> candidates,ModelGateway.Configuration config,
            Instant approvedAt,Instant until) {
        return new ProductComparisonModelAdmission.Approval("v1","component-owner-not-real-approval",approvedAt,approvedAt,until,task,inputSha,candidates,2,config);
    }
    private String save(ProductComparisonModelAdmission.Approval approval) throws Exception {
        String raw=" \n"+json.canonical(approval)+"\n";
        Files.write(root.resolve("historical.json"),raw.getBytes(StandardCharsets.UTF_8));
        return ProductComparisonJson.hash(raw);
    }
    private ProductComparisonLineageStore.Material material(String sha,ModelGateway.Configuration config) {
        return material(sha,config,input);
    }
    private ProductComparisonLineageStore.Material material(String sha,ModelGateway.Configuration config,ProductComparisonInputs.View view) {
        return new ProductComparisonLineageStore.Material("RESERVATION_MATERIAL_V1","attempt","B",sha,"e".repeat(64),
                "task","parent",view.inputSha(),"root",json.canonical(config),List.of(),List.of());
    }
    private ProductComparisonInputs.View view(List<ProductComparisonInputs.Candidate> candidates,Instant until) {
        var envelope=new ProductComparisonInputs.Envelope("parent","task",2,"root",null,null,"preparation","a".repeat(64),"{}",
                "issuer","subject","key","a".repeat(64),now,until,candidates);
        return new ProductComparisonInputs.View(envelope,json.sha(envelope),List.of());
    }
    private ModelGateway.Configuration config(String model,String endpoint,String protocol,int maxBytes) {
        return new ModelGateway.Configuration("id","v1","provider",model,URI.create(endpoint),protocol,Duration.ofSeconds(20),maxBytes,4096,
                Set.of("PATENT_COMPARISON"),Set.of("APPROVED_COMPARISON_ORIGINALS"));
    }
}
