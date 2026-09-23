package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnforcementDiscoveryRuleMigrationTest {
    @Test void migrationContainsVersionRunResultAuditAndDevelopmentGuards() throws Exception {
        String sql = Files.readString(Path.of("db/dm8/V019__enforcement_discovery_rule_v1.sql"));
        assertThat(sql).contains("enf_discovery_rule_version", "enf_discovery_rule_condition", "enf_discovery_rule_source",
                "enf_discovery_rule_lifecycle", "enf_discovery_rule_dry_run",
                "enf_discovery_rule_condition_result", "enf_discovery_rule_audit",
                "DETERMINISTIC_V1", "DEVELOPMENT_ONLY", "SYNTHETIC_LOCAL");
        assertThat(sql).doesNotContain("enf_local_clue", "enf_evidence_ref", "enf_work_item", "notification");
    }

    @Test void ruleStoreCannotWriteClueEvidenceWorkItemOrNotificationTables() throws Exception {
        String source = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/Dm8EnforcementDiscoveryRuleStore.java"));
        assertThat(source).doesNotContain("enf_local_clue", "enf_evidence_ref", "enf_work_item", "notification",
                "permission", "official_status");
    }
}
