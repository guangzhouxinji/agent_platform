package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original comparison coordinates under the request's Task lock. No model invocation or copied rows. */
final class ProductHumanComparisonReferences {
    record Checked(ProductHumanRequestReferences.Comparison reference, Instant accessUntil) { }
    record Selection(String comparisonId, List<String> reportIds) {
        Selection {
            if (comparisonId == null || comparisonId.isBlank()) throw unavailable();
            reportIds = List.copyOf(reportIds);
            if (reportIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || reportIds.stream().distinct().count() != reportIds.size()) throw unavailable();
        }
    }
    private final JdbcTemplate db;
    private final ProductComparisonInputStore store;
    private final ProductComparisonInputApplication inputs;
    private final ProductComparisonMatrixReader matrices;
    private final ProductComparisonParentEvidenceReader parents;
    private final ProductComparisonJson json;

    ProductHumanComparisonReferences(JdbcTemplate db, ProductComparisonInputApplication inputs,
            ProductComparisonMatrixReader matrices, ProductComparisonParentEvidenceReader parents, ProductComparisonJson json) {
        this.db = db; this.store = new ProductComparisonInputStore(db, json); this.inputs = Objects.requireNonNull(inputs);
        this.matrices = Objects.requireNonNull(matrices); this.parents = Objects.requireNonNull(parents); this.json = json;
    }
    void requireBinding(javax.sql.DataSource main) {
        if (db.getDataSource() != main) throw new IllegalArgumentException("Matching comparison storage required");
        matrices.requireBinding(main); parents.requireBinding(main);
    }
    List<ProductHumanRequestLocks.SourceUse> discover(String taskId, Selection selection) {
        bound();
        var current = store.read(taskId, selection.comparisonId());
        var uses = new ArrayList<ProductHumanRequestLocks.SourceUse>(uses(current));
        boolean needsParent = selection.reportIds().stream().anyMatch(id -> !selection.comparisonId().equals(origin(taskId, id)));
        if (needsParent) {
            var seen = new HashSet<String>(); seen.add(selection.comparisonId());
            var missing = new HashSet<String>(); current.input().candidates().forEach(c -> missing.add(c.candidateId()));
            String id = current.input().previousComparisonId();
            while (id != null) {
                if (seen.size() > 63 || !seen.add(id)) throw unavailable();
                var prior = store.read(taskId, id); uses.addAll(uses(prior));
                missing.removeAll(db.queryForList("SELECT candidate_id FROM con5_matrix_report WHERE task_id=? AND comparison_id=?",
                        String.class, taskId, id));
                if (missing.isEmpty()) break;
                id = prior.input().previousComparisonId();
            }
        }
        return List.copyOf(uses);
    }
    ProductHumanRequestReferences.Comparison load(ProductHumanTaskTransaction.Context context,
            ProductHumanTaskFacts.Snapshot facts, Selection selection, Jwt jwt, boolean replay) {
        return loadChecked(context, facts, selection, jwt, replay).reference();
    }
    Checked loadChecked(ProductHumanTaskTransaction.Context context,
            ProductHumanTaskFacts.Snapshot facts, Selection selection, Jwt jwt, boolean replay) {
        bound(); String task = context.task().id(), id = selection.comparisonId();
        if (!replay && !id.equals(store.current(task))) throw unavailable();
        var input = inputs.read(task, id, jwt); var command = command(input);
        if (input.input().taskVersion() != facts.taskVersion() || !command.goalId().equals(facts.goal().id())
                || command.goalRevision() != facts.goal().revision() || !command.inputId().equals(facts.input().id())
                || command.expectedTaskVersion() != facts.taskVersion()) throw unavailable();
        var reports = new ArrayList<ProductHumanRequestReferences.Report>();
        Instant until = input.input().expiresAt(), accessUntil = until;
        for (String reportId : selection.reportIds()) {
            var reportOrigin = origin(task, reportId);
            ProductComparisonMatrixResults.Report report;
            List<ProductComparisonParentEvidenceReader.Ancestor> ancestry;
            Instant reportUntil;
            if (id.equals(reportOrigin)) {
                report = replay ? matrices.historicalReport(task, id, reportId, jwt, inputs) : matrices.report(task, id, reportId, jwt);
                ancestry = List.of(); reportUntil = report.result().expiresAt().isBefore(input.input().expiresAt())
                        ? report.result().expiresAt() : input.input().expiresAt();
                if (!report.result().inputSha().equals(input.inputSha())
                        || input.input().candidates().stream().noneMatch(c -> c.candidateId().equals(report.result().candidateId()))) throw unavailable();
            } else {
                var parent = replay ? parents.historicalReport(task, id, reportId, jwt) : parents.report(task, id, reportId, jwt);
                if (parent.expiresAt().isBefore(accessUntil)) accessUntil = parent.expiresAt();
                report = parent.report(); ancestry = parent.ancestry(); reportUntil = parents.retainedUntil(parent, jwt);
                if (!id.equals(parent.comparisonId()) || !input.inputSha().equals(parent.inputSha())
                        || !reportOrigin.equals(parent.sourceComparisonId())) throw unavailable();
            }
            var value = report.result();
            if (!task.equals(value.taskId()) || !reportId.equals(value.reportId())
                    || !reportOrigin.equals(value.comparisonId()) || !"COMMITTED".equals(report.receipt().status())) throw unavailable();
            if (value.expiresAt().isBefore(reportUntil)) reportUntil = value.expiresAt();
            if (reportUntil.isBefore(until)) until = reportUntil;
            reports.add(new ProductHumanRequestReferences.Report(reportId, value.resultVersion(), report.reportSha(),
                    value.candidateId(), value.comparisonId(), value.inputSha(), reportUntil, ancestry));
        }
        if (!input.equals(inputs.read(task, id, jwt)) || !replay && !id.equals(store.current(task))) throw unavailable();
        return new Checked(new ProductHumanRequestReferences.Comparison(id, input.inputSha(), facts.taskVersion(), command.goalId(),
                command.goalRevision(), command.inputId(), until, reports), accessUntil.isBefore(until) ? accessUntil : until);
    }
    private List<ProductHumanRequestLocks.SourceUse> uses(ProductComparisonInputs.View input) {
        var command = command(input); var result = new ArrayList<ProductHumanRequestLocks.SourceUse>();
        if (command.target().patent() != null) result.add(use(command.target().patent()));
        command.candidates().forEach(ref -> result.add(use(ref))); return List.copyOf(result);
    }
    private ProductComparisonContracts.Prepare command(ProductComparisonInputs.View input) {
        JsonNode snapshot = json.stored(input.input().currentPreparationSnapshot(), JsonNode.class);
        return json.stored(json.canonical(snapshot.get("command")), ProductComparisonContracts.Prepare.class);
    }
    private static ProductHumanRequestLocks.SourceUse use(ProductComparisonContracts.DocumentRef ref) {
        return new ProductHumanRequestLocks.SourceUse(ref.sourceId(), Set.of("CLAIMS"), Set.of("CLAIMS"));
    }
    private String origin(String task, String report) {
        var ids = db.queryForList("SELECT comparison_id FROM con5_matrix_report WHERE task_id=? AND report_id=?", String.class, task, report);
        if (ids.size() != 1) throw unavailable(); return ids.getFirst();
    }
    private void bound() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(db.getDataSource()))) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Human comparison references unavailable"); }
}
