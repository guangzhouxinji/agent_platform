package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.material.MaterialRepository.SyncRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

final class Dm8MaterialSyncRecords {
    private final JdbcTemplate db;

    Dm8MaterialSyncRecords(JdbcTemplate db) { this.db = db; }

    void record(SyncRecord record) {
        db.update("INSERT INTO enf3_sync_record(attempt_id,ordinal_no,record_id,source_version,"
                        + "before_cursor,next_cursor,state,failure_code) VALUES(?,?,?,?,?,?,?,?)",
                record.attemptId(), record.ordinal(), record.recordId(), record.sourceVersion(),
                record.beforeCursor(), record.nextCursor(), record.state(), record.failureCode());
    }

    List<SyncRecord> read(String attemptId, String sourceId) {
        return db.query("SELECT r.attempt_id,r.ordinal_no,r.record_id,r.source_version,r.before_cursor,"
                        + "r.next_cursor,r.state,r.failure_code FROM enf3_sync_record r "
                        + "JOIN enf3_sync_attempt a ON a.attempt_id=r.attempt_id "
                        + "WHERE r.attempt_id=? AND a.source_id=? ORDER BY r.ordinal_no",
                (rs, row) -> new SyncRecord(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8)), attemptId, sourceId);
    }

    int successful(String attemptId) {
        return db.queryForObject("SELECT COUNT(*) FROM enf3_sync_record WHERE attempt_id=? "
                + "AND state='COMMITTED'", Integer.class, attemptId);
    }
}
