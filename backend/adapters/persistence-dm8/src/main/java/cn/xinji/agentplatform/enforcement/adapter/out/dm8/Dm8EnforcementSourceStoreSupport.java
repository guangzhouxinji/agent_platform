package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceManagementException;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SourceRecord;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.SyncFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

final class Dm8EnforcementSourceStoreSupport {
    private Dm8EnforcementSourceStoreSupport() {}

    static boolean snapshotExists(Connection connection, String sourceId, String recordId, String version) throws Exception {
        String sql = "SELECT 1 FROM enf_source_record_snapshot WHERE source_id=? AND source_record_id=? AND source_version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, sourceId, recordId, version);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    static String continuousCursor(String start, List<SourceRecord> records, List<SyncFailure> failures) {
        int firstFailure = failures.stream().mapToInt(SyncFailure::sequence).min().orElse(Integer.MAX_VALUE);
        return records.stream().filter(record -> record.sequence() < firstFailure)
                .max(Comparator.comparingInt(SourceRecord::sequence)).map(SourceRecord::cursor).orElse(start);
    }

    static Duration duration(Integer interval, String unit) {
        return "HOURS".equals(unit) ? Duration.ofHours(interval) : Duration.ofMinutes(interval);
    }

    static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    static int execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    static void bind(PreparedStatement statement, Object... values) throws Exception {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }

    static boolean isConstraint(Exception failure) {
        return failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23");
    }

    static SourceManagementException unavailable(Exception failure) {
        SourceManagementException result = EnforcementSourceManagementService.failure("SOURCE_UNAVAILABLE");
        result.initCause(failure);
        return result;
    }
}
