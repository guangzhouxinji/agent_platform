package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import cn.xinji.agentplatform.runtime.ModelGateway;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal owning-transaction adapter; calculation assembly requires the V108 schema. */
final class ProductComparisonLineageStore {
    record Material(String version, String attemptId, String candidateId, String approvalSha, String requestSha,
                    String taskId, String comparisonId, String inputSha, String previousComparisonId,
                    String configurationJson, List<ProductComparisonLineageCodec.Fact> facts, List<RowKey> expectedRows) { }
    private final JdbcTemplate db;
    private final ProductComparisonJson json;
    private final ProductComparisonLineageFacts facts;
    private final ProductComparisonLineageCodec codec;
    ProductComparisonLineageStore(JdbcTemplate db, ProductComparisonJson json) {
        this.db = db; this.json = json;
        facts = new ProductComparisonLineageFacts(json, new ProductComparisonMatrixCalculation(json));
        codec = new ProductComparisonLineageCodec(json);
    }
    void reserve(String task, String attempt, String candidate, String approvalSha,
                 ProductComparisonLineageFacts.Captured captured, ModelGateway.Configuration configuration) {
        lock(task);
        var snapshot = captured.snapshot();
        var payload = new Material("RESERVATION_MATERIAL_V1", attempt, candidate, approvalSha, captured.requestSha(),
                snapshot.taskId(), snapshot.comparisonId(), snapshot.inputSha(), snapshot.previousComparisonId(), facts.configurationJson(configuration),
                snapshot.facts().entrySet().stream().sorted(Comparator.comparing(entry -> json.canonical(entry.getKey())))
                        .map(entry -> new ProductComparisonLineageCodec.Fact(entry.getKey(), entry.getValue())).toList(),
                snapshot.expectedRows().stream().sorted(Comparator.comparing(json::canonical)).toList());
        capture(payload);
        if (!task.equals(payload.taskId())) throw invalid();
        nativeMaterial(payload, " AND a.status='ACTIVE' AND i.status='RESERVED' AND i.response_json IS NULL AND i.response_sha IS NULL");
        String raw = json.canonical(payload);
        if (db.update("INSERT INTO con5_matrix_dependency_material(attempt_id,material_json,material_sha) VALUES(?,?,?)",
                attempt, raw, ProductComparisonJson.hash(raw)) != 1) throw invalid();
    }
    Material material(String task, String attempt) {
        lock(task);
        var rows = db.queryForList("SELECT material_json,material_sha FROM con5_matrix_dependency_material WHERE attempt_id=?", attempt);
        if (rows.size() != 1) throw missing();
        String raw = (String) rows.getFirst().get("material_json"), sha = (String) rows.getFirst().get("material_sha");
        if (raw == null || !ProductComparisonJson.hash(raw).equals(sha)) throw invalid();
        var value = json.stored(raw, Material.class); capture(value);
        if (!task.equals(value.taskId()) || !attempt.equals(value.attemptId())) throw invalid();
        nativeMaterial(value, "");
        return value;
    }
    void commit(String reportRaw, String reportSha) {
        var value = report(reportRaw, reportSha); var material = material(value.taskId(), value.attemptId());
        nativeReport(value, reportSha, material);
        var original = codec.encode(capture(material), reportRaw, reportSha);
        if (db.update("INSERT INTO con5_matrix_dependency_original(report_id,attempt_id,original_json,original_sha) VALUES(?,?,?,?)",
                value.reportId(), value.attemptId(), original.raw(), original.sha()) != 1) throw invalid();
    }
    SourceReport read(String reportRaw, String reportSha) {
        var value = report(reportRaw, reportSha); var material = material(value.taskId(), value.attemptId());
        nativeReport(value, reportSha, material);
        var rows = db.queryForList("SELECT original_json,original_sha FROM con5_matrix_dependency_original WHERE report_id=? AND attempt_id=?",
                value.reportId(), value.attemptId());
        if (rows.size() != 1) throw missing();
        return codec.decode(new ProductComparisonLineageCodec.Encoded((String) rows.getFirst().get("original_json"),
                (String) rows.getFirst().get("original_sha")), capture(material), reportRaw, reportSha);
    }
    private ProductComparisonMatrixResults.Package report(String raw, String sha) {
        if (raw == null || !ProductComparisonJson.hash(raw).equals(sha)) throw invalid();
        return json.stored(raw, ProductComparisonMatrixResults.Package.class);
    }
    ProductComparisonLineageFacts.Captured capture(Material value) {
        if (value == null || !"RESERVATION_MATERIAL_V1".equals(value.version()) || !hash(value.requestSha())
                || !hash(value.approvalSha()) || value.attemptId() == null || value.attemptId().isBlank()
                || value.facts() == null || value.facts().size() > 225 || value.expectedRows() == null || value.expectedRows().size() > 4000) throw invalid();
        Map<FactKey, String> map = new HashMap<>();
        for (var fact : value.facts()) if (map.putIfAbsent(fact.key(), fact.sha()) != null) throw invalid();
        Set<RowKey> rows = new HashSet<>(value.expectedRows());
        if (rows.size() != value.expectedRows().size() || rows.stream().noneMatch(row -> row.candidateId().equals(value.candidateId()))) throw invalid();
        var configuration = json.stored(value.configurationJson(), ModelGateway.Configuration.class);
        if (!ProductComparisonJson.hash(facts.configurationJson(configuration)).equals(map.get(new FactKey(Type.CONFIGURATION, "global")))) throw invalid();
        return new ProductComparisonLineageFacts.Captured(new Snapshot(value.taskId(), value.comparisonId(), value.inputSha(),
                value.previousComparisonId(), map, rows), value.requestSha());
    }
    private void nativeMaterial(Material value, String extra) {
        var configuration = json.stored(value.configurationJson(), ModelGateway.Configuration.class);
        Long count = db.queryForObject("SELECT COUNT(*) FROM con5_matrix_invocation i JOIN con5_candidate_attempt a ON a.attempt_id=i.attempt_id "
                + "WHERE a.attempt_id=? AND a.task_id=? AND a.comparison_id=? AND a.candidate_id=? AND a.input_sha=? "
                + "AND i.request_sha=? AND i.approval_sha=? AND i.configuration_version=?" + extra, Long.class,
                value.attemptId(), value.taskId(), value.comparisonId(), value.candidateId(), value.inputSha(), value.requestSha(),
                value.approvalSha(), configuration.version());
        if (!Long.valueOf(1).equals(count)) throw invalid();
    }
    private void nativeReport(ProductComparisonMatrixResults.Package value, String reportSha, Material material) {
        if (!value.candidateId().equals(material.candidateId()) || !value.inputSha().equals(material.inputSha())
                || !value.comparisonId().equals(material.comparisonId()) || !value.approvalSha().equals(material.approvalSha())) throw invalid();
        Long count = db.queryForObject("SELECT COUNT(*) FROM con5_matrix_report r JOIN con5_matrix_receipt c ON c.report_id=r.report_id AND c.attempt_id=r.attempt_id "
                + "JOIN con5_candidate_attempt a ON a.attempt_id=r.attempt_id JOIN con5_matrix_invocation i ON i.attempt_id=r.attempt_id "
                + "WHERE r.report_id=? AND r.attempt_id=? AND r.report_sha=? AND c.report_sha=? AND r.task_id=? AND r.comparison_id=? "
                + "AND r.candidate_id=? AND r.input_sha=? AND r.fencing_token=? AND a.fencing_token=? "
                + "AND a.status='COMMITTED' AND i.status='COMMITTED' AND i.response_sha=? AND i.approval_sha=? AND i.configuration_version=?",
                Long.class, value.reportId(), value.attemptId(), reportSha, reportSha, value.taskId(), value.comparisonId(), value.candidateId(),
                value.inputSha(), value.token(), value.token(), value.responseSha(), value.approvalSha(), value.configurationVersion());
        if (!Long.valueOf(1).equals(count)) throw invalid();
    }
    private void lock(String task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || db.getDataSource() == null
                || !TransactionSynchronizationManager.hasResource(db.getDataSource())) throw new IllegalStateException("LINEAGE_OWNING_TRANSACTION_REQUIRED");
        if (db.queryForList("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE", String.class, task).size() != 1) throw invalid();
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalStateException invalid() { return new IllegalStateException("LINEAGE_NATIVE_MATERIAL_OR_CLOSURE_INVALID"); }
    private static IllegalStateException missing() { return new IllegalStateException("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED"); }
}
