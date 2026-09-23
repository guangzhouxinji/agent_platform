package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnforcementSourceManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T06:00:00Z");
    private static final Map<String, String> MAPPING = Map.of(
            "sourceRecordId", "id", "sourceVersion", "version", "sourceUpdatedAt", "updatedAt", "content", "content");

    @Test
    void rejectsArbitraryEndpointsBeforePersistence() {
        Store store = mock(Store.class);
        EnforcementSourceManagementService service = service(store, mock(SourceAdapter.class));
        SaveCommand command = new SaveCommand("source-1", "测试来源", "COMPLAINT_REPORT",
                "HTTP_API", "https://example.invalid", "credential-ref", MAPPING, "owner",
                "MANUAL", null, null, true, null);

        assertThatThrownBy(() -> service.save("actor", "org-a", command))
                .isInstanceOf(SourceManagementException.class)
                .hasMessage("SOURCE_ADAPTER_NOT_ALLOWED");
        verifyNoInteractions(store);
    }

    @Test
    void synchronizesOnlyTheFixedAdapterAndReturnsThePersistedTerminalTask() {
        Store store = mock(Store.class);
        SourceAdapter adapter = mock(SourceAdapter.class);
        SourceConfiguration source = source("org-a", true);
        SyncTask queued = task("queued", List.of());
        SyncTask completed = task("succeeded", List.of());
        SourceRecord record = new SourceRecord(1, "record-1", "v1", NOW, "{}", "a".repeat(64), "AVAILABLE", "1");
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(source));
        when(store.beginSync(eq(source), eq("actor"), eq("sync-key"), eq("MANUAL"), eq("request-1"), eq(NOW)))
                .thenReturn(queued);
        when(adapter.fetch(source, null)).thenReturn(List.of(record));
        when(store.completeSync(queued, List.of(record), List.of(), NOW)).thenReturn(completed);

        SyncTask result = service(store, adapter).synchronize("actor", "org-a", "source-1",
                "sync-key", "MANUAL", "request-1");

        assertThat(result.status()).isEqualTo("succeeded");
        verify(store).completeSync(queued, List.of(record), List.of(), NOW);
    }

    @Test
    void recordsAdapterFailureAsARealFailedTaskInsteadOfAnEmptySuccess() {
        Store store = mock(Store.class);
        SourceAdapter adapter = mock(SourceAdapter.class);
        SourceConfiguration source = source("org-a", true);
        SyncTask queued = task("queued", List.of());
        SyncTask failed = task("failed", List.of(new SyncFailure(1, null, "UPSTREAM_FAILURE", "来源同步未完成")));
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(source));
        when(store.beginSync(any(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(queued);
        when(adapter.fetch(source, null)).thenThrow(new IllegalStateException("UPSTREAM_FAILURE"));
        when(store.completeSync(eq(queued), eq(List.of()), argThat(items -> items.size() == 1
                && "UPSTREAM_FAILURE".equals(items.getFirst().errorCode())), eq(NOW))).thenReturn(failed);

        SyncTask result = service(store, adapter).synchronize("actor", "org-a", "source-1",
                "sync-key", "MANUAL", "request-1");

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failures()).extracting(SyncFailure::errorCode).containsExactly("UPSTREAM_FAILURE");
    }

    @Test
    void usesTheTrustedOrganizationForEveryRead() {
        Store store = mock(Store.class);
        when(store.find("org-b", "source-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(store, mock(SourceAdapter.class)).read("actor", "org-b", "source-1"))
                .isInstanceOf(SourceManagementException.class).hasMessage("SOURCE_NOT_FOUND");
        verify(store).find("org-b", "source-1");
    }

    @Test
    void routesCreateUpdateAndEnableChangesWithTrustedContextAndVersion() {
        Store store = mock(Store.class);
        SaveCommand create = command(null, "MANUAL", null, null);
        SaveCommand update = command(3, "MANUAL", null, null);
        SourceConfiguration source = source("org-a", true);
        when(store.create("org-a", "actor", create, NOW)).thenReturn(source);
        when(store.update("org-a", "actor", update, NOW)).thenReturn(source);
        when(store.setEnabled("org-a", "actor", "source-1", 3, false, NOW)).thenReturn(source("org-a", false));

        EnforcementSourceManagementService service = service(store, mock(SourceAdapter.class));
        service.save("actor", "org-a", create);
        service.save("actor", "org-a", update);
        SourceConfiguration disabled = service.setEnabled("actor", "org-a", "source-1", 3, false);

        assertThat(disabled.enabled()).isFalse();
        verify(store).create("org-a", "actor", create, NOW);
        verify(store).update("org-a", "actor", update, NOW);
        verify(store).setEnabled("org-a", "actor", "source-1", 3, false, NOW);
    }

    @Test
    void rejectsInvalidSchedulesBeforePersistence() {
        Store store = mock(Store.class);
        EnforcementSourceManagementService service = service(store, mock(SourceAdapter.class));

        assertThatThrownBy(() -> service.save("actor", "org-a", command(null, "SCHEDULED", 0, "MINUTES")))
                .isInstanceOf(SourceManagementException.class).hasMessage("SCHEDULE_INVALID");
        assertThatThrownBy(() -> service.save("actor", "org-a", command(null, "MANUAL", 1, "HOURS")))
                .isInstanceOf(SourceManagementException.class).hasMessage("SCHEDULE_INVALID");
        verifyNoInteractions(store);
    }

    @Test
    void recordsConnectionFailuresWithoutReportingSuccess() {
        Store store = mock(Store.class);
        SourceAdapter adapter = mock(SourceAdapter.class);
        SourceConfiguration source = source("org-a", true);
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(source));
        when(adapter.test(source)).thenThrow(new IllegalStateException("AUTHENTICATION_FAILED"));
        when(store.recordConnectionTest(eq(source), eq("actor"), eq("request-1"), any(), eq(NOW)))
                .thenAnswer(invocation -> {
                    ConnectionCapabilities capabilities = invocation.getArgument(3);
                    return new ConnectionTestResult("test-1", "source-1", 1, "FAILED", capabilities,
                            "actor", NOW, "request-1");
                });

        ConnectionTestResult result = service(store, adapter)
                .testConnection("actor", "org-a", "source-1", "request-1");

        assertThat(result.result()).isEqualTo("FAILED");
        assertThat(result.capabilities().errorCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(result.capabilities().responseShape()).isEqualTo("FAILED");
    }

    @Test
    void blocksDisabledSourcesAndDoesNotStartATask() {
        Store store = mock(Store.class);
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(source("org-a", false)));

        assertThatThrownBy(() -> service(store, mock(SourceAdapter.class)).synchronize(
                "actor", "org-a", "source-1", "sync-key", "MANUAL", "request-1"))
                .isInstanceOf(SourceManagementException.class).hasMessage("SOURCE_DISABLED");
        verify(store, never()).beginSync(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void returnsAnIdempotentTerminalTaskWithoutFetchingAgain() {
        Store store = mock(Store.class);
        SourceAdapter adapter = mock(SourceAdapter.class);
        SourceConfiguration source = source("org-a", true);
        SyncTask completed = task("succeeded", List.of());
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(source));
        when(store.beginSync(source, "actor", "sync-key", "MANUAL", "request-1", NOW)).thenReturn(completed);

        assertThat(service(store, adapter).synchronize(
                "actor", "org-a", "source-1", "sync-key", "MANUAL", "request-1")).isSameAs(completed);
        verifyNoInteractions(adapter);
        verify(store, never()).completeSync(any(), anyList(), anyList(), any());
    }

    @Test
    void runsEachDueScheduleWithAStableMinuteKeyAndContinuesAfterAConflict() {
        Store store = mock(Store.class);
        SourceAdapter adapter = mock(SourceAdapter.class);
        SourceConfiguration first = source("org-a", true);
        SourceConfiguration second = new SourceConfiguration("source-2", "org-b", "第二来源", "COMPLAINT_REPORT",
                EnforcementSourceManagementService.ADAPTER_TYPE, EnforcementSourceManagementService.CONTROLLED_ENDPOINT,
                "credential-ref", MAPPING, "owner", "SCHEDULED", 5, "MINUTES", true, "HEALTHY",
                null, null, 1, "actor", NOW, "actor", NOW);
        when(store.due(NOW)).thenReturn(List.of(first, second));
        when(store.find("org-a", "source-1")).thenReturn(Optional.of(first));
        when(store.find("org-b", "source-2")).thenReturn(Optional.of(second));
        when(store.beginSync(eq(first), anyString(), anyString(), eq("SCHEDULED"), anyString(), eq(NOW)))
                .thenThrow(EnforcementSourceManagementService.failure("SOURCE_SYNC_IN_PROGRESS"));
        SyncTask queued = new SyncTask("task-2", "source-2", "org-b", 1, "SCHEDULED", "queued",
                0, 0, 0, null, null, null, "system-scheduler", NOW, NOW, null, "scheduled", List.of());
        when(store.beginSync(eq(second), anyString(), anyString(), eq("SCHEDULED"), anyString(), eq(NOW))).thenReturn(queued);
        when(adapter.fetch(second, null)).thenReturn(List.of());
        when(store.completeSync(queued, List.of(), List.of(), NOW)).thenReturn(new SyncTask(
                "task-2", "source-2", "org-b", 1, "SCHEDULED", "succeeded", 0, 0, 0,
                null, null, null, "system-scheduler", NOW, NOW, NOW, "scheduled", List.of()));

        service(store, adapter).runDueSchedules();

        String minute = Long.toString(NOW.getEpochSecond() / 60);
        verify(store).beginSync(second, "system-scheduler", "scheduled:source-2:" + minute,
                "SCHEDULED", "scheduled-" + minute, NOW);
        verify(store).completeSync(queued, List.of(), List.of(), NOW);
    }

    private static EnforcementSourceManagementService service(Store store, SourceAdapter adapter) {
        return new EnforcementSourceManagementService(store, adapter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SourceConfiguration source(String organization, boolean enabled) {
        return new SourceConfiguration("source-1", organization, "测试来源", "COMPLAINT_REPORT",
                EnforcementSourceManagementService.ADAPTER_TYPE, EnforcementSourceManagementService.CONTROLLED_ENDPOINT,
                "credential-ref", MAPPING, "owner", "MANUAL", null, null, enabled, "NOT_TESTED",
                null, null, 1, "actor", NOW, "actor", NOW);
    }

    private static SaveCommand command(Integer expectedVersion, String scheduleMode,
                                       Integer scheduleInterval, String scheduleUnit) {
        return new SaveCommand("source-1", "测试来源", "COMPLAINT_REPORT",
                EnforcementSourceManagementService.ADAPTER_TYPE, EnforcementSourceManagementService.CONTROLLED_ENDPOINT,
                "credential-ref", MAPPING, "owner", scheduleMode, scheduleInterval, scheduleUnit, true,
                expectedVersion);
    }

    private static SyncTask task(String status, List<SyncFailure> failures) {
        return new SyncTask("task-1", "source-1", "org-a", 1, "MANUAL", status,
                status.equals("succeeded") ? 1 : failures.size(), status.equals("succeeded") ? 1 : 0,
                failures.size(), null, status.equals("succeeded") ? "1" : null,
                failures.isEmpty() ? null : failures.getFirst().errorCode(), "actor", NOW, NOW,
                status.equals("queued") ? null : NOW, "request-1", failures);
    }
}
