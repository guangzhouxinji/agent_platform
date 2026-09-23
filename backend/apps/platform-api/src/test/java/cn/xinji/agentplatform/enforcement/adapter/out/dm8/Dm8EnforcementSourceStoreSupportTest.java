package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceRecord;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SyncFailure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class Dm8EnforcementSourceStoreSupportTest {
    private static final Instant NOW = Instant.parse("2026-09-20T06:00:00Z");

    @Test
    void advancesOnlyToTheLastRecordBeforeTheFirstFailure() {
        List<SourceRecord> records = List.of(record(1, "cursor-1"), record(2, "cursor-2"), record(4, "cursor-4"));
        List<SyncFailure> failures = List.of(new SyncFailure(3, "record-3", "PARSE_FAILED", "解析失败"));

        assertThat(Dm8EnforcementSourceStoreSupport.continuousCursor("cursor-0", records, failures))
                .isEqualTo("cursor-2");
    }

    @Test
    void advancesToTheLastRecordWhenThereIsNoFailure() {
        assertThat(Dm8EnforcementSourceStoreSupport.continuousCursor(
                "cursor-0", List.of(record(1, "cursor-1"), record(2, "cursor-2")), List.of()))
                .isEqualTo("cursor-2");
    }

    @Test
    void preservesTheStartingCursorWhenNothingCompletedContinuously() {
        assertThat(Dm8EnforcementSourceStoreSupport.continuousCursor(
                "cursor-0", List.of(record(2, "cursor-2")),
                List.of(new SyncFailure(1, "record-1", "RATE_LIMITED", "限流"))))
                .isEqualTo("cursor-0");
    }

    private static SourceRecord record(int sequence, String cursor) {
        return new SourceRecord(sequence, "record-" + sequence, "v1", NOW, "{}", "a".repeat(64),
                "AVAILABLE", cursor);
    }
}
