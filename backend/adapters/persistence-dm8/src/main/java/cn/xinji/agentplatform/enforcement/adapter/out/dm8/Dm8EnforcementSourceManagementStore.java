package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementSourceStoreSupport.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

public final class Dm8EnforcementSourceManagementStore implements Store {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public Dm8EnforcementSourceManagementStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper.copy().findAndRegisterModules();
    }

    @Override
    public List<SourceConfiguration> list(String organization) {
        return queryConfigurations("WHERE organization_ref=? ORDER BY updated_at DESC", organization);
    }

    @Override
    public Optional<SourceConfiguration> find(String organization, String sourceId) {
        List<SourceConfiguration> rows = queryConfigurations("WHERE organization_ref=? AND source_id=?",
                organization, sourceId);
        return rows.stream().findFirst();
    }

    @Override
    public SourceConfiguration create(String organization, String actor, SaveCommand command, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String sql = "INSERT INTO enf_source_configuration (source_id,organization_ref,source_name,data_type,access_method,controlled_endpoint,credential_ref,field_mapping_json,owner_name,schedule_mode,schedule_interval,schedule_unit,enabled,health_status,last_success_cursor,last_success_at,active_task_id,version_no,created_by,created_at,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                execute(connection, sql, command.sourceId(), organization, command.sourceName(), command.dataType(),
                        command.accessMethod(), command.controlledEndpoint(), command.credentialRef(), json(command.fieldMapping()),
                        command.ownerName(), command.scheduleMode(), command.scheduleInterval(), command.scheduleUnit(),
                        command.enabled() ? 1 : 0, "NOT_TESTED", null, null, null, 1, actor, time(now), actor, time(now));
                SourceConfiguration created = read(connection, organization, command.sourceId()).orElseThrow();
                insertHistory(connection, created, "CREATED", actor, now);
                connection.commit();
                return created;
            } catch (Exception failure) {
                connection.rollback();
                if (isConstraint(failure)) throw EnforcementSourceManagementService.failure("SOURCE_ALREADY_EXISTS");
                throw failure;
            }
        } catch (SourceManagementException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public SourceConfiguration update(String organization, String actor, SaveCommand command, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SourceConfiguration current = read(connection, organization, command.sourceId())
                        .orElseThrow(() -> EnforcementSourceManagementService.failure("SOURCE_NOT_FOUND"));
                if (current.version() != command.expectedVersion()) {
                    throw EnforcementSourceManagementService.failure("VERSION_CONFLICT");
                }
                int changed = execute(connection, "UPDATE enf_source_configuration SET source_name=?,data_type=?,access_method=?,controlled_endpoint=?,credential_ref=?,field_mapping_json=?,owner_name=?,schedule_mode=?,schedule_interval=?,schedule_unit=?,enabled=?,health_status='NOT_TESTED',version_no=version_no+1,updated_by=?,updated_at=? WHERE source_id=? AND organization_ref=? AND version_no=?",
                        command.sourceName(), command.dataType(), command.accessMethod(), command.controlledEndpoint(),
                        command.credentialRef(), json(command.fieldMapping()), command.ownerName(), command.scheduleMode(),
                        command.scheduleInterval(), command.scheduleUnit(), command.enabled() ? 1 : 0, actor, time(now),
                        command.sourceId(), organization, command.expectedVersion());
                if (changed != 1) throw EnforcementSourceManagementService.failure("VERSION_CONFLICT");
                SourceConfiguration updated = read(connection, organization, command.sourceId()).orElseThrow();
                insertHistory(connection, updated, "UPDATED", actor, now);
                connection.commit();
                return updated;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SourceManagementException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public SourceConfiguration setEnabled(String organization, String actor, String sourceId, int expectedVersion,
                                          boolean enabled, Instant now) {
        SourceConfiguration current = find(organization, sourceId)
                .orElseThrow(() -> EnforcementSourceManagementService.failure("SOURCE_NOT_FOUND"));
        SaveCommand command = new SaveCommand(current.sourceId(), current.sourceName(), current.dataType(),
                current.accessMethod(), current.controlledEndpoint(), current.credentialRef(), current.fieldMapping(),
                current.ownerName(), current.scheduleMode(), current.scheduleInterval(), current.scheduleUnit(), enabled,
                expectedVersion);
        return update(organization, actor, command, now);
    }

    @Override
    public ConnectionTestResult recordConnectionTest(SourceConfiguration source, String actor, String requestId,
                                                     ConnectionCapabilities capabilities, Instant now) {
        String result = capabilities.errorCode() == null ? "SUCCEEDED" : "FAILED";
        String testId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO enf_source_connection_test (test_id,source_id,organization_ref,configuration_version,result_status,authentication_status,pagination_status,time_field_status,unique_key_status,response_shape_status,error_code,tested_by,tested_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        testId, source.sourceId(), source.organizationRef(), source.version(), result,
                        capabilities.authentication(), capabilities.pagination(), capabilities.timeField(),
                        capabilities.uniqueKey(), capabilities.responseShape(), capabilities.errorCode(), actor, time(now), requestId);
                execute(connection, "UPDATE enf_source_configuration SET health_status=?,updated_at=? WHERE source_id=? AND organization_ref=?",
                        "SUCCEEDED".equals(result) ? "HEALTHY" : "UNHEALTHY", time(now), source.sourceId(), source.organizationRef());
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw unavailable(failure);
        }
        return new ConnectionTestResult(testId, source.sourceId(), source.version(), result, capabilities,
                actor, now, requestId);
    }

    @Override
    public SyncTask beginSync(SourceConfiguration source, String actor, String idempotencyKey, String triggerType,
                              String requestId, Instant now) {
        Optional<SyncTask> replay = taskByKey(source.organizationRef(), source.sourceId(), idempotencyKey);
        if (replay.isPresent()) {
            if (replay.get().configurationVersion() != source.version()) {
                throw EnforcementSourceManagementService.failure("IDEMPOTENCY_CONFLICT");
            }
            return replay.get();
        }
        String taskId = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int locked = execute(connection, "UPDATE enf_source_configuration SET active_task_id=? WHERE source_id=? AND organization_ref=? AND version_no=? AND active_task_id IS NULL",
                        taskId, source.sourceId(), source.organizationRef(), source.version());
                if (locked != 1) throw EnforcementSourceManagementService.failure("SOURCE_SYNC_IN_PROGRESS");
                execute(connection, "INSERT INTO enf_source_sync_task (task_id,source_id,organization_ref,configuration_version,trigger_type,idempotency_key,status,processed_count,imported_count,failed_count,start_cursor,result_cursor,error_code,started_by,created_at,started_at,completed_at,request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, source.sourceId(), source.organizationRef(), source.version(), triggerType, idempotencyKey,
                        "running", 0, 0, 0, source.lastSuccessCursor(), null, null, actor, time(now), time(now), null, requestId);
                connection.commit();
                return new SyncTask(taskId, source.sourceId(), source.organizationRef(), source.version(), triggerType,
                        "queued", 0, 0, 0, source.lastSuccessCursor(), null, null, actor, now, now, null,
                        requestId, List.of());
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SourceManagementException failure) {
            throw failure;
        } catch (Exception failure) {
            Optional<SyncTask> concurrentReplay = taskByKey(source.organizationRef(), source.sourceId(), idempotencyKey);
            if (concurrentReplay.isPresent()) return concurrentReplay.get();
            throw unavailable(failure);
        }
    }

    @Override
    public SyncTask completeSync(SyncTask task, List<SourceRecord> records, List<SyncFailure> failures, Instant now) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int imported = 0;
                for (SourceRecord record : records) {
                    if (!snapshotExists(connection, task.sourceId(), record.sourceRecordId(), record.sourceVersion())) {
                        execute(connection, "INSERT INTO enf_source_record_snapshot (snapshot_id,source_id,organization_ref,source_record_id,source_version,source_updated_at,fetched_at,payload_json,content_sha256,withdrawal_status,task_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                UUID.randomUUID().toString(), task.sourceId(), task.organizationRef(), record.sourceRecordId(),
                                record.sourceVersion(), time(record.sourceUpdatedAt()), time(now), record.payloadJson(),
                                record.contentSha256(), record.withdrawalStatus(), task.taskId());
                        imported++;
                    }
                }
                for (SyncFailure failure : failures) {
                    execute(connection, "INSERT INTO enf_source_sync_failure (failure_id,task_id,sequence_no,source_record_ref,error_code,error_message,created_at) VALUES (?,?,?,?,?,?,?)",
                            UUID.randomUUID().toString(), task.taskId(), failure.sequence(), failure.sourceRecordRef(),
                            failure.errorCode(), failure.message(), time(now));
                }
                String status = failures.isEmpty() ? "succeeded" : records.isEmpty() ? "failed" : "partial_failed";
                String cursor = continuousCursor(task.startCursor(), records, failures);
                String errorCode = failures.isEmpty() ? null : failures.getFirst().errorCode();
                execute(connection, "UPDATE enf_source_sync_task SET status=?,processed_count=?,imported_count=?,failed_count=?,result_cursor=?,error_code=?,completed_at=? WHERE task_id=? AND status='running'",
                        status, records.size() + failures.size(), imported, failures.size(), cursor, errorCode, time(now), task.taskId());
                if (cursor != null) {
                    execute(connection, "UPDATE enf_source_configuration SET last_success_cursor=?,last_success_at=?,active_task_id=NULL WHERE source_id=? AND organization_ref=? AND active_task_id=?",
                            cursor, time(now), task.sourceId(), task.organizationRef(), task.taskId());
                } else {
                    execute(connection, "UPDATE enf_source_configuration SET active_task_id=NULL WHERE source_id=? AND organization_ref=? AND active_task_id=?",
                            task.sourceId(), task.organizationRef(), task.taskId());
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (Exception failure) {
            throw unavailable(failure);
        }
        return task(task.organizationRef(), task.sourceId(), task.taskId()).orElseThrow();
    }

    @Override public List<SyncTask> tasks(String organization, String sourceId) {
        return queryTasks("WHERE organization_ref=? AND source_id=? ORDER BY created_at DESC", organization, sourceId);
    }
    @Override public Optional<SyncTask> task(String organization, String sourceId, String taskId) {
        return queryTasks("WHERE organization_ref=? AND source_id=? AND task_id=?", organization, sourceId, taskId)
                .stream().findFirst();
    }
    @Override public List<ConfigurationHistory> history(String organization, String sourceId) {
        String sql = "SELECT history_id,source_id,version_no,action_type,configuration_json,changed_by,changed_at FROM enf_source_configuration_history WHERE organization_ref=? AND source_id=? ORDER BY version_no DESC";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, organization, sourceId);
            try (ResultSet result = statement.executeQuery()) {
                List<ConfigurationHistory> rows = new ArrayList<>();
                while (result.next()) rows.add(new ConfigurationHistory(result.getString(1), result.getString(2),
                        result.getInt(3), result.getString(4), result.getString(5), result.getString(6), instant(result.getTimestamp(7))));
                return List.copyOf(rows);
            }
        } catch (Exception failure) { throw unavailable(failure); }
    }
    @Override public List<SourceConfiguration> due(Instant now) {
        return queryConfigurations("WHERE enabled=1 AND schedule_mode='SCHEDULED' ORDER BY updated_at")
                .stream().filter(source -> source.lastSuccessAt() == null || !source.lastSuccessAt()
                        .plus(duration(source.scheduleInterval(), source.scheduleUnit())).isAfter(now)).toList();
    }

    private List<SourceConfiguration> queryConfigurations(String suffix, Object... values) {
        try (Connection connection = dataSource.getConnection()) {
            return queryConfigurations(connection, suffix, values);
        } catch (Exception failure) { throw unavailable(failure); }
    }
    private List<SourceConfiguration> queryConfigurations(Connection connection, String suffix, Object... values) throws Exception {
        String sql = "SELECT source_id,organization_ref,source_name,data_type,access_method,controlled_endpoint,credential_ref,field_mapping_json,owner_name,schedule_mode,schedule_interval,schedule_unit,enabled,health_status,last_success_cursor,last_success_at,version_no,created_by,created_at,updated_by,updated_at FROM enf_source_configuration " + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                List<SourceConfiguration> rows = new ArrayList<>();
                while (result.next()) rows.add(mapConfiguration(result));
                return List.copyOf(rows);
            }
        }
    }
    private Optional<SourceConfiguration> read(Connection connection, String organization, String sourceId) throws Exception {
        return queryConfigurations(connection, "WHERE organization_ref=? AND source_id=?", organization, sourceId)
                .stream().findFirst();
    }
    private SourceConfiguration mapConfiguration(ResultSet result) throws Exception {
        Integer interval = result.getObject(11) == null ? null : result.getInt(11);
        return new SourceConfiguration(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                result.getString(5), result.getString(6), result.getString(7),
                mapper.readValue(result.getString(8), new TypeReference<Map<String, String>>() {}), result.getString(9),
                result.getString(10), interval, result.getString(12), result.getInt(13) == 1, result.getString(14),
                result.getString(15), instant(result.getTimestamp(16)), result.getInt(17), result.getString(18),
                instant(result.getTimestamp(19)), result.getString(20), instant(result.getTimestamp(21)));
    }
    private List<SyncTask> queryTasks(String suffix, Object... values) {
        String sql = "SELECT task_id,source_id,organization_ref,configuration_version,trigger_type,status,processed_count,imported_count,failed_count,start_cursor,result_cursor,error_code,started_by,created_at,started_at,completed_at,request_id FROM enf_source_sync_task " + suffix;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                List<SyncTask> rows = new ArrayList<>();
                while (result.next()) rows.add(new SyncTask(result.getString(1), result.getString(2), result.getString(3),
                        result.getInt(4), result.getString(5), result.getString(6), result.getInt(7), result.getInt(8),
                        result.getInt(9), result.getString(10), result.getString(11), result.getString(12), result.getString(13),
                        instant(result.getTimestamp(14)), instant(result.getTimestamp(15)), instant(result.getTimestamp(16)),
                        result.getString(17), failures(connection, result.getString(1))));
                return List.copyOf(rows);
            }
        } catch (Exception failure) { throw unavailable(failure); }
    }
    private List<SyncFailure> failures(Connection connection, String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sequence_no,source_record_ref,error_code,error_message FROM enf_source_sync_failure WHERE task_id=? ORDER BY sequence_no")) {
            bind(statement, taskId); try (ResultSet result = statement.executeQuery()) {
                List<SyncFailure> rows = new ArrayList<>();
                while (result.next()) rows.add(new SyncFailure(result.getInt(1), result.getString(2), result.getString(3), result.getString(4)));
                return List.copyOf(rows);
            }
        }
    }
    private Optional<SyncTask> taskByKey(String organization, String sourceId, String key) {
        return queryTasks("WHERE organization_ref=? AND source_id=? AND idempotency_key=?", organization, sourceId, key)
                .stream().findFirst();
    }
    private void insertHistory(Connection connection, SourceConfiguration source, String action, String actor, Instant now) throws Exception {
        execute(connection, "INSERT INTO enf_source_configuration_history (history_id,source_id,organization_ref,version_no,configuration_json,action_type,changed_by,changed_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), source.sourceId(), source.organizationRef(), source.version(), json(source), action, actor, time(now));
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception failure) { throw unavailable(failure); } }
}
