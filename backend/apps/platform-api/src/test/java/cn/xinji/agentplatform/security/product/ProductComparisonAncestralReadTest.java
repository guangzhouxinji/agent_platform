package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Owning-transaction callbacks and actual approval bytes; no DB, DDL, model dispatch or runtime claim. */
class ProductComparisonAncestralReadTest {
    @TempDir Path root;
    ProductComparisonParentEvidenceReaderTest f;
    ProductComparisonParentEvidenceReader reader;
    ProductComparisonLineageProjection projection;
    ProductComparisonModelAdmission metadata;
    final Map<String,ProductComparisonInputs.View> views=new HashMap<>();
    final Map<String,Snapshot> snapshots=new HashMap<>();
    final Map<String,String> approvalShas=new HashMap<>();
    final List<ProductComparisonModelAdmission.HistoricalReference> refs=new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        f=new ProductComparisonParentEvidenceReaderTest();f.setup();
        var b=new ProductComparisonDocuments.Loaded(f.document.reference(),null,
                new ProductComparisonContracts.MaterialDocument("B","source","doc","version","source-version",f.sha,"original","/claims",List.of(),""),f.document.authority());
        when(f.loaded.candidates()).thenReturn(List.of(f.document,b));
        var rootInput=view("root",null,List.of(new ProductComparisonInputs.Progress("candidate","COMMITTED"),new ProductComparisonInputs.Progress("B","COMMITTED")));
        var middle=view("parent","root",List.of(new ProductComparisonInputs.Progress("candidate","QUEUED"),new ProductComparisonInputs.Progress("B","COMMITTED")));
        var current=view("current","parent",List.of());
        for(var v:List.of(rootInput,middle,current)) {
            String id=v.input().comparisonId();views.put(id,v);when(f.inputs.read("task",id,f.jwt)).thenReturn(v);
            var a=new ProductComparisonModelAdmission.Approval("v1","test-owner",f.at,f.at,f.at.plusSeconds(300),"task",v.inputSha(),Set.of("candidate","B"),2,f.config);
            Files.writeString(root.resolve(id+".json"),f.json.canonical(a));approvalShas.put(id,f.json.sha(a));
            refs.add(new ProductComparisonModelAdmission.HistoricalReference(f.json.sha(a),id+".json"));
            snapshots.put(id,snapshot(v,id.equals("root")?"1":id.equals("parent")?"2":"3"));
        }
        when(f.matrices.ids("task","root")).thenReturn(List.of("A0","B0"));
        when(f.matrices.ids("task","parent")).thenReturn(List.of("B1"));
        nativeReport("root","candidate","A0");nativeReport("root","B","B0");nativeReport("parent","B","B1");
        when(f.calculation.request(any(),eq(f.config))).thenAnswer(call -> "request-"+((ProductComparisonWorkerMaterials.Loaded)call.getArgument(0)).input().input().comparisonId());
        when(f.facts.capture(any(),eq(f.config),anyString())).thenAnswer(call -> {
            var loaded=(ProductComparisonWorkerMaterials.Loaded)call.getArgument(0);
            return new ProductComparisonLineageFacts.Captured(snapshots.get(loaded.input().input().comparisonId()),call.getArgument(2));
        });
        when(f.facts.configurationJson(f.config)).thenReturn(f.json.canonical(f.config));
        assemble(f.json.canonical(refs));
    }

    void assemble(String mapping) {
        metadata=spy(new ProductComparisonModelAdmission(new ProductSourceEvidenceFiles(root,65536),f.json,f.sources,
                mock(ProductPatentDocumentAccess.class),"current.json",approvalShas.get("current"),f.clock,mapping));
        reader=new ProductComparisonParentEvidenceReader(f.loader,f.inputs,f.inputStore,f.matrices,f.lineage,f.sources,f.db,f.tx,f.json,f.calculation,f.facts,f.clock,metadata);
        projection=new ProductComparisonLineageProjection(reader,metadata,f.facts,f.calculation);
    }
    ProductComparisonInputs.View view(String id,String previous,List<ProductComparisonInputs.Progress> progress) {
        var e=f.current.input();
        var input=new ProductComparisonInputs.Envelope(id,"task",id.equals("current")?2:1,previous,e.command(),e.confirmedFeatures(),
                e.preparationId(),e.materialSha(),e.currentPreparationSnapshot(),e.actorIssuer(),e.actorSubject(),e.requestId(),e.requestSha(),
                e.createdAt(),e.expiresAt(),List.of(new ProductComparisonInputs.Candidate("candidate",0),new ProductComparisonInputs.Candidate("B",1)));
        return new ProductComparisonInputs.View(input,f.json.sha(input),progress);
    }
    Snapshot snapshot(ProductComparisonInputs.View v,String b) {
        Map<FactKey,String> facts=new HashMap<>(f.snapshot.facts());
        facts.put(new FactKey(Type.CANDIDATE,"B"),b.repeat(64));
        facts.put(new FactKey(Type.CONFIGURATION,"global"),ProductComparisonJson.hash(f.json.canonical(f.config)));
        return new Snapshot("task",v.input().comparisonId(),v.inputSha(),v.input().previousComparisonId(),facts,
                Set.of(new RowKey("candidate",List.of(1),"F1"),new RowKey("B",List.of(1),"F1")));
    }
    void nativeReport(String id,String candidate,String reportId) {
        var input=views.get(id);var snapshot=snapshots.get(id);
        var row=new PatentComparisonMatrix.Row(candidate,List.of(1),"F1",PatentComparisonMatrix.Relation.UNKNOWN,List.of(),List.of(),"unknown","unknown",List.of(),List.of());
        var value=new ProductComparisonMatrixResults.Package("task",id,candidate,input.inputSha(),reportId+"-attempt",1,reportId,1,
                "PATENT_FEATURE_MATRIX_V1","worker","v1",approvalShas.get(id),f.sha,f.at,f.at.plusSeconds(500),List.of(row));
        String raw=" \n"+f.json.canonical(value)+"\n",sha=ProductComparisonJson.hash(raw);
        var report=new ProductComparisonMatrixResults.Report(value,sha,null);
        var material=new ProductComparisonLineageStore.Material("RESERVATION_MATERIAL_V1",value.attemptId(),candidate,approvalShas.get(id),f.sha,
                "task",id,input.inputSha(),input.input().previousComparisonId(),f.json.canonical(f.config),List.of(),List.of());
        Set<FactKey> dependencies=new HashSet<>(snapshot.facts().keySet());dependencies.removeIf(k -> k.type()==Type.CANDIDATE && !k.id().equals(candidate));
        var proof=new SourceReport(id,input.inputSha(),candidate,reportId,sha,FULL_FEATURE_CONTEXT,
                List.of(new RetainedRow(new RowKey(candidate,List.of(1),"F1"),f.json.sha(row),dependencies)));
        when(f.matrices.read("task",id,reportId)).thenReturn(report);
        when(f.matrices.rawResponse(value.attemptId())).thenReturn(reportId+"-response");
        when(f.lineage.material("task",value.attemptId())).thenReturn(material);
        when(f.lineage.capture(material)).thenReturn(new ProductComparisonLineageFacts.Captured(snapshot,f.sha));
        when(f.calculation.validate(any(),eq(reportId+"-response"))).thenReturn(new ProductComparisonMatrixCalculation.Validated(List.of(row),f.sha));
        when(f.db.queryForList(startsWith("SELECT package_json"),eq("task"),eq(id),eq(reportId))).thenReturn(List.of(Map.of("package_json",raw,"report_sha",sha)));
        when(f.lineage.read(raw,sha)).thenReturn(proof);
    }
    @Test void twoBCorrectionsKeepOriginalAAndOnlyBPendingWithoutNewCalls() {
        var v=projection.read("task","current",f.jwt);
        assertThat(v.carried()).hasSize(1);var a=v.carried().getFirst();
        assertThat(a.comparisonId()).isEqualTo("root");assertThat(a.inputSha()).isEqualTo(views.get("root").inputSha());assertThat(a.reportId()).isEqualTo("A0");
        assertThat(v.recompute()).containsExactly(new RowKey("B",List.of(1),"F1"));
        assertThat(v.ancestry()).extracting(ProductComparisonParentEvidenceReader.Ancestor::comparisonId).containsExactly("parent","root");
        assertThat(v.expiresAt()).isEqualTo(f.at.plusSeconds(300));
        ProductComparisonRecomputeController.requirePending(v,v.inputSha(),v.configurationSha(),"B",f.at);
        assertThatThrownBy(()->ProductComparisonRecomputeController.requirePending(v,v.inputSha(),v.configurationSha(),"candidate",f.at))
                .hasMessage("COMPARISON_RECOMPUTE_UNAVAILABLE");
        verify(metadata,never()).require(any());verify(f.db,never()).update(anyString(),any(Object[].class));
        verify(f.sources,never()).require(anyString(),anyString(),eq("MODEL"));
    }
    @Test void reportGetKeepsSourceBytesAndDirectParentSeparate() {
        var v=reader.report("task","current","A0",f.jwt);
        assertThat(v.previousComparisonId()).isEqualTo("parent");assertThat(v.sourceComparisonId()).isEqualTo("root");
        assertThat(v.sourceInputSha()).isEqualTo(v.report().result().inputSha());assertThat(v.originalJson()).startsWith(" \n").endsWith("\n");
        assertThat(ProductComparisonJson.hash(v.originalJson())).isEqualTo(v.report().reportSha());
    }
    @Test void nearestNativeWinsAndNoOlderNodeIsReadWhenCovered() {
        nativeReport("parent","candidate","A1");when(f.matrices.ids("task","parent")).thenReturn(List.of("A1","B1"));
        var v=projection.read("task","current",f.jwt);assertThat(v.carried()).extracting(CarryForwardFrom::reportId).containsExactly("A1");
        verify(f.inputs,never()).read("task","root",f.jwt);
    }
    @Test void missingMappingRejectsInsteadOfFillingFromCurrentConfiguration() {
        assemble("[]");assertThatThrownBy(()->projection.read("task","current",f.jwt)).hasMessageContaining("NOT_APPROVED");
    }
    @Test void missingIntermediateNativeAnchorRejects() {
        var v=view("parent","root",List.of());when(f.inputs.read("task","parent",f.jwt)).thenReturn(v);when(f.matrices.ids("task","parent")).thenReturn(List.of());
        assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void changedHistoricalApprovalOriginalRejects() throws Exception {
        Files.writeString(root.resolve("parent.json"),"{}");assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessageContaining("digest changed");
    }
    @Test void historyCycleRejects() {
        when(f.inputs.read("task","parent",f.jwt)).thenReturn(view("parent","current",List.of()));
        assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void missingAncestorCommittedReportRejects() {
        when(f.matrices.ids("task","root")).thenReturn(List.of("B0"));assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void terminalHistoricalOriginalMutationRejects() {
        assertThatThrownBy(()->reader.project("task","current",f.jwt,(e,m,until)->{
            try{Files.writeString(root.resolve("parent.json"),"{}");}catch(Exception failure){throw new IllegalStateException(failure);}
            return new ProductComparisonParentEvidenceReader.Checked<>(e,until,()->{});
        })).hasMessageContaining("digest changed");
    }
    @Test void terminalHistoricalDeadlineCannotReturnLateEvidence() {
        assertThatThrownBy(()->reader.project("task","current",f.jwt,(e,m,until)->new ProductComparisonParentEvidenceReader.Checked<>(e,until,()->f.now=f.at.plusSeconds(300))))
                .hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void terminalAncestorInputChangeRejects() {
        var original=views.get("root");
        when(f.inputs.read("task","root",f.jwt)).thenReturn(original,view("root","foreign",original.candidateProgress()));
        assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void terminalAncestorNativeIndexChangeRejects() {
        when(f.matrices.ids("task","root")).thenReturn(List.of("A0","B0"),List.of("B0"));
        assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void terminalSourceWithdrawalRejectsAfterProjection() {
        assertThatThrownBy(()->reader.project("task","current",f.jwt,(e,m,until)->{
            when(f.sources.require("source","CLAIMS","SAVE")).thenThrow(new IllegalStateException("SOURCE_WITHDRAWN"));
            return new ProductComparisonParentEvidenceReader.Checked<>(e,until,()->{});
        })).hasMessage("SOURCE_WITHDRAWN");
    }
    @Test void historyBudgetRejectsRatherThanTruncatingToPendingRows() {
        when(f.inputs.read("task","parent",f.jwt)).thenReturn(view("parent","n0",List.of()));
        when(f.matrices.ids("task","parent")).thenReturn(List.of());
        for(int i=0;i<64;i++) {
            when(f.inputs.read("task","n"+i,f.jwt)).thenReturn(view("n"+i,"n"+(i+1),List.of()));
            when(f.matrices.ids("task","n"+i)).thenReturn(List.of());
        }
        assertThatThrownBy(()->reader.read("task","current",f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
        verify(f.inputs,never()).read("task","n62",f.jwt);
    }
    @Test void intermediateChangeThenRestorationDoesNotReviveA() {
        var old=snapshots.get("parent");var facts=new HashMap<>(old.facts());facts.put(new FactKey(Type.CANDIDATE,"candidate"),"f".repeat(64));
        snapshots.put("parent",new Snapshot(old.taskId(),old.comparisonId(),old.inputSha(),old.previousComparisonId(),facts,old.expectedRows()));
        nativeReport("parent","B","B1");
        var v=projection.read("task","current",f.jwt);assertThat(v.carried()).isEmpty();assertThat(v.recompute()).hasSize(2);
    }
    @Test void mappingRejectsDuplicatesUnknownFieldsAndUnsafeReferences() {
        for(String mapping:List.of("[{\"sha256\":\""+f.sha+"\",\"reference\":\"../outside\"}]",
                "[{\"sha256\":\""+f.sha+"\",\"reference\":\"a\",\"extra\":true}]",
                f.json.canonical(List.of(refs.getFirst(),refs.getFirst()))))assertThatThrownBy(()->assemble(mapping)).isInstanceOf(RuntimeException.class);
    }
}
