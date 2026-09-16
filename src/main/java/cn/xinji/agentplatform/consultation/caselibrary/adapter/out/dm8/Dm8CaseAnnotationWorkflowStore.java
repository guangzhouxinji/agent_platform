package cn.xinji.agentplatform.consultation.caselibrary.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Annotation;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Audit;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Task;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowStore;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public final class Dm8CaseAnnotationWorkflowStore implements CaseAnnotationWorkflowStore {
    private final Supplier<Connection> connections;

    public Dm8CaseAnnotationWorkflowStore(Supplier<Connection> connections) { this.connections = connections; }

    @Override public Task createIfAbsent(String requester, Task task, Annotation initial, Audit audit) {
        Optional<Task> existing = findTask(requester, task.caseId(), task.sourceVersion(), task.config());
        if (existing.isPresent()) return existing.get();
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                insertTask(connection, requester, task);
                insertAnnotation(connection, initial);
                insertVersion(connection, initial);
                insertAudit(connection, audit);
                connection.commit();
                return task;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                Optional<Task> concurrent = findTask(requester, task.caseId(), task.sourceVersion(), task.config());
                if (concurrent.isPresent()) return concurrent.get();
                throw persistence("create extraction", failure);
            }
        } catch (SQLException failure) {
            throw persistence("create extraction", failure);
        }
    }

    @Override public Optional<Annotation> current(String annotationId) {
        String sql = "SELECT a.case_id,a.source_version,v.annotation_version,v.annotation_state,v.fact_text,v.issue_text,v.holding_text,v.source_location,v.actor_ref,v.reason_text,v.created_at FROM con_case_annotation a JOIN con_case_annotation_version v ON v.annotation_id=a.annotation_id AND v.annotation_version=a.current_version WHERE a.annotation_id=?";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, annotationId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(mapAnnotation(rows, annotationId)) : Optional.empty(); }
        } catch (SQLException failure) { throw persistence("read current annotation", failure); }
    }

    @Override public Annotation append(int expectedVersion, Annotation next, Audit audit) {
        try (Connection connection = connections.get()) {
            connection.setAutoCommit(false);
            try {
                int rowVersion = rowVersion(connection, next.annotationId(), expectedVersion);
                String update = "UPDATE con_case_annotation SET current_version=?,current_state=?,updated_at=?,row_version=row_version+1 WHERE annotation_id=? AND current_version=? AND row_version=?";
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setInt(1, next.version()); statement.setString(2, next.state()); statement.setTimestamp(3, timestamp(next.updatedAt()));
                    statement.setString(4, next.annotationId()); statement.setInt(5, expectedVersion); statement.setInt(6, rowVersion);
                    if (statement.executeUpdate() != 1) throw new VersionConflictException(next.annotationId());
                }
                insertVersion(connection, next);
                insertAudit(connection, audit);
                connection.commit();
                return next;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof VersionConflictException conflict) throw conflict;
                throw persistence("append annotation version", failure);
            }
        } catch (SQLException failure) { throw persistence("append annotation version", failure); }
    }

    @Override public List<Annotation> annotationsForCase(String caseId) {
        String sql = "SELECT a.annotation_id,a.case_id,a.source_version,v.annotation_version,v.annotation_state,v.fact_text,v.issue_text,v.holding_text,v.source_location,v.actor_ref,v.reason_text,v.created_at FROM con_case_annotation a JOIN con_case_annotation_version v ON v.annotation_id=a.annotation_id WHERE a.case_id=? ORDER BY a.annotation_id,v.annotation_version";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, caseId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Annotation> result = new ArrayList<>();
                while (rows.next()) result.add(mapAnnotation(rows, rows.getString("annotation_id")));
                return List.copyOf(result);
            }
        } catch (SQLException failure) { throw persistence("read annotation history", failure); }
    }

    @Override public List<Audit> auditsForCase(String caseId) {
        String sql = "SELECT u.annotation_id,u.action_name,u.actor_ref,u.reason_text,u.annotation_version,u.created_at FROM con_case_annotation_audit u JOIN con_case_annotation a ON a.annotation_id=u.annotation_id WHERE a.case_id=? ORDER BY u.created_at,u.annotation_version,u.audit_id";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, caseId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Audit> result = new ArrayList<>();
                while (rows.next()) result.add(new Audit(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getInt(5), rows.getTimestamp(6).toInstant().toString()));
                return List.copyOf(result);
            }
        } catch (SQLException failure) { throw persistence("read annotation audit", failure); }
    }

    @Override public Optional<Annotation> published(String caseId, String sourceVersion) {
        String sql = "SELECT a.annotation_id,a.case_id,a.source_version,v.annotation_version,v.annotation_state,v.fact_text,v.issue_text,v.holding_text,v.source_location,v.actor_ref,v.reason_text,v.created_at FROM con_case_annotation a JOIN con_case_annotation_version v ON v.annotation_id=a.annotation_id AND v.annotation_version=a.current_version WHERE a.case_id=? AND a.source_version=? AND a.current_state='PUBLISHED' ORDER BY a.updated_at DESC,a.annotation_id FETCH FIRST 1 ROWS ONLY";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, caseId); statement.setString(2, sourceVersion);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(mapAnnotation(rows, rows.getString("annotation_id"))) : Optional.empty(); }
        } catch (SQLException failure) { throw persistence("read published annotation", failure); }
    }

    @Override public int failInterruptedTasks(Instant now) {
        String sql = "UPDATE con_case_extract_task SET status='FAILED',error_code='RESTART_INTERRUPTED',updated_at=?,row_version=row_version+1 WHERE status='PROCESSING'";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now)); return statement.executeUpdate();
        } catch (SQLException failure) { throw persistence("recover interrupted extraction", failure); }
    }

    @Override public String storageMode() { return "DM8"; }

    private Optional<Task> findTask(String requester, String caseId, String sourceVersion, String config) {
        String sql = "SELECT task_id,case_id,source_version,extract_config,status,attempts,annotation_id,request_id,updated_at FROM con_case_extract_task WHERE requester_ref=? AND case_id=? AND source_version=? AND extract_config=?";
        try (Connection connection = connections.get(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requester); statement.setString(2, caseId); statement.setString(3, sourceVersion); statement.setString(4, config);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Task(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getInt(6), rows.getString(7), rows.getString(8), rows.getTimestamp(9).toInstant().toString())) : Optional.empty();
            }
        } catch (SQLException failure) { throw persistence("read extraction", failure); }
    }

    private static int rowVersion(Connection connection, String annotationId, int expectedVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_version,row_version FROM con_case_annotation WHERE annotation_id=?")) {
            statement.setString(1, annotationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("annotation not found");
                if (rows.getInt(1) != expectedVersion) throw new VersionConflictException(annotationId);
                return rows.getInt(2);
            }
        }
    }

    private static void insertTask(Connection connection, String requester, Task task) throws SQLException {
        String sql = "INSERT INTO con_case_extract_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Timestamp time = timestamp(task.updatedAt()); int index = 1;
            statement.setString(index++, task.taskId()); statement.setString(index++, requester); statement.setString(index++, task.caseId());
            statement.setString(index++, task.sourceVersion()); statement.setString(index++, task.config()); statement.setString(index++, task.status());
            statement.setInt(index++, task.attempts()); statement.setString(index++, task.annotationId()); statement.setString(index++, task.requestId());
            statement.setString(index++, task.requestId()); statement.setNull(index++, Types.VARCHAR); statement.setInt(index++, 0);
            statement.setTimestamp(index++, time); statement.setTimestamp(index, time); statement.executeUpdate();
        }
    }

    private static void insertAnnotation(Connection connection, Annotation value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_case_annotation VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            Timestamp time = timestamp(value.updatedAt());
            statement.setString(1, value.annotationId()); statement.setString(2, value.caseId()); statement.setString(3, value.sourceVersion());
            statement.setInt(4, value.version()); statement.setString(5, value.state()); statement.setInt(6, 0);
            statement.setTimestamp(7, time); statement.setTimestamp(8, time); statement.executeUpdate();
        }
    }

    private static void insertVersion(Connection connection, Annotation value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_case_annotation_version VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, value.annotationId()); statement.setInt(2, value.version()); statement.setString(3, value.state());
            statement.setString(4, value.fact()); statement.setString(5, value.issue()); statement.setString(6, value.holding());
            statement.setString(7, value.sourceLocation()); statement.setString(8, value.actor()); statement.setString(9, value.reason());
            statement.setTimestamp(10, timestamp(value.updatedAt())); statement.executeUpdate();
        }
    }

    private static void insertAudit(Connection connection, Audit value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO con_case_annotation_audit VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, value.annotationId()); statement.setInt(3, value.annotationVersion());
            statement.setString(4, value.action()); statement.setString(5, value.actor()); statement.setString(6, value.reason());
            statement.setTimestamp(7, timestamp(value.createdAt())); statement.executeUpdate();
        }
    }

    private static Annotation mapAnnotation(ResultSet rows, String annotationId) throws SQLException {
        return new Annotation(annotationId, rows.getString("case_id"), rows.getString("source_version"), rows.getInt("annotation_version"),
                rows.getString("annotation_state"), rows.getString("fact_text"), rows.getString("issue_text"), rows.getString("holding_text"),
                rows.getString("source_location"), rows.getString("actor_ref"), rows.getString("reason_text"), rows.getTimestamp("created_at").toInstant().toString());
    }
    private static Timestamp timestamp(String value) { return Timestamp.from(Instant.parse(value)); }
    private static IllegalStateException persistence(String action, Exception cause) { return new IllegalStateException("DM8 case annotation " + action + " failed", cause); }
    private static void rollback(Connection connection, Exception original) { try { connection.rollback(); } catch (SQLException failure) { original.addSuppressed(failure); } }
}
