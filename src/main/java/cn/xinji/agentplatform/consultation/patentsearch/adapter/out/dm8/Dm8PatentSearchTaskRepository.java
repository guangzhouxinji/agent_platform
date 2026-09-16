package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** DM8 persistence for synthetic patent-search task records; catalog documents remain external. */
public final class Dm8PatentSearchTaskRepository implements PatentSearchTaskRepository {
    private static final String TASK_COLUMNS = "task_id,requester_ref,idempotency_key,request_fingerprint,input_mode,input_text,purpose,confirmed_features,patent_types,regions,classifications,application_from,application_to,publication_from,publication_to,status,attempt,data_mode,scoring_version,actual_scope,limitation,error_code,trace_id,row_version,created_at,updated_at";
    private final Supplier<Connection> connections;

    public Dm8PatentSearchTaskRepository(Supplier<Connection> connections) {
        this.connections = connections;
    }

    @Override public Optional<PatentSearchTask> findById(String taskId) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_patent_search_task WHERE task_id=?", taskId);
    }

    @Override public Optional<PatentSearchTask> findByIdempotency(String requester, String key) {
        return queryOne("SELECT " + TASK_COLUMNS + " FROM con_patent_search_task WHERE requester_ref=? AND idempotency_key=?", requester, key);
    }

    @Override public List<PatentSearchTask> findByRequester(String requester) {
        String sql = "SELECT " + TASK_COLUMNS + " FROM con_patent_search_task WHERE requester_ref=? ORDER BY created_at DESC, task_id";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requester);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentSearchTask> result = new ArrayList<>();
                while (rows.next()) result.add(map(connection, rows).task());
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw failure("read history", exception);
        }
    }

    @Override public void save(PatentSearchTask task) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                insertTask(connection, task);
                replaceCandidates(connection, task.taskId(), task.candidates());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw failure("save", exception);
            }
        } catch (SQLException exception) {
            throw failure("save", exception);
        }
    }

    @Override public PatentSearchTask update(String taskId, UnaryOperator<PatentSearchTask> operation) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                VersionedTask current = load(connection, taskId).orElseThrow(() -> new IllegalArgumentException("task not found"));
                PatentSearchTask next = Objects.requireNonNull(operation.apply(current.task()), "updated task");
                if (!taskId.equals(next.taskId())) throw new IllegalArgumentException("task id cannot change");
                int changed = updateTask(connection, next, current.version());
                if (changed != 1) throw new VersionConflictException(taskId);
                replaceCandidates(connection, taskId, next.candidates());
                connection.commit();
                return next;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof VersionConflictException conflict) throw conflict;
                if (exception instanceof IllegalArgumentException invalid) throw invalid;
                throw failure("update", exception);
            }
        } catch (SQLException exception) {
            throw failure("update", exception);
        }
    }

    @Override public int failInterruptedTasks(Instant now) {
        String sql = "UPDATE con_patent_search_task SET status='FAILED',error_code='RESTART_INTERRUPTED',updated_at=?,row_version=row_version+1 WHERE status IN ('QUEUED','PROCESSING')";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("recover interrupted tasks", exception);
        }
    }

    private Optional<PatentSearchTask> queryOne(String sql, String... values) {
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(connection, rows).task()) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read", exception);
        }
    }

    private Optional<VersionedTask> load(Connection connection, String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TASK_COLUMNS + " FROM con_patent_search_task WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(connection, rows)) : Optional.empty();
            }
        }
    }

    private VersionedTask map(Connection connection, ResultSet row) throws SQLException {
        String taskId = row.getString("task_id");
        var scope = new PatentSearchCommand.SearchScope(
                decode(row.getString("patent_types")), decode(row.getString("regions")), decode(row.getString("classifications")),
                localDate(row, "application_from"), localDate(row, "application_to"),
                localDate(row, "publication_from"), localDate(row, "publication_to"));
        var command = new PatentSearchCommand(PatentSearchCommand.InputMode.valueOf(row.getString("input_mode")),
                row.getString("input_text"), row.getString("purpose"), decode(row.getString("confirmed_features")), scope);
        PatentSearchTask task = new PatentSearchTask(taskId, row.getString("requester_ref"), row.getString("idempotency_key"),
                row.getString("request_fingerprint"), command, PatentSearchTask.Status.valueOf(row.getString("status")),
                row.getInt("attempt"), row.getString("data_mode"), row.getString("scoring_version"),
                nullable(row.getString("actual_scope")), candidates(connection, taskId), nullable(row.getString("limitation")),
                nullable(row.getString("error_code")), row.getString("trace_id"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
        return new VersionedTask(task, row.getInt("row_version"));
    }

    private static List<PatentSearchCandidate> candidates(Connection connection, String taskId) throws SQLException {
        String sql = "SELECT source_id,application_number,publication_number,title,patent_type,source_version,score,relevance_label,matched_features,matching_excerpt,location_text FROM con_patent_search_candidate WHERE task_id=? ORDER BY candidate_order";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                List<PatentSearchCandidate> result = new ArrayList<>();
                while (rows.next()) result.add(new PatentSearchCandidate(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getInt(7), rows.getString(8),
                        decode(rows.getString(9)), rows.getString(10), rows.getString(11)));
                return List.copyOf(result);
            }
        }
    }

    private static void insertTask(Connection connection, PatentSearchTask task) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(26, "?"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_patent_search_task (" + TASK_COLUMNS + ") VALUES (" + placeholders + ")")) {
            bindTask(statement, task, 0);
            statement.executeUpdate();
        }
    }

    private static int updateTask(Connection connection, PatentSearchTask task, int version) throws SQLException {
        String sql = "UPDATE con_patent_search_task SET requester_ref=?,idempotency_key=?,request_fingerprint=?,input_mode=?,input_text=?,purpose=?,confirmed_features=?,patent_types=?,regions=?,classifications=?,application_from=?,application_to=?,publication_from=?,publication_to=?,status=?,attempt=?,data_mode=?,scoring_version=?,actual_scope=?,limitation=?,error_code=?,trace_id=?,created_at=?,updated_at=?,row_version=row_version+1 WHERE task_id=? AND row_version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindTask(statement, task, 1);
            statement.setString(25, task.taskId());
            statement.setInt(26, version);
            return statement.executeUpdate();
        }
    }

    private static void bindTask(PreparedStatement statement, PatentSearchTask task, int updateMode) throws SQLException {
        PatentSearchCommand command = task.inputSnapshot();
        PatentSearchCommand.SearchScope scope = command.scope();
        int i = 1;
        if (updateMode == 0) statement.setString(i++, task.taskId());
        statement.setString(i++, task.requester()); statement.setString(i++, task.idempotencyKey());
        statement.setString(i++, task.requestFingerprint()); statement.setString(i++, command.inputMode().name());
        statement.setString(i++, command.input()); statement.setString(i++, command.purpose());
        statement.setString(i++, encode(command.confirmedFeatures())); statement.setString(i++, encode(scope.patentTypes()));
        statement.setString(i++, encode(scope.regions())); statement.setString(i++, encode(scope.classifications()));
        setDate(statement, i++, scope.applicationFrom()); setDate(statement, i++, scope.applicationTo());
        setDate(statement, i++, scope.publicationFrom()); setDate(statement, i++, scope.publicationTo());
        statement.setString(i++, task.status().name()); statement.setInt(i++, task.attempt());
        statement.setString(i++, task.dataMode()); statement.setString(i++, task.scoringVersion());
        statement.setString(i++, task.actualScope()); statement.setString(i++, task.limitation());
        statement.setString(i++, task.errorCode()); statement.setString(i++, task.traceId());
        if (updateMode == 0) statement.setInt(i++, 0);
        statement.setTimestamp(i++, Timestamp.from(task.createdAt())); statement.setTimestamp(i, Timestamp.from(task.updatedAt()));
    }

    private static void replaceCandidates(Connection connection, String taskId, List<PatentSearchCandidate> candidates) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM con_patent_search_candidate WHERE task_id=?")) {
            delete.setString(1, taskId); delete.executeUpdate();
        }
        String sql = "INSERT INTO con_patent_search_candidate VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int order = 0; order < candidates.size(); order++) {
                PatentSearchCandidate value = candidates.get(order);
                int i = 1;
                insert.setString(i++, taskId); insert.setInt(i++, order); insert.setString(i++, value.sourceId());
                insert.setString(i++, value.applicationNumber()); insert.setString(i++, value.publicationNumber());
                insert.setString(i++, value.title()); insert.setString(i++, value.patentType()); insert.setString(i++, value.version());
                insert.setInt(i++, value.score()); insert.setString(i++, value.relevanceLabel());
                insert.setString(i++, encode(value.matchedFeatures())); insert.setString(i++, value.matchingExcerpt());
                insert.setString(i, value.location()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String encode(List<String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return String.join("\n", values.stream().map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList());
    }

    private static List<String> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return Arrays.stream(value.split("\\n", -1)).map(item -> new String(decoder.decode(item), StandardCharsets.UTF_8)).toList();
    }

    private static LocalDate localDate(ResultSet row, String column) throws SQLException {
        java.sql.Date value = row.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static void setDate(PreparedStatement statement, int index, LocalDate value) throws SQLException {
        if (value == null) statement.setNull(index, Types.DATE); else statement.setDate(index, java.sql.Date.valueOf(value));
    }

    private static String nullable(String value) { return value == null ? "" : value; }
    private static IllegalStateException failure(String action, Exception cause) { return new IllegalStateException("DM8 patent search " + action + " failed", cause); }
    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException rollbackFailure) { original.addSuppressed(rollbackFailure); }
    }
    private record VersionedTask(PatentSearchTask task, int version) {}
    public static final class VersionConflictException extends IllegalStateException {
        public VersionConflictException(String taskId) { super("patent search task version conflict: " + taskId); }
    }
}
