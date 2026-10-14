package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;

/** Controlled transaction callbacks/JDBC only. No DB connection, DDL or atomicity claim. */
class ProductComparisonLineageAssemblyTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final ProductComparisonLineageStore lineage=mock(ProductComparisonLineageStore.class);
    final ProductComparisonJson json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final Instant at=Instant.parse("2026-10-12T00:00:00Z");
    final String sha="a".repeat(64),requestSha="b".repeat(64),raw="original response";
    final ModelGateway.Configuration config=new ModelGateway.Configuration("model","v1","provider","model",URI.create("https://component.invalid/v1"),
            "OPENAI_CHAT_COMPLETIONS",Duration.ofSeconds(20),1048576,1048576,Set.of("PATENT_COMPARISON"),Set.of("APPROVED_COMPARISON_ORIGINALS"));
    final ProductComparisonModelAdmission.Permit permit=new ProductComparisonModelAdmission.Permit(new ProductComparisonModelAdmission.Approval(
            "v1","component",at,at,at.plusSeconds(600),"task",sha,Set.of("candidate"),3,config),sha);
    final ProductComparisonLineageFacts.Captured captured=new ProductComparisonLineageFacts.Captured(
            new Snapshot("task","comparison",sha,null,Map.of(
                    new FactKey(Type.TARGET,"global"),sha,new FactKey(Type.GOAL,"global"),sha,
                    new FactKey(Type.PURPOSE,"global"),sha,new FactKey(Type.METHOD,"global"),sha,
                    new FactKey(Type.CONFIGURATION,"global"),sha,new FactKey(Type.FEATURE,"F1"),sha,
                    new FactKey(Type.CANDIDATE,"candidate"),sha),Set.of(new RowKey("candidate",List.of(1),"F1"))),requestSha);
    final List<String> order=new ArrayList<>();
    ProductComparisonMatrixStore store;
    boolean callback;

    @BeforeEach void setup() {
        when(db.queryForObject(anyString(),eq(Long.class))).thenReturn(0L);
        when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(db.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of("task"));
        when(db.query(startsWith("SELECT status,request_sha"),any(RowMapper.class),eq("attempt"))).thenReturn(List.of());
        when(db.update(anyString(),any(Object[].class))).thenAnswer(call->{order.add(call.getArgument(0));return 1;});
        when(tx.execute(any())).thenAnswer(call->{
            callback=true;
            try{return ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class));}
            finally{callback=false;}
        });
        doAnswer(call->{assertThat(callback).isTrue();order.add("material");return null;})
                .when(lineage).reserve("task","attempt","candidate",sha,captured,config);
        store=new ProductComparisonMatrixStore(db,json,tx,Clock.fixed(at,ZoneOffset.UTC),lineage);
    }
    @Test void newReservationStoresMaterialInsideOriginalCallbackBeforeFinalCurrentCheck() {
        assertThat(reserve()).isNull();
        assertThat(order).hasSize(4);
        assertThat(order.get(0)).isEqualTo("current");
        assertThat(order.get(1)).startsWith("INSERT INTO con5_matrix_invocation");
        assertThat(order.subList(2,4)).containsExactly("material","current");
    }
    @Test void materialFailurePreventsSuccessfulReservationAndFinalCurrentCheck() {
        doThrow(new IllegalStateException("material refused")).when(lineage).reserve(anyString(),anyString(),anyString(),anyString(),any(),any());
        assertThatThrownBy(this::reserve).hasMessage("material refused");
        assertThat(order).hasSize(2);assertThat(callback).isFalse();
    }
    @Test void failedInvocationInsertDoesNotAttemptMaterialInsert() {
        when(db.update(startsWith("INSERT INTO con5_matrix_invocation"),any(Object[].class))).thenReturn(0);
        assertThatThrownBy(this::reserve).hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
        verifyNoInteractions(lineage);
    }
    @Test void receivedLegacyWithoutMaterialCannotBeUpgraded() {
        received("RECEIVED");
        when(lineage.material("task","attempt")).thenThrow(new IllegalStateException("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED"));
        assertThatThrownBy(this::reserve).hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        verify(lineage,never()).reserve(anyString(),anyString(),anyString(),anyString(),any(),any());
        assertThat(order).containsExactly("current");
    }
    @Test void receivedUsesExactOriginalMaterialWithoutAnotherInsert() {
        received("RECEIVED");var material=mock(ProductComparisonLineageStore.Material.class);
        when(lineage.material("task","attempt")).thenReturn(material);when(lineage.capture(material)).thenReturn(captured);
        assertThat(reserve().raw()).isEqualTo(raw);
        assertThat(order).containsExactly("current","current");
        verify(lineage,never()).reserve(anyString(),anyString(),anyString(),anyString(),any(),any());
    }
    @Test void changedFactsCannotBorrowReceivedOriginal() {
        received("RECEIVED");var material=mock(ProductComparisonLineageStore.Material.class);
        when(lineage.material("task","attempt")).thenReturn(material);
        when(lineage.capture(material)).thenReturn(new ProductComparisonLineageFacts.Captured(captured.snapshot(),sha));
        assertThatThrownBy(this::reserve).hasMessage("COMPARISON_MATRIX_RESULT_OR_CLOSURE_INVALID");
        assertThat(order).containsExactly("current");
    }
    @Test void unresolvedReservationCannotDispatchAgainOrRetrofitMaterial() {
        received("RESERVED");assertThatThrownBy(this::reserve).hasMessage("MODEL_INVOCATION_UNRESOLVED");
        verifyNoInteractions(lineage);
    }
    @Test void proofIsStoredAfterNativeCommitAndBeforeTerminalChecks() {
        received("RECEIVED");store=spy(store);
        var row=new cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Row("candidate",List.of(1),"F1",
                cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Relation.UNKNOWN,List.of(),List.of(),"gap","unknown",List.of(),List.of());
        var value=new ProductComparisonMatrixResults.Package("task","comparison","candidate",sha,"attempt",1,"report",1,
                "PATENT_FEATURE_MATRIX_V1","worker","v1",sha,ProductComparisonJson.hash(raw),at,at.plusSeconds(60),List.of(row));
        doReturn(null).when(store).read("task","comparison","report");
        doAnswer(call->{assertThat(callback).isTrue();order.add("proof");return null;}).when(lineage).commit(json.canonical(value),json.sha(value));
        store.commit(value,()->order.add("current"),()->order.add("terminal"));
        int proof=order.indexOf("proof");
        assertThat(order.get(proof-1)).startsWith("UPDATE con5_matrix_invocation SET status='COMMITTED'");
        assertThat(order.get(proof+1)).isEqualTo("terminal");
        doThrow(new IllegalStateException("proof refused")).when(lineage).commit(anyString(),anyString());order.clear();
        assertThatThrownBy(()->store.commit(value,()->{},()->order.add("terminal"))).hasMessage("proof refused");
        assertThat(order).doesNotContain("terminal");
    }
    @Test void lineageShapeChecksAreZeroRowOnlyAndMissingSchemaRefuses() {
        ProductComparisonSchemaGuard.lineage(db);
        for(var shape:ProductComparisonSchemaGuard.LINEAGE)
            verify(db).query(eq("SELECT "+shape.columns()+" FROM "+shape.table()+" WHERE 1=0"),any(ResultSetExtractor.class));
        when(db.query(contains("FROM con5_matrix_dependency_material WHERE 1=0"),any(ResultSetExtractor.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private details"));
        assertThatThrownBy(()->ProductComparisonSchemaGuard.lineage(db)).hasMessage("COMPARISON_SCHEMA_REQUIRED:V108:con5_matrix_dependency_material");
    }
    @Test void reservationFailureNeverFallsThroughToGatewayDispatch() {
        var leases=mock(ProductComparisonCandidateExecution.class);var materials=mock(ProductComparisonWorkerMaterials.class);
        var admission=mock(ProductComparisonModelAdmission.class);var calculation=mock(ProductComparisonMatrixCalculation.class);
        var executionStore=mock(ProductComparisonMatrixStore.class);var gateway=mock(ModelGateway.class);
        var request=mock(jakarta.servlet.http.HttpServletRequest.class);var loaded=mock(ProductComparisonWorkerMaterials.Loaded.class);
        var scope=new ProductComparisonCandidateExecution.Scope("task","comparison","candidate",sha,"delegation");
        var lease=new ProductComparisonCandidateExecution.Lease("attempt",1,at.plusSeconds(60));
        String exact="controlled request",digest=ProductComparisonJson.hash(exact);
        var bound=new ProductComparisonLineageFacts.Captured(captured.snapshot(),digest);
        when(materials.consume(request,"task","comparison","candidate",sha,"delegation")).thenReturn(loaded);
        when(admission.require(loaded)).thenReturn(permit);when(calculation.request(loaded,config)).thenReturn(exact);
        when(executionStore.capture(loaded,config,digest)).thenReturn(bound);
        when(executionStore.reserve(eq("task"),eq("attempt"),eq("candidate"),eq(digest),eq(permit),eq(bound),any()))
                .thenThrow(new IllegalStateException("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED"));
        var execution=new ProductComparisonMatrixExecution(leases,materials,admission,calculation,executionStore,gateway,Clock.fixed(at,ZoneOffset.UTC));
        assertThatThrownBy(()->execution.execute(request,scope,lease)).hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        verifyNoInteractions(gateway);
    }
    private ProductComparisonMatrixStore.Response reserve() {
        return store.reserve("task","attempt","candidate",requestSha,permit,captured,()->order.add("current"));
    }
    private void received(String status) {
        when(db.query(startsWith("SELECT status,request_sha"),any(RowMapper.class),eq("attempt")))
                .thenReturn(List.of(new ProductComparisonMatrixStore.Response(status,requestSha,sha,"v1",raw)));
    }
}
