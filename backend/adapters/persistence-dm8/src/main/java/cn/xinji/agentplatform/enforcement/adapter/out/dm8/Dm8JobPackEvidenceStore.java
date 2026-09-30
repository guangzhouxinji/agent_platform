package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Evidence;

import cn.xinji.agentplatform.enforcement.jobpack.JobPackEvidenceStore;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;

public final class Dm8JobPackEvidenceStore implements JobPackEvidenceStore {
    private final JdbcTemplate db;
    private final Clock clock;
    public Dm8JobPackEvidenceStore(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

    @Override public void register(String packageId, Evidence evidence, String actor) {
        db.update("INSERT INTO enf8_job_pack_file(file_key,package_id,evidence_ref,sha256,original_name,uploaded_by,created_at) VALUES(?,?,?,?,?,?,?)",
                evidence.fileKey(), packageId, evidence.ref(), evidence.sha256(), evidence.originalName(), actor,
                Timestamp.from(clock.instant()));
    }

    @Override public boolean contains(String packageId, Evidence evidence) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_file WHERE package_id=? AND file_key=? AND evidence_ref=? AND sha256=?",
                Integer.class, packageId, evidence.fileKey(), evidence.ref(), evidence.sha256());
        return count != null && count == 1;
    }

    @Override public java.util.Optional<Evidence> find(String packageId, String evidenceRef) {
        return db.query("SELECT evidence_ref,sha256,file_key,original_name FROM enf8_job_pack_file WHERE package_id=? AND evidence_ref=?",
                (rs, row) -> new Evidence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                packageId, evidenceRef).stream().findFirst();
    }
}
