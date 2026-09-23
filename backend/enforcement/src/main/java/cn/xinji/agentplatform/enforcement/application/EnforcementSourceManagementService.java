package cn.xinji.agentplatform.enforcement.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EnforcementSourceManagementService {
    public static final String ADAPTER_TYPE = "DEVELOPMENT_FIXED_API";
    public static final String CONTROLLED_ENDPOINT = "local://enforcement/development-feed";
    private static final List<String> REQUIRED_MAPPING_KEYS =
            List.of("sourceRecordId", "sourceVersion", "sourceUpdatedAt", "content");

    private final Store store;
    private final SourceAdapter adapter;
    private final Clock clock;

    public EnforcementSourceManagementService(Store store, SourceAdapter adapter, Clock clock) {
        this.store = store;
        this.adapter = Objects.requireNonNull(adapter);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<SourceConfiguration> list(String actor, String organization) {
        requireContext(actor, organization);
        return store().list(organization);
    }

    public SourceConfiguration read(String actor, String organization, String sourceId) {
        requireContext(actor, organization);
        return store().find(organization, required(sourceId, "sourceId"))
                .orElseThrow(() -> failure("SOURCE_NOT_FOUND"));
    }

    public SourceConfiguration save(String actor, String organization, SaveCommand command) {
        requireContext(actor, organization);
        validate(command);
        Instant now = clock.instant();
        if (command.expectedVersion() == null) {
            return store().create(organization, actor, command, now);
        }
        return store().update(organization, actor, command, now);
    }

    public SourceConfiguration setEnabled(String actor, String organization, String sourceId,
                                          int expectedVersion, boolean enabled) {
        requireContext(actor, organization);
        if (expectedVersion < 1) throw failure("INVALID_INPUT");
        return store().setEnabled(organization, actor, required(sourceId, "sourceId"),
                expectedVersion, enabled, clock.instant());
    }

    public ConnectionTestResult testConnection(String actor, String organization, String sourceId, String requestId) {
        SourceConfiguration configuration = read(actor, organization, sourceId);
        ConnectionCapabilities capabilities;
        try {
            capabilities = adapter.test(configuration);
        } catch (RuntimeException failure) {
            capabilities = new ConnectionCapabilities("FAILED", "FAILED", "FAILED", "FAILED", "FAILED",
                    normalizedError(failure));
        }
        return store().recordConnectionTest(configuration, actor, required(requestId, "requestId"),
                capabilities, clock.instant());
    }

    public SyncTask synchronize(String actor, String organization, String sourceId, String idempotencyKey,
                                String triggerType, String requestId) {
        SourceConfiguration configuration = read(actor, organization, sourceId);
        if (!configuration.enabled()) throw failure("SOURCE_DISABLED");
        SyncTask task = store().beginSync(configuration, actor, required(idempotencyKey, "idempotencyKey"),
                triggerType, required(requestId, "requestId"), clock.instant());
        if (!"queued".equals(task.status())) return task;
        try {
            List<SourceRecord> records = adapter.fetch(configuration, configuration.lastSuccessCursor());
            return store().completeSync(task, records, List.of(), clock.instant());
        } catch (RuntimeException failure) {
            SyncFailure item = new SyncFailure(1, null, normalizedError(failure), "来源同步未完成");
            return store().completeSync(task, List.of(), List.of(item), clock.instant());
        }
    }

    public List<SyncTask> tasks(String actor, String organization, String sourceId) {
        read(actor, organization, sourceId);
        return store().tasks(organization, sourceId);
    }

    public SyncTask task(String actor, String organization, String sourceId, String taskId) {
        read(actor, organization, sourceId);
        return store().task(organization, sourceId, required(taskId, "taskId"))
                .orElseThrow(() -> failure("SOURCE_TASK_NOT_FOUND"));
    }

    public List<ConfigurationHistory> history(String actor, String organization, String sourceId) {
        read(actor, organization, sourceId);
        return store().history(organization, sourceId);
    }

    public void runDueSchedules() {
        if (store == null) return;
        Instant now = clock.instant();
        for (SourceConfiguration source : store.due(now)) {
            String minute = Long.toString(now.getEpochSecond() / 60);
            try {
                synchronize("system-scheduler", source.organizationRef(), source.sourceId(),
                        "scheduled:" + source.sourceId() + ":" + minute, "SCHEDULED", "scheduled-" + minute);
            } catch (SourceManagementException ignored) {
                // A concurrent manual run or a recently changed configuration remains authoritative.
            }
        }
    }

    private Store store() {
        if (store == null) throw failure("SOURCE_UNAVAILABLE");
        return store;
    }

    private static void validate(SaveCommand command) {
        if (command == null) throw failure("INVALID_INPUT");
        required(command.sourceId(), "sourceId");
        required(command.sourceName(), "sourceName");
        required(command.dataType(), "dataType");
        required(command.credentialRef(), "credentialRef");
        required(command.ownerName(), "ownerName");
        if (!ADAPTER_TYPE.equals(command.accessMethod()) || !CONTROLLED_ENDPOINT.equals(command.controlledEndpoint())) {
            throw failure("SOURCE_ADAPTER_NOT_ALLOWED");
        }
        if (command.fieldMapping() == null || !command.fieldMapping().keySet().containsAll(REQUIRED_MAPPING_KEYS)
                || command.fieldMapping().values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw failure("FIELD_MAPPING_INVALID");
        }
        if (!List.of("MANUAL", "SCHEDULED").contains(command.scheduleMode())) throw failure("SCHEDULE_INVALID");
        if ("SCHEDULED".equals(command.scheduleMode())) {
            if (command.scheduleInterval() == null || command.scheduleInterval() < 1
                    || !List.of("MINUTES", "HOURS").contains(command.scheduleUnit())) {
                throw failure("SCHEDULE_INVALID");
            }
        } else if (command.scheduleInterval() != null || command.scheduleUnit() != null) {
            throw failure("SCHEDULE_INVALID");
        }
    }

    private static void requireContext(String actor, String organization) {
        required(actor, "actor");
        required(organization, "organization");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw failure("INVALID_INPUT");
        return value.trim();
    }

    private static String normalizedError(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z0-9_]{3,128}") ? message : "UPSTREAM_FAILURE";
    }

    public static SourceManagementException failure(String code) { return new SourceManagementException(code); }

    public interface Store {
        List<SourceConfiguration> list(String organization);
        java.util.Optional<SourceConfiguration> find(String organization, String sourceId);
        SourceConfiguration create(String organization, String actor, SaveCommand command, Instant now);
        SourceConfiguration update(String organization, String actor, SaveCommand command, Instant now);
        SourceConfiguration setEnabled(String organization, String actor, String sourceId, int expectedVersion,
                                       boolean enabled, Instant now);
        ConnectionTestResult recordConnectionTest(SourceConfiguration source, String actor, String requestId,
                                                  ConnectionCapabilities capabilities, Instant now);
        SyncTask beginSync(SourceConfiguration source, String actor, String idempotencyKey, String triggerType,
                           String requestId, Instant now);
        SyncTask completeSync(SyncTask task, List<SourceRecord> records, List<SyncFailure> failures, Instant now);
        List<SyncTask> tasks(String organization, String sourceId);
        java.util.Optional<SyncTask> task(String organization, String sourceId, String taskId);
        List<ConfigurationHistory> history(String organization, String sourceId);
        List<SourceConfiguration> due(Instant now);
    }

    public interface SourceAdapter {
        ConnectionCapabilities test(SourceConfiguration source);
        List<SourceRecord> fetch(SourceConfiguration source, String cursor);
    }

    public record SaveCommand(String sourceId, String sourceName, String dataType, String accessMethod,
                              String controlledEndpoint, String credentialRef, Map<String, String> fieldMapping,
                              String ownerName, String scheduleMode, Integer scheduleInterval, String scheduleUnit,
                              boolean enabled, Integer expectedVersion) {
        public SaveCommand { fieldMapping = fieldMapping == null ? null : Map.copyOf(fieldMapping); }
    }

    public record SourceConfiguration(String sourceId, String organizationRef, String sourceName, String dataType,
                                      String accessMethod, String controlledEndpoint, String credentialRef,
                                      Map<String, String> fieldMapping, String ownerName, String scheduleMode,
                                      Integer scheduleInterval, String scheduleUnit, boolean enabled,
                                      String healthStatus, String lastSuccessCursor, Instant lastSuccessAt,
                                      int version, String createdBy, Instant createdAt, String updatedBy,
                                      Instant updatedAt) {
        public SourceConfiguration { fieldMapping = Map.copyOf(fieldMapping); }
    }

    public record ConnectionCapabilities(String authentication, String pagination, String timeField,
                                         String uniqueKey, String responseShape, String errorCode) {}
    public record ConnectionTestResult(String testId, String sourceId, int configurationVersion, String result,
                                       ConnectionCapabilities capabilities, String testedBy, Instant testedAt,
                                       String requestId) {}
    public record SourceRecord(int sequence, String sourceRecordId, String sourceVersion, Instant sourceUpdatedAt,
                               String payloadJson, String contentSha256, String withdrawalStatus, String cursor) {}
    public record SyncFailure(int sequence, String sourceRecordRef, String errorCode, String message) {}
    public record SyncTask(String taskId, String sourceId, String organizationRef, int configurationVersion,
                           String triggerType, String status, int processedCount, int importedCount,
                           int failedCount, String startCursor, String resultCursor, String errorCode,
                           String startedBy, Instant createdAt, Instant startedAt, Instant completedAt,
                           String requestId, List<SyncFailure> failures) {
        public SyncTask { failures = failures == null ? List.of() : List.copyOf(failures); }
    }
    public record ConfigurationHistory(String historyId, String sourceId, int version, String actionType,
                                       String configurationJson, String changedBy, Instant changedAt) {}

    public static final class SourceManagementException extends RuntimeException {
        private final String code;
        public SourceManagementException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
