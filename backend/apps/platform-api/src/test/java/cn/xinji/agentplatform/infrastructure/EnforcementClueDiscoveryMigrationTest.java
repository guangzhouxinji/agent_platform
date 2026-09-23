package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnforcementClueDiscoveryMigrationTest {
    @Test void migrationContainsFrozenInputsLeadsEvidenceHistoryAndDevelopmentGuards() throws Exception {
        String sql = Files.readString(Path.of("db/dm8/V020__enforcement_clue_discovery_v1.sql"));
        assertThat(sql).contains("enf_clue_discovery_task", "enf_clue_discovery_input_sync", "enf_clue_discovery_input_snapshot",
                "enf_clue_discovery_input_rule", "enf_clue_discovery_item", "enf_discovered_lead",
                "enf_discovered_lead_match", "enf_discovered_lead_condition",
                "enf_discovered_lead_status_history", "enf_discovered_lead_duplicate",
                "DEVELOPMENT_ONLY", "PENDING_VERIFICATION", "NOT_AN_INFRINGEMENT_FINDING");
        assertThat(sql).doesNotContain("model_score", "model_version", "notification", "official_status");
    }

    @Test void storeCannotWriteEvidenceWorkItemsNotificationsPermissionsOrOfficialStates() throws Exception {
        String source = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/Dm8EnforcementClueDiscoveryStore.java"));
        assertThat(source).doesNotContain("enf_evidence_ref", "enf_work_item", "notification_event",
                "permission_grant", "official_status", "model_score", "model_version");
    }
}
