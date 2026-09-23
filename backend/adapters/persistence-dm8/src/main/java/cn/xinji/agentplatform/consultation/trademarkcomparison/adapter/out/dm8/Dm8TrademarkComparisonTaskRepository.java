package cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** DM8 persistence for immutable synthetic trademark-comparison snapshots. */
public final class Dm8TrademarkComparisonTaskRepository implements TrademarkComparisonTaskRepository {
    private static final String TASK_COLUMNS = "task_id,requester_ref,idempotency_key,request_fingerprint,purpose,target_source_id,target_version,confirmed_categories,confirmed_goods_services,selected_dimensions,status,data_mode,source_version,rule_version,report_capability,review_status,limitations_text,trace_id,created_at";
    private final Supplier<Connection> connections;

    public Dm8TrademarkComparisonTaskRepository(Supplier<Connection> connections) { this.connections = connections; }

    @Override public Optional<TrademarkComparisonTask> findById(String taskId) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_trademark_compare_task WHERE task_id=?", taskId);
    }

    @Override public Optional<TrademarkComparisonTask> findByIdempotency(String requester, String key) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_trademark_compare_task WHERE requester_ref=? AND idempotency_key=?", requester, key);
    }

    @Override public List<TrademarkComparisonTask> findByRequester(String requester) {
        String sql = "SELECT " + TASK_COLUMNS + " FROM con_trademark_compare_task WHERE requester_ref=? ORDER BY created_at DESC,task_id";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requester);
            try (ResultSet rows = statement.executeQuery()) {
                List<TrademarkComparisonTask> tasks = new ArrayList<>();
                while (rows.next()) tasks.add(map(connection, rows));
                return List.copyOf(tasks);
            }
        } catch (SQLException failure) { throw persistence("read history", failure); }
    }

    @Override public TrademarkComparisonTask saveIfAbsent(TrademarkComparisonTask task) {
        Optional<TrademarkComparisonTask> existing = findByIdempotency(task.requester(), task.idempotencyKey());
        if (existing.isPresent()) return existing.get();
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                insertTask(connection, task);
                insertCandidates(connection, task);
                insertDimensions(connection, task);
                connection.commit();
                return task;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                Optional<TrademarkComparisonTask> concurrent = findByIdempotency(task.requester(), task.idempotencyKey());
                if (concurrent.isPresent()) return concurrent.get();
                throw persistence("save", failure);
            }
        } catch (SQLException failure) { throw persistence("save", failure); }
    }

    private Optional<TrademarkComparisonTask> queryOne(String sql, String... values) {
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(map(connection, rows)) : Optional.empty(); }
        } catch (SQLException failure) { throw persistence("read", failure); }
    }

    private static TrademarkComparisonTask map(Connection connection, ResultSet row) throws SQLException {
        String taskId = row.getString("task_id");
        List<TrademarkComparisonTask.PairResult> pairs = candidates(connection, taskId);
        List<TrademarkComparisonCommand.DocumentRef> candidateRefs = pairs.stream().map(TrademarkComparisonTask.PairResult::candidate).toList();
        List<TrademarkComparisonCommand.Dimension> selectedDimensions = decode(row.getString("selected_dimensions")).stream()
                .map(TrademarkComparisonCommand.Dimension::valueOf).toList();
        TrademarkComparisonCommand command = new TrademarkComparisonCommand(
                TrademarkComparisonCommand.Purpose.valueOf(row.getString("purpose")),
                new TrademarkComparisonCommand.DocumentRef(row.getString("target_source_id"), row.getString("target_version")),
                candidateRefs, decode(row.getString("confirmed_categories")), decode(row.getString("confirmed_goods_services")),
                selectedDimensions);
        return new TrademarkComparisonTask(taskId, row.getString("requester_ref"), row.getString("idempotency_key"),
                row.getString("request_fingerprint"), command, row.getString("status"), row.getString("data_mode"),
                row.getString("source_version"), row.getString("rule_version"), row.getString("report_capability"),
                row.getString("review_status"), pairs, decode(row.getString("limitations_text")), row.getString("trace_id"),
                row.getTimestamp("created_at").toInstant());
    }

    private static List<TrademarkComparisonTask.PairResult> candidates(Connection connection, String taskId) throws SQLException {
        String sql = "SELECT candidate_order,source_id,source_version,result_status,missing_items_text,auxiliary_opinion FROM con_trademark_compare_candidate WHERE task_id=? ORDER BY candidate_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                List<TrademarkComparisonTask.PairResult> result = new ArrayList<>();
                while (rows.next()) {
                    int order = rows.getInt(1);
                    var reference = new TrademarkComparisonCommand.DocumentRef(rows.getString(2), rows.getString(3));
                    result.add(new TrademarkComparisonTask.PairResult(reference, rows.getString(4), dimensions(connection, taskId, order),
                            decode(rows.getString(5)), rows.getString(6)));
                }
                return List.copyOf(result);
            }
        }
    }

    private static List<TrademarkComparisonTask.DimensionResult> dimensions(Connection connection, String taskId, int candidateOrder) throws SQLException {
        String sql = "SELECT dimension_name,result_state,observation_text,evidence_text,reason_text FROM con_trademark_compare_dimension WHERE task_id=? AND candidate_order=? ORDER BY dimension_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId); statement.setInt(2, candidateOrder);
            try (ResultSet rows = statement.executeQuery()) {
                List<TrademarkComparisonTask.DimensionResult> result = new ArrayList<>();
                while (rows.next()) result.add(new TrademarkComparisonTask.DimensionResult(
                        TrademarkComparisonCommand.Dimension.valueOf(rows.getString(1)), rows.getString(2), rows.getString(3),
                        decode(rows.getString(4)), rows.getString(5)));
                return List.copyOf(result);
            }
        }
    }

    private static void insertTask(Connection connection, TrademarkComparisonTask task) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(19, "?"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_trademark_compare_task (" + TASK_COLUMNS + ") VALUES (" + placeholders + ")")) {
            int i = 1;
            statement.setString(i++, task.taskId()); statement.setString(i++, task.requester()); statement.setString(i++, task.idempotencyKey());
            statement.setString(i++, task.requestFingerprint()); statement.setString(i++, task.inputSnapshot().purpose().name());
            statement.setString(i++, task.inputSnapshot().target().sourceId()); statement.setString(i++, task.inputSnapshot().target().version());
            statement.setString(i++, encode(task.inputSnapshot().confirmedCategories()));
            statement.setString(i++, encode(task.inputSnapshot().confirmedGoodsServices()));
            statement.setString(i++, encode(task.inputSnapshot().dimensions().stream().map(Enum::name).toList()));
            statement.setString(i++, task.status()); statement.setString(i++, task.dataMode()); statement.setString(i++, task.sourceVersion());
            statement.setString(i++, task.ruleVersion()); statement.setString(i++, task.reportCapability()); statement.setString(i++, task.reviewStatus());
            statement.setString(i++, encode(task.limitations())); statement.setString(i++, task.traceId()); statement.setTimestamp(i, Timestamp.from(task.createdAt()));
            statement.executeUpdate();
        }
    }

    private static void insertCandidates(Connection connection, TrademarkComparisonTask task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_trademark_compare_candidate VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (int order = 0; order < task.pairs().size(); order++) {
                TrademarkComparisonTask.PairResult pair = task.pairs().get(order);
                statement.setString(1, task.taskId()); statement.setInt(2, order); statement.setString(3, pair.candidate().sourceId());
                statement.setString(4, pair.candidate().version()); statement.setString(5, pair.status());
                statement.setString(6, encode(pair.missingItems())); statement.setString(7, pair.auxiliaryOpinion()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertDimensions(Connection connection, TrademarkComparisonTask task) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_trademark_compare_dimension VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (int candidateOrder = 0; candidateOrder < task.pairs().size(); candidateOrder++) {
                List<TrademarkComparisonTask.DimensionResult> dimensions = task.pairs().get(candidateOrder).dimensions();
                for (int dimensionOrder = 0; dimensionOrder < dimensions.size(); dimensionOrder++) {
                    TrademarkComparisonTask.DimensionResult result = dimensions.get(dimensionOrder);
                    statement.setString(1, task.taskId()); statement.setInt(2, candidateOrder); statement.setInt(3, dimensionOrder);
                    statement.setString(4, result.dimension() == null ? null : result.dimension().name()); statement.setString(5, result.state());
                    statement.setString(6, result.observation()); statement.setString(7, encode(result.evidence())); statement.setString(8, result.reason());
                    statement.addBatch();
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
        return new IllegalStateException("DM8 trademark comparison " + action + " failed", cause);
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); }
    }
}
