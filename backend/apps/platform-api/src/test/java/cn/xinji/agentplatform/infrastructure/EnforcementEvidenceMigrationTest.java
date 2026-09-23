package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnforcementEvidenceMigrationTest {
    @Test void migrationKeepsImmutableContentVersionsStatusHistoryAndAccessAudit() throws Exception {
        String sql = Files.readString(Path.of("db/dm8/V022__enforcement_evidence_management_v1.sql"));
        assertThat(sql).contains("enf_evidence_record", "enf_evidence_content_version", "content_bytes BLOB",
                "content_sha256", "original_content_version", "enf_evidence_metadata_history",
                "enf_evidence_status_event", "enf_evidence_access_log", "PENDING_VERIFICATION",
                "NOT").doesNotContain("DELETE FROM", "is_legal", "is_authentic", "evidence_accepted");
    }
}
