package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled JDBC call contract only; no DDL, connection, persistence or permission evidence. */
class ProductComparisonLineageStoreTest {
    final ProductComparisonJson json = new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final JdbcTemplate db = mock(JdbcTemplate.class); final DataSource source = mock(DataSource.class);
    final ProductComparisonLineageStore store = new ProductComparisonLineageStore(db, json);
    final String sha = "a".repeat(64), requestSha = "b".repeat(64);
    final ModelGateway.Configuration config = new ModelGateway.Configuration("id", "v1", "provider", "model", URI.create("https://component.invalid/v1"),
            "OPENAI_CHAT_COMPLETIONS", Duration.ofSeconds(30), 4096, 4096, Set.of("PATENT_COMPARISON"), Set.of("APPROVED_COMPARISON_ORIGINALS"));
    final Map<String, Object> material = new HashMap<>(), original = new HashMap<>();
    final List<String> writes = new ArrayList<>();
    @BeforeEach void setup() {
        when(db.getDataSource()).thenReturn(source);
        when(db.queryForList(eq("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE"), eq(String.class), eq("task"))).thenReturn(List.of("task"));
        when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(db.queryForList(startsWith("SELECT material_json"), any(Object[].class))).thenAnswer(call -> material.isEmpty() ? List.of() : List.of(material));
        when(db.queryForList(startsWith("SELECT original_json"), any(Object[].class))).thenAnswer(call -> original.isEmpty() ? List.of() : List.of(original));
        when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0); var args = (Object[]) call.getRawArguments()[1]; writes.add(sql);
            if (sql.contains("dependency_material")) { material.put("material_json", args[1]); material.put("material_sha", args[2]); }
            else { original.put("original_json", args[2]); original.put("original_sha", args[3]); }
            return 1;
        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(source, new Object());
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    @Test void sourceContractInsertsOnlyAndBindsReportToStoredPreDispatchMaterial() {
        reserve(); String raw = report(); String digest = ProductComparisonJson.hash(raw);
        store.commit(raw, digest);
        assertThat(store.read(raw, digest).rows().getFirst().rowSha()).isEqualTo(json.sha(json.stored(raw, ProductComparisonMatrixResults.Package.class).rows().getFirst()));
        assertThat(writes).hasSize(2).allMatch(sql -> sql.startsWith("INSERT INTO"));
    }
    @Test void noTransactionCannotWriteOrQuery() {
        TransactionSynchronizationManager.setActualTransactionActive(false); clearInvocations(db);
        assertThatThrownBy(this::reserve).hasMessage("LINEAGE_OWNING_TRANSACTION_REQUIRED");
        verifyNoInteractions(db);
    }
    @Test void unrelatedTransactionCannotReadOrWrite() {
        TransactionSynchronizationManager.unbindResource(source);
        assertThatThrownBy(this::reserve).hasMessage("LINEAGE_OWNING_TRANSACTION_REQUIRED");
        assertThat(writes).isEmpty();
    }
    @Test void legacyInvocationWithNoMaterialCannotAcquireProof() {
        String raw = report();
        assertThatThrownBy(() -> store.commit(raw, ProductComparisonJson.hash(raw))).hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        assertThat(writes).isEmpty();
    }
    @Test void missingTaskRefusesBeforeNativeLookupOrInsert() {
        when(db.queryForList(eq("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE"), eq(String.class), eq("task"))).thenReturn(List.of());
        assertThatThrownBy(this::reserve).hasMessage("LINEAGE_NATIVE_MATERIAL_OR_CLOSURE_INVALID");
        verify(db, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        assertThat(writes).isEmpty();
    }
    @Test void nativeInvocationMismatchRefusesBeforeInsert() {
        when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        assertThatThrownBy(this::reserve).hasMessage("LINEAGE_NATIVE_MATERIAL_OR_CLOSURE_INVALID");
        assertThat(writes).isEmpty();
    }
    @Test void rehashedSameVersionConfigurationDriftCannotBorrowFacts() {
        reserve(); String raw = ((String) material.get("material_json")).replace("component.invalid/v1", "component.invalid/v2");
        material.put("material_json", raw); material.put("material_sha", ProductComparisonJson.hash(raw));
        assertThatThrownBy(() -> store.material("task", "attempt")).hasMessage("LINEAGE_NATIVE_MATERIAL_OR_CLOSURE_INVALID");
        assertThat(writes).hasSize(1);
    }
    @Test void reportClosureMismatchDoesNotInsertProof() {
        reserve(); when(db.queryForObject(contains("con5_matrix_report r"), eq(Long.class), any(Object[].class))).thenReturn(0L);
        String raw = report();
        assertThatThrownBy(() -> store.commit(raw, ProductComparisonJson.hash(raw))).hasMessage("LINEAGE_NATIVE_MATERIAL_OR_CLOSURE_INVALID");
        assertThat(writes).hasSize(1);
    }
    private void reserve() {
        Map<FactKey, String> facts = new HashMap<>();
        for (Type type : Type.values()) facts.put(new FactKey(type, type == Type.FEATURE ? "F1" : type == Type.CANDIDATE ? "candidate" : "global"), sha);
        facts.put(new FactKey(Type.CONFIGURATION, "global"), ProductComparisonJson.hash(new ProductComparisonLineageFacts(json,
                new ProductComparisonMatrixCalculation(json)).configurationJson(config)));
        var captured = new ProductComparisonLineageFacts.Captured(new Snapshot("task", "comparison", sha, null, facts,
                Set.of(new RowKey("candidate", List.of(1), "F1"))), requestSha);
        store.reserve("task", "attempt", "candidate", sha, captured, config);
    }
    private String report() {
        var row = new cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Row("candidate", List.of(1), "F1",
                cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.Relation.UNKNOWN, List.of(), List.of(), "gap", "unknown", List.of(), List.of());
        var at = Instant.parse("2026-10-12T00:00:00Z");
        return json.canonical(new ProductComparisonMatrixResults.Package("task", "comparison", "candidate", sha, "attempt", 1,
                "report", 1, "PATENT_FEATURE_MATRIX_V1", "worker", "v1", sha, sha, at, at.plusSeconds(60), List.of(row)));
    }
}
