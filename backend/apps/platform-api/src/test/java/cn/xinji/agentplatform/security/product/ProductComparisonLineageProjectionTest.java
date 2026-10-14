package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Controlled authentication/material/transaction ports plus real pinned file metadata; no DB or model. */
class ProductComparisonLineageProjectionTest {
    @TempDir Path root;
    ProductComparisonParentEvidenceReaderTest f;
    ProductComparisonModelAdmission metadata;
    ProductComparisonLineageProjection projection;
    ProductComparisonModelAdmission.Approval approval;
    final String configJson="configuration", configSha=ProductComparisonJson.hash(configJson);
    Snapshot after;
    @BeforeEach void setup() throws Exception {
        f=new ProductComparisonParentEvidenceReaderTest(); f.setup();
        approval=new ProductComparisonModelAdmission.Approval("approval-v1","component-owner",f.at,f.at,f.at.plusSeconds(60),"task",f.current.inputSha(),Set.of("candidate"),1,f.config);
        var source=mock(ProductSourceAvailability.class); var access=mock(ProductPatentDocumentAccess.class);
        write(approval);
        metadata=new ProductComparisonModelAdmission(new ProductSourceEvidenceFiles(root,65536),f.json,source,access,"approval.json",f.json.sha(approval),f.clock);
        projection=new ProductComparisonLineageProjection(f.reader,metadata,f.facts,f.calculation);
        after=snapshot("current",f.current.inputSha(),"parent",configSha,Set.of("candidate"));
        when(f.calculation.request(any(),eq(f.config))).thenReturn("request");
        when(f.facts.configurationJson(f.config)).thenReturn(configJson);
        when(f.facts.capture(any(),eq(f.config),eq(ProductComparisonJson.hash("request")))).thenReturn(new ProductComparisonLineageFacts.Captured(after,ProductComparisonJson.hash("request")));
        verifyNoInteractions(source,access);
    }
    void write(ProductComparisonModelAdmission.Approval a) throws Exception {Files.writeString(root.resolve("approval.json"),f.json.canonical(a));}
    Snapshot snapshot(String id,String sha,String previous,String cfg,Set<String> candidates) {
        Map<FactKey,String> facts=new HashMap<>(f.snapshot.facts());facts.put(new FactKey(Type.CONFIGURATION,"global"),cfg);
        Set<RowKey> rows=new HashSet<>();for(String c:candidates){facts.put(new FactKey(Type.CANDIDATE,c),f.sha);rows.add(new RowKey(c,List.of(1),"F1"));}
        return new Snapshot("task",id,sha,previous,facts,rows);
    }
    ProductComparisonLineageProjection.View read(){return projection.read("task","current",f.jwt);}
    @Test void newConfigurationChangesInvalidateWithoutModelOrWrite() {
        var v=read();assertThat(v.carried()).isEmpty();assertThat(v.recompute()).containsExactly(new RowKey("candidate",List.of(1),"F1"));
        assertThat(v.invalidated()).isEqualTo(v.recompute());assertThat(v.expiresAt()).isEqualTo(approval.expiresAt());
        verify(f.db,never()).update(anyString(),any(Object[].class));
    }
    @Test void authenticatedMetadataIsNotAnExecutionPermit() {
        var spy=spy(metadata);var p=new ProductComparisonLineageProjection(f.reader,spy,f.facts,f.calculation);
        p.read("task","current",f.jwt);verify(spy,times(2)).approval();verify(spy,never()).require(any());
    }
    @Test void changedPinnedBytesRefuseButExistingParentReadStillWorks() throws Exception {
        Files.writeString(root.resolve("approval.json"),"{}");assertThatThrownBy(this::read).hasMessageContaining("digest changed");
        assertThat(f.reader.read("task","current",f.jwt).reports()).hasSize(1);
    }
    @Test void incorrectInputAndNarrowCandidateSetsCannotDefineFullProjection() {
        var m=mock(ProductComparisonModelAdmission.class);var p=new ProductComparisonLineageProjection(f.reader,m,f.facts,f.calculation);
        when(m.approval()).thenReturn(new ProductComparisonModelAdmission.Approval("v","owner",f.at,f.at,f.at.plusSeconds(60),"task",f.sha,Set.of("candidate"),1,f.config));
        assertThatThrownBy(()->p.binding(f.current)).hasMessageContaining("UNAVAILABLE");
        when(m.approval()).thenReturn(new ProductComparisonModelAdmission.Approval("v","owner",f.at,f.at,f.at.plusSeconds(60),"task",f.current.inputSha(),Set.of("other"),1,f.config));
        assertThatThrownBy(()->p.binding(f.current)).hasMessageContaining("UNAVAILABLE");
    }
    @Test void expiryDuringLastTaskReadRefusesProjection() {
        when(f.loader.require("task",f.jwt,cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.Action.READ)).thenAnswer(call->{
            if(mockingDetails(f.loader).getInvocations().stream().filter(i->i.getMethod().getName().equals("require")).count()>=3)f.now=f.at.plusSeconds(60);return f.task;
        });assertThatThrownBy(this::read).hasMessageContaining("NOT_APPROVED");
    }
    @Test void metadataMutationAtTerminalReadRefuses() {
        when(f.loader.require("task",f.jwt,cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.Action.READ)).thenAnswer(call->{
            if(mockingDetails(f.loader).getInvocations().stream().filter(i->i.getMethod().getName().equals("require")).count()>=3)Files.writeString(root.resolve("approval.json"),"{}");return f.task;
        });assertThatThrownBy(this::read).hasMessageContaining("digest changed");
    }
    @Test void noReportsMeansAllCurrentRowsPendingWithoutBackfill() {
        var e=new ProductComparisonParentEvidenceReader.Evidence(f.current,f.parent,List.of());
        var v=ProductComparisonLineageProjection.aggregate(e,after,"v",configSha,f.at.plusSeconds(60));
        assertThat(v.recompute()).containsExactlyElementsOf(after.expectedRows());assertThat(v.carried()).isEmpty();assertThat(v.invalidated()).isEmpty();
    }
    @Test void perReportConfigurationsAreIndependentAndRecomputeIsGlobalComplement() {
        var next=snapshot("current",f.current.inputSha(),"parent",configSha,Set.of("candidate","B","new"));
        var a=snapshot("parent",f.parent.inputSha(),null,configSha,Set.of("candidate","B"));
        var b=snapshot("parent",f.parent.inputSha(),null,f.sha,Set.of("candidate","B"));
        var proofA=proof(a,"candidate","A"); var proofB=proof(b,"B","B");
        var e=new ProductComparisonParentEvidenceReader.Evidence(f.current,f.parent,List.of(new ProductComparisonParentEvidenceReader.ParentReport(a,proofA),new ProductComparisonParentEvidenceReader.ParentReport(b,proofB)));
        var v=ProductComparisonLineageProjection.aggregate(e,next,"v",configSha,f.at.plusSeconds(60));
        assertThat(v.carried()).hasSize(1);assertThat(v.carried().getFirst().key().candidateId()).isEqualTo("candidate");
        assertThat(v.recompute()).extracting(RowKey::candidateId).containsExactly("B","new");
        assertThat(v.invalidated()).extracting(RowKey::candidateId).containsExactly("B");
        assertThatThrownBy(()->v.carried().clear()).isInstanceOf(UnsupportedOperationException.class);
        var duplicate=new ProductComparisonParentEvidenceReader.Evidence(f.current,f.parent,List.of(e.reports().getFirst(),e.reports().getFirst()));
        assertThatThrownBy(()->ProductComparisonLineageProjection.aggregate(duplicate,next,"v",configSha,f.at.plusSeconds(60))).hasMessageContaining("UNAVAILABLE");
    }
    SourceReport proof(Snapshot s,String candidate,String report){
        Set<FactKey> deps=new HashSet<>(s.facts().keySet());deps.removeIf(k->k.type()==Type.CANDIDATE&&!k.id().equals(candidate));
        return new SourceReport("parent",f.parent.inputSha(),candidate,report,f.sha,FULL_FEATURE_CONTEXT,List.of(new RetainedRow(new RowKey(candidate,List.of(1),"F1"),f.sha,deps)));
    }
    @Test void readAssemblyDoesNotValidateMissingConfigurationAtStartup() {
        var p=new ProductComparisonParentEvidenceConfiguration().productComparisonLineageProjection(f.reader,mock(ProductSourceEvidenceFiles.class),f.json,f.sources,f.calculation,f.db,mock(ProductGrantWatermark.class),"issuer","","");
        assertThat(p).isNotNull();verifyNoInteractions(f.db);
    }
    @Test void controllerNoStoreAndFailureDoesNotLeakMetadataOrMaskDeniedIdentity() {
        var p=mock(ProductComparisonLineageProjection.class);var c=new ProductComparisonLineageController(p);
        when(p.read("task","current",f.jwt)).thenReturn(ProductComparisonLineageProjection.aggregate(new ProductComparisonParentEvidenceReader.Evidence(f.current,f.parent,List.of()),after,"v",configSha,f.at.plusSeconds(60)));
        var response=c.read("task","current",f.jwt);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(c.unavailable().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);assertThat(c.unavailable().getBody()).containsOnlyKeys("code");
        when(p.read("task","current",f.jwt)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->c.read("task","current",f.jwt)).isInstanceOf(ResponseStatusException.class);
    }
}
