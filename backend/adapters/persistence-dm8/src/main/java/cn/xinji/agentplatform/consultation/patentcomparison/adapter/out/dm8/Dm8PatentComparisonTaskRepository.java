package cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonCommand;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonTask;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** DM8 persistence for immutable synthetic patent-comparison snapshots. */
public final class Dm8PatentComparisonTaskRepository implements PatentComparisonTaskRepository {
    private static final String TASK_COLUMNS = "task_id,requester_ref,idempotency_key,request_fingerprint,purpose,target_source_id,target_version,status,data_mode,source_version,rule_version,score_capability,review_status,limitations_text,trace_id,created_at";
    private final Supplier<Connection> connections;

    public Dm8PatentComparisonTaskRepository(Supplier<Connection> connections) { this.connections = connections; }

    @Override public Optional<PatentComparisonTask> findById(String taskId) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_patent_compare_task WHERE task_id=?", taskId);
    }

    @Override public Optional<PatentComparisonTask> findByIdempotency(String requester, String key) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_patent_compare_task WHERE requester_ref=? AND idempotency_key=?", requester, key);
    }

    @Override public List<PatentComparisonTask> findByRequester(String requester) {
        String sql = "SELECT " + TASK_COLUMNS + " FROM con_patent_compare_task WHERE requester_ref=? ORDER BY created_at DESC,task_id";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requester);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentComparisonTask> tasks = new ArrayList<>();
                while (rows.next()) tasks.add(map(connection, rows));
                return List.copyOf(tasks);
            }
        } catch (SQLException failure) { throw persistence("read history", failure); }
    }

    @Override public PatentComparisonTask saveIfAbsent(PatentComparisonTask task) {
        Optional<PatentComparisonTask> existing = findByIdempotency(task.requester(), task.idempotencyKey());
        if (existing.isPresent()) return existing.get();
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                insertTask(connection, task);
                insertFeatures(connection, task);
                insertCandidates(connection, task);
                insertMappings(connection, task);
                connection.commit();
                return task;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                Optional<PatentComparisonTask> concurrent = findByIdempotency(task.requester(), task.idempotencyKey());
                if (concurrent.isPresent()) return concurrent.get();
                throw persistence("save", failure);
            }
        } catch (SQLException failure) { throw persistence("save", failure); }
    }

    private Optional<PatentComparisonTask> queryOne(String sql, String... values) {
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(connection, rows)) : Optional.empty(); }
        } catch (SQLException failure) { throw persistence("read", failure); }
    }

    private static PatentComparisonTask map(Connection connection, ResultSet row) throws SQLException {
        String taskId = row.getString("task_id");
        List<PatentComparisonCommand.Feature> features = features(connection, taskId);
        List<PatentComparisonTask.PairResult> pairs = candidates(connection, taskId, features);
        List<PatentComparisonCommand.DocumentRef> candidateRefs = pairs.stream().map(PatentComparisonTask.PairResult::candidate).toList();
        PatentComparisonCommand command = new PatentComparisonCommand(
                PatentComparisonCommand.Purpose.valueOf(row.getString("purpose")),
                new PatentComparisonCommand.DocumentRef(row.getString("target_source_id"), row.getString("target_version")),
                candidateRefs, features);
        return new PatentComparisonTask(taskId, row.getString("requester_ref"), row.getString("idempotency_key"),
                row.getString("request_fingerprint"), command, row.getString("status"), row.getString("data_mode"),
                row.getString("source_version"), row.getString("rule_version"), row.getString("score_capability"),
                row.getString("review_status"), pairs, decode(row.getString("limitations_text")), row.getString("trace_id"),
                row.getTimestamp("created_at").toInstant());
    }

    private static List<PatentComparisonCommand.Feature> features(Connection connection, String taskId) throws SQLException {
        String sql = "SELECT feature_id,target_text,source_location FROM con_patent_compare_feature WHERE task_id=? ORDER BY feature_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentComparisonCommand.Feature> result = new ArrayList<>();
                while (rows.next()) result.add(new PatentComparisonCommand.Feature(rows.getString(1), rows.getString(2), rows.getString(3)));
                return List.copyOf(result);
            }
        }
    }

    private static List<PatentComparisonTask.PairResult> candidates(Connection connection, String taskId,
            List<PatentComparisonCommand.Feature> features) throws SQLException {
        String sql = "SELECT candidate_order,source_id,source_version,result_status,similarities_text,differences_text,unexecuted_text,auxiliary_opinion FROM con_patent_compare_candidate WHERE task_id=? ORDER BY candidate_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentComparisonTask.PairResult> result = new ArrayList<>();
                while (rows.next()) {
                    int order = rows.getInt(1);
                    var reference = new PatentComparisonCommand.DocumentRef(rows.getString(2), rows.getString(3));
                    result.add(new PatentComparisonTask.PairResult(reference, rows.getString(4), mappings(connection, taskId, order, features),
                            decode(rows.getString(5)), decode(rows.getString(6)), decode(rows.getString(7)), rows.getString(8)));
                }
                return List.copyOf(result);
            }
        }
    }

    private static List<PatentComparisonTask.FeatureMapping> mappings(Connection connection, String taskId, int candidateOrder,
            List<PatentComparisonCommand.Feature> features) throws SQLException {
        String sql = "SELECT feature_order,relation_name,candidate_evidence,candidate_location,difference_text,reason_text FROM con_patent_compare_mapping WHERE task_id=? AND candidate_order=? ORDER BY feature_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId); statement.setInt(2, candidateOrder);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentComparisonTask.FeatureMapping> result = new ArrayList<>();
                while (rows.next()) {
                    PatentComparisonCommand.Feature feature = features.get(rows.getInt(1));
                    result.add(new PatentComparisonTask.FeatureMapping(feature.featureId(), feature.text(), rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void insertTask(Connection connection, PatentComparisonTask task) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(16, "?"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_patent_compare_task (" + TASK_COLUMNS + ") VALUES (" + placeholders + ")")) {
            int i = 1;
            statement.setString(i++, task.taskId()); statement.setString(i++, task.requester()); statement.setString(i++, task.idempotencyKey());
            statement.setString(i++, task.requestFingerprint()); statement.setString(i++, task.inputSnapshot().purpose().name());
            statement.setString(i++, task.inputSnapshot().target().sourceId()); statement.setString(i++, task.inputSnapshot().target().version());
            statement.setString(i++, task.status()); statement.setString(i++, task.dataMode()); statement.setString(i++, task.sourceVersion());
            statement.setString(i++, task.ruleVersion()); statement.setString(i++, task.scoreCapability()); statement.setString(i++, task.reviewStatus());
            statement.setString(i++, encode(task.limitations())); statement.setString(i++, task.traceId()); statement.setTimestamp(i, Timestamp.from(task.createdAt()));
            statement.executeUpdate();
        }
    }

    private static void insertFeatures(Connection connection, PatentComparisonTask task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_patent_compare_feature VALUES (?, ?, ?, ?, ?)")) {
            List<PatentComparisonCommand.Feature> features = task.inputSnapshot().confirmedFeatures();
            for (int order = 0; order < features.size(); order++) {
                var feature = features.get(order);
                statement.setString(1, task.taskId()); statement.setInt(2, order); statement.setString(3, feature.featureId());
                statement.setString(4, feature.text()); statement.setString(5, feature.sourceLocation()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertCandidates(Connection connection, PatentComparisonTask task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_patent_compare_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int order = 0; order < task.pairs().size(); order++) {
                PatentComparisonTask.PairResult pair = task.pairs().get(order);
                statement.setString(1, task.taskId()); statement.setInt(2, order); statement.setString(3, pair.candidate().sourceId());
                statement.setString(4, pair.candidate().version()); statement.setString(5, pair.status());
                statement.setString(6, encode(pair.similarities())); statement.setString(7, encode(pair.differences()));
                statement.setString(8, encode(pair.unexecutedItems())); statement.setString(9, pair.auxiliaryOpinion()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertMappings(Connection connection, PatentComparisonTask task) throws SQLException {
        Map<String, Integer> featureOrders = new HashMap<>();
        List<PatentComparisonCommand.Feature> features = task.inputSnapshot().confirmedFeatures();
        for (int order = 0; order < features.size(); order++) featureOrders.put(features.get(order).featureId(), order);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_patent_compare_mapping VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int candidateOrder = 0; candidateOrder < task.pairs().size(); candidateOrder++) {
                for (PatentComparisonTask.FeatureMapping mapping : task.pairs().get(candidateOrder).mappings()) {
                    Integer featureOrder = featureOrders.get(mapping.featureId());
                    if (featureOrder == null) throw new IllegalArgumentException("mapping references unknown feature: " + mapping.featureId());
                    statement.setString(1, task.taskId()); statement.setInt(2, candidateOrder); statement.setInt(3, featureOrder);
                    statement.setString(4, mapping.relation()); statement.setString(5, mapping.candidateEvidence());
                    statement.setString(6, mapping.candidateLocation()); statement.setString(7, mapping.difference());
                    statement.setString(8, mapping.reason()); statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static String encode(List<String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return String.join("\n", values.stream().map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList());
    }

    private static List<String> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return Arrays.stream(value.split("\n", -1)).map(item -> new String(decoder.decode(item), StandardCharsets.UTF_8)).toList();
    }

    private static IllegalStateException persistence(String action, Exception cause) {
        return new IllegalStateException("DM8 patent comparison " + action + " failed", cause);
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }
}
