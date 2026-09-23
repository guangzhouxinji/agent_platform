package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnforcementClueParsingMigrationTest {
    @Test void migrationContainsVersionsSourcesHumanChangesReviewsAndHardBoundaryChecks() throws Exception {
        String sql = Files.readString(Path.of("db/dm8/V021__enforcement_clue_parsing_v1.sql"));
        assertThat(sql).contains("enf_clue_parse_task", "enf_clue_parse_input", "enf_clue_parse_result",
                "enf_clue_parse_field", "enf_clue_parse_issue", "enf_clue_parse_change",
                "enf_clue_parse_review", "enf_clue_parse_audit", "NO_MODEL_USED",
                "TO_BE_EVALUATED", "FACTORS_NOT_CONFIRMED", "PENDING_CONNECTION",
                "source_ref", "source_version", "source_sha256", "ck_enf_clue_parse_source_trace");
        assertThat(sql).doesNotContain("LEGAL_PROBABILITY", "INFRINGEMENT_CONFIRMED", "official_status");
    }

    @Test void parsingStoreCannotWriteEvidenceWorkItemsNotificationsPermissionsOrOfficialStates() throws Exception {
        String source = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/Dm8EnforcementClueParsingStore.java"));
        assertThat(source).doesNotContain("enf_evidence_ref", "enf_work_item", "notification_event",
                "permission_grant", "official_status", "RestClient", "WebClient", "HttpClient", "OpenAI");
        assertThat(source).contains("WHERE l.organization_ref=? AND l.lead_id=?",
                "PENDING_VERIFICATION", "VERIFYING", "SOURCE_WITHDRAWN", "FROZEN_INPUT_CHANGED",
                "enf_discovered_lead_match", "rule_sha256", "source_availability");
    }
}
