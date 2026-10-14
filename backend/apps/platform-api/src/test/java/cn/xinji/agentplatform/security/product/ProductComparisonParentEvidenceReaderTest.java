package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.FULL_FEATURE_CONTEXT;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix;
import cn.xinji.agentplatform.consultation.patenttask.authorization.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;

/** Controlled existing authentication/material ports and transaction/JDBC callbacks; no DB, DDL or permission claim. */
class ProductComparisonParentEvidenceReaderTest {
    final ProductComparisonPreparationLoader loader=mock(ProductComparisonPreparationLoader.class);
    final ProductComparisonInputApplication inputs=mock(ProductComparisonInputApplication.class);
    final ProductComparisonInputStore inputStore=mock(ProductComparisonInputStore.class);
    final ProductComparisonMatrixStore matrices=mock(ProductComparisonMatrixStore.class);
    final ProductComparisonLineageStore lineage=mock(ProductComparisonLineageStore.class);
    final ProductSourceAvailability sources=mock(ProductSourceAvailability.class);
    final ProductComparisonMatrixCalculation calculation=mock(ProductComparisonMatrixCalculation.class);
    final ProductComparisonLineageFacts facts=mock(ProductComparisonLineageFacts.class);
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final DataSource dataSource=mock(DataSource.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final Jwt jwt=mock(Jwt.class);
    final ProductComparisonJson json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final Instant at=Instant.parse("2026-10-12T00:00:00Z");
    Instant now=at;
    final Clock clock=new Clock() {
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    };
    final String sha="a".repeat(64);
    final ModelGateway.Configuration config=new ModelGateway.Configuration("id","v1","provider","model",URI.create("https://component.invalid/v1"),
            "OPENAI_CHAT_COMPLETIONS",Duration.ofSeconds(20),4096,4096,Set.of("PATENT_COMPARISON"),Set.of("APPROVED_COMPARISON_ORIGINALS"));
    final ProductComparisonContracts.Prepare command=new ProductComparisonContracts.Prepare(1,"goal",1,"input","TECHNICAL_SIMILARITY",
            new ProductComparisonContracts.Target("message",null),List.of());
    final ProductComparisonInputs.View current=view("current","parent",2,List.of());
    final ProductComparisonInputs.View parent=view("parent",null,1,List.of(new ProductComparisonInputs.Progress("candidate","COMMITTED")));
    final ProductTaskDirectory.Task task=new ProductTaskDirectory.Task("task","org","issuer","subject","IN_PROGRESS",2,3,"scope",1,at.plusSeconds(600));
    final ProductComparisonPreparationLoader.Loaded loaded=mock(ProductComparisonPreparationLoader.Loaded.class);
    final ProductSourceAvailability.Active source=mock(ProductSourceAvailability.Active.class);
    final ProductComparisonDocuments.Loaded document=new ProductComparisonDocuments.Loaded(
            new ProductComparisonContracts.DocumentRef("source","doc","version",List.of(1),null,null,null,null),null,
            new ProductComparisonContracts.MaterialDocument("candidate","source","doc","version","source-version",sha,"original","/claims",List.of(),""),
            new ProductComparisonDocuments.Authority(source,null,null,at.plusSeconds(600)));
    final PatentComparisonMatrix.Row row=new PatentComparisonMatrix.Row("candidate",List.of(1),"F1",PatentComparisonMatrix.Relation.UNKNOWN,
            List.of(),List.of(),"unknown","unknown",List.of(),List.of());
    final ProductComparisonMatrixResults.Package value=new ProductComparisonMatrixResults.Package("task","parent","candidate",parent.inputSha(),
            "attempt",1,"report",1,"PATENT_FEATURE_MATRIX_V1","worker","v1",sha,sha,at,at.plusSeconds(600),List.of(row));
    final String raw=" \n"+json.canonical(value)+"\n", reportSha=ProductComparisonJson.hash(raw);
    final ProductComparisonMatrixResults.Report report=new ProductComparisonMatrixResults.Report(value,reportSha,null);
    final Snapshot snapshot=new Snapshot("task","parent",parent.inputSha(),null,Map.of(
            new FactKey(Type.TARGET,"global"),sha,new FactKey(Type.GOAL,"global"),sha,new FactKey(Type.PURPOSE,"global"),sha,
            new FactKey(Type.METHOD,"global"),sha,new FactKey(Type.CONFIGURATION,"global"),sha,
            new FactKey(Type.FEATURE,"F1"),sha,new FactKey(Type.CANDIDATE,"candidate"),sha),Set.of(new RowKey("candidate",List.of(1),"F1")));
    final ProductComparisonLineageFacts.Captured captured=new ProductComparisonLineageFacts.Captured(snapshot,sha);
    final ProductComparisonLineageStore.Material material=new ProductComparisonLineageStore.Material("RESERVATION_MATERIAL_V1","attempt","candidate",sha,sha,
            "task","parent",parent.inputSha(),null,json.canonical(config),List.of(),List.of());
    final SourceReport proof=new SourceReport("parent",parent.inputSha(),"candidate","report",reportSha,FULL_FEATURE_CONTEXT,
            List.of(new RetainedRow(new RowKey("candidate",List.of(1),"F1"),sha,snapshot.facts().keySet())));
    ProductComparisonParentEvidenceReader reader;
    boolean transaction=true, bound=true;

    @BeforeEach void setup() {
        when(tx.execute(any())).thenAnswer(call -> {
            TransactionSynchronizationManager.setActualTransactionActive(transaction);
            if(bound) TransactionSynchronizationManager.bindResource(dataSource,new Object());
            try{return ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class));}
            finally{TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);TransactionSynchronizationManager.setActualTransactionActive(false);}
        });
        when(db.getDataSource()).thenReturn(dataSource);
        when(db.queryForList(anyString(),eq(String.class),eq("task"))).thenReturn(List.of("task"));
        when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenReturn(task);
        when(inputStore.current("task")).thenReturn("current");
        when(inputs.read("task","current",jwt)).thenReturn(current);
        when(inputs.read("task","parent",jwt)).thenReturn(parent);
        when(loader.load("task",command,jwt,true)).thenReturn(loaded);
        when(loaded.materialSha()).thenReturn(sha);when(loaded.expiresAt()).thenReturn(at.plusSeconds(600));
        when(loaded.candidates()).thenReturn(List.of(document));
        when(sources.require("source","CLAIMS","SAVE")).thenReturn(source);
        when(sources.useUntil("source","CLAIMS","SAVE")).thenReturn(at.plusSeconds(600));
        when(matrices.ids("task","parent")).thenReturn(List.of("report"));
        when(matrices.read("task","parent","report")).thenReturn(report);
        when(matrices.rawResponse("attempt")).thenReturn("response");
        when(lineage.material("task","attempt")).thenReturn(material);
        when(lineage.capture(material)).thenReturn(captured);
        when(facts.capture(any(),eq(config),eq(sha))).thenReturn(captured);
        when(calculation.validate(any(),eq("response"))).thenReturn(new ProductComparisonMatrixCalculation.Validated(List.of(row),sha));
        when(db.queryForList(startsWith("SELECT package_json"),eq("task"),eq("parent"),eq("report")))
                .thenReturn(List.of(Map.of("package_json",raw,"report_sha",reportSha)));
        when(lineage.read(raw,reportSha)).thenReturn(proof);
        reader=new ProductComparisonParentEvidenceReader(loader,inputs,inputStore,matrices,lineage,sources,db,tx,json,calculation,facts,clock);
    }
    @Test void oldParentVersionUsesHistoricalAuthenticationAndPreservesOriginalBytesWithoutWrites() {
        var evidence=read();assertThat(evidence.parent().input().taskVersion()).isEqualTo(1);
        assertThat(evidence.current().input().taskVersion()).isEqualTo(2);
        assertThat(evidence.reports()).containsExactly(new ProductComparisonParentEvidenceReader.ParentReport(snapshot,proof));
        verify(lineage,times(2)).read(raw,reportSha);verify(loader,never()).require(anyString(),any(),eq(ProductAccessPolicy.Action.COMPARE));
        verify(db,never()).update(anyString(),any(Object[].class));
        assertThatThrownBy(() -> evidence.reports().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void parentMatrixUsesCurrentCoordinatesButKeepsParentFeaturesAndExactOriginalBytes() {
        var result=reader.report("task","current","report",jwt);
        assertThat(result.comparisonId()).isEqualTo("current");assertThat(result.inputSha()).isEqualTo(current.inputSha());
        assertThat(result.previousComparisonId()).isEqualTo("parent");assertThat(result.parentInputSha()).isEqualTo(parent.inputSha());
        assertThat(result.report()).isEqualTo(report);assertThat(result.originalJson()).isEqualTo(raw);
        assertThat(result.confirmedFeatures()).isEqualTo(parent.input().confirmedFeatures().revision().features());
        verify(loader,never()).require(anyString(),any(),eq(ProductAccessPolicy.Action.COMPARE));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void unknownParentReportDoesNotReadClientSelectedHistory() {
        assertThatThrownBy(()->reader.report("task","current","foreign",jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
        verify(matrices,never()).read("task","parent","foreign");
    }
    @Test void parentMatrixRecheckRejectsDifferentOriginalAfterProjection() {
        when(db.queryForList(startsWith("SELECT package_json"),eq("task"),eq("parent"),eq("report")))
                .thenReturn(List.of(Map.of("package_json",raw,"report_sha",reportSha)),List.of(Map.of("package_json",raw,"report_sha",reportSha)),
                        List.of(Map.of("package_json",json.canonical(value),"report_sha",reportSha)));
        assertThatThrownBy(()->reader.report("task","current","report",jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void parentMatrixRefusesSaveWithdrawalAfterProjection() {
        when(sources.require("source","CLAIMS","SAVE")).thenReturn(source,source,source).thenThrow(new IllegalStateException("SOURCE_WITHDRAWN"));
        assertThatThrownBy(()->reader.report("task","current","report",jwt)).hasMessage("SOURCE_WITHDRAWN");
    }
    @Test void parentMatrixRefusesExpiryDuringFinalRead() {
        when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenAnswer(call->{
            if(mockingDetails(loader).getInvocations().stream().filter(i->i.getMethod().getName().equals("require")).count()>=3)now=at.plusSeconds(600);
            return task;
        });assertThatThrownBy(()->reader.report("task","current","report",jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void noTransactionRefusesBeforeInputOrReportRead(){transaction=false;refused("LINEAGE_OWNING_TRANSACTION_REQUIRED");verifyNoInteractions(inputs,matrices);}
    @Test void unrelatedTransactionRefusesEvenWhenNoReportsExist(){bound=false;refused("LINEAGE_OWNING_TRANSACTION_REQUIRED");verifyNoInteractions(inputs,matrices);}
    @Test void currentReadDeniedPreventsStartingTransaction(){when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenThrow(new IllegalStateException("READ_DENIED"));refused("READ_DENIED");verifyNoInteractions(tx);}
    @Test void nonCurrentInputRefuses(){when(inputStore.current("task")).thenReturn("other");refused();verifyNoInteractions(matrices);}
    @Test void currentInputVersionMustStillMatchCurrentTask(){when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenReturn(new ProductTaskDirectory.Task("task","org","issuer","subject","IN_PROGRESS",3,3,"scope",1,at.plusSeconds(600)));refused();}
    @Test void wrongParentTaskRefuses(){when(inputs.read("task","parent",jwt)).thenReturn(viewForTask("other","parent",null,1,List.of()));refused();}
    @Test void committedCandidateCannotBecomeEmptyHistory(){when(matrices.ids("task","parent")).thenReturn(List.of());refused();}
    @Test void noCommittedReportsReturnsEmptyEvidenceWithoutRebuildingHistory(){
        when(inputs.read("task","parent",jwt)).thenReturn(view("parent",null,1,List.of(new ProductComparisonInputs.Progress("candidate","QUEUED"))));
        when(matrices.ids("task","parent")).thenReturn(List.of());
        assertThat(read().reports()).isEmpty();verifyNoInteractions(lineage,facts,calculation);
    }
    @Test void readAssemblyNeedsNoModelOrLineageStartupQuery(){
        var assembled=new ProductComparisonParentEvidenceConfiguration().productComparisonParentEvidenceReader(mock(ProductTaskDirectory.class),
                mock(ProductConsultationAccess.class),mock(ProductComparisonTargetReader.class),sources,mock(ProductCollectedPatentTitles.class),
                mock(ProductSourceEvidenceFiles.class),mock(ProductGrantWatermark.class),inputs,matrices,calculation,db,json,
                new ObjectMapper().findAndRegisterModules(),mock(org.springframework.transaction.PlatformTransactionManager.class),"issuer","[]");
        assertThat(assembled).isNotNull();verifyNoInteractions(db,sources);
    }
    @Test void duplicateReportCannotReduceTheOriginalDomain(){when(matrices.ids("task","parent")).thenReturn(List.of("report","report"));refused();}
    @Test void twoReportsForSameCandidateRefuse(){when(matrices.ids("task","parent")).thenReturn(List.of("report","second"));when(matrices.read("task","parent","second")).thenReturn(report);refused();}
    @Test void missingOriginalProofNeverBackfillsHistory(){when(lineage.read(raw,reportSha)).thenThrow(new IllegalStateException("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED"));refused("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");verify(lineage,never()).commit(anyString(),anyString());}
    @Test void historicalFactsMustMatchActualMaterial(){when(lineage.capture(material)).thenReturn(new ProductComparisonLineageFacts.Captured(snapshot,"b".repeat(64)));refused();verify(lineage,never()).read(anyString(),anyString());}
    @Test void missingValidatedRowCannotBorrowReport(){when(calculation.validate(any(),anyString())).thenReturn(new ProductComparisonMatrixCalculation.Validated(List.of(),sha));refused();}
    @Test void reencodedOriginalBytesCannotBorrowOriginalDigest(){when(db.queryForList(startsWith("SELECT package_json"),eq("task"),eq("parent"),eq("report"))).thenReturn(List.of(Map.of("package_json",json.canonical(value),"report_sha",reportSha)));refused();}
    @Test void pointerChangeAtTerminalRefuses(){when(inputStore.current("task")).thenReturn("current","successor");refused();}
    @Test void sourceSaveWithdrawalDuringTerminalReadRefuses(){when(sources.require("source","CLAIMS","SAVE")).thenReturn(source,source,source).thenThrow(new IllegalStateException("SOURCE_WITHDRAWN"));refused("SOURCE_WITHDRAWN");}
    @Test void taskAuthorizationChangesAtTerminalRefuse(){when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenReturn(task,task,new ProductTaskDirectory.Task("task","org","issuer","subject","IN_PROGRESS",2,4,"scope",1,at.plusSeconds(600)));refused();}
    @Test void separateSourceSaveDeadlineIsNotExtendedToDisplayOrInputExpiry(){
        when(sources.useUntil("source","CLAIMS","SAVE")).thenReturn(at.plusSeconds(30));
        when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenAnswer(call->{
            if(mockingDetails(loader).getInvocations().stream().filter(i->i.getMethod().getName().equals("require")).count()>=3)now=at.plusSeconds(30);
            return task;
        });refused();
    }
    @Test void expiryDuringTerminalAuthorizationCannotReturnEvidence(){when(loader.require("task",jwt,ProductAccessPolicy.Action.READ)).thenAnswer(call->{if(mockingDetails(loader).getInvocations().stream().filter(i->i.getMethod().getName().equals("require")).count()>=3)now=at.plusSeconds(600);return task;});refused();}
    @Test void projectionDeadlineIsCheckedAfterItsTerminalMetadataRead() {
        assertThatThrownBy(()->reader.project("task","current",jwt,(e,m,until)->
                new ProductComparisonParentEvidenceReader.Checked<>(e,at.plusSeconds(30),()->now=at.plusSeconds(30))))
                .hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }
    @Test void projectionReceivesEarliestIndependentSaveDeadline() {
        when(sources.useUntil("source","CLAIMS","SAVE")).thenReturn(at.plusSeconds(30));
        var until=reader.project("task","current",jwt,(e,m,expiry)->new ProductComparisonParentEvidenceReader.Checked<>(expiry,expiry,()->{}));
        assertThat(until).isEqualTo(at.plusSeconds(30));
    }
    private ProductComparisonParentEvidenceReader.Evidence read(){return reader.read("task","current",jwt);}
    private void refused(){refused("LINEAGE_PARENT_OR_CURRENT_CHANGED");}
    private void refused(String message){assertThatThrownBy(this::read).hasMessage(message);}
    private ProductComparisonInputs.View view(String id,String previous,long version,List<ProductComparisonInputs.Progress> progress){return viewForTask("task",id,previous,version,progress);}
    private ProductComparisonInputs.View viewForTask(String taskId,String id,String previous,long version,List<ProductComparisonInputs.Progress> progress){
        var features=new ProductComparisonContracts.FeatureView(new ProductComparisonContracts.Revision("features",taskId,1,null,"preparation",sha,sha,
                "issuer","subject","key",sha,"confirmed",at,at.plusSeconds(600),List.of(new ProductComparisonContracts.Feature("F1",1,0,1,"parent feature",null,null,null))),sha);
        var envelope=new ProductComparisonInputs.Envelope(id,taskId,version,previous,null,features,"preparation",sha,json.canonical(Map.of("command",command)),
                "issuer","subject","key",sha,at,at.plusSeconds(600),List.of(new ProductComparisonInputs.Candidate("candidate",0)));
        return new ProductComparisonInputs.View(envelope,json.sha(envelope),progress);
    }
}
