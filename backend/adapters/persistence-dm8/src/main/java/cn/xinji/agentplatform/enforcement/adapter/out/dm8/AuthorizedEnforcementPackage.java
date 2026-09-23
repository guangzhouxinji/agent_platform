package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.ClueData;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AuthorizedEnforcementPackage(
        Manifest manifest,
        String manifestJson,
        String manifestSha256,
        String packageSha256,
        List<ClueData> clues,
        List<EvidenceData> evidence,
        List<RuleRecord> rules,
        List<RightStatusRecord> rightStatuses) {

    public int recordCount() {
        return clues.size() + evidence.size() + rules.size() + rightStatuses.size();
    }

    public record Manifest(
            String schemaVersion,
            String batchId,
            String datasetId,
            String datasetVersion,
            String sourceName,
            String sourceType,
            String approvalDecisionId,
            String approvedBy,
            Instant approvedAt,
            String allowedUse,
            String recordScope,
            String retentionPolicy,
            Instant retentionUntil,
            String withdrawalContact,
            String withdrawalProcess,
            boolean externalModelAllowed,
            Map<String, FileDeclaration> files) {}

    public record FileDeclaration(String sha256, int recordCount) {}

    public record RuleRecord(
            SourceLocator source,
            String status,
            String approvedBy,
            Instant approvedAt,
            Instant effectiveFrom,
            String effectivityStatus,
            String effectivityBasis,
            String applicability,
            String ruleType,
            String content,
            String contentSha256) {}

    public record RightStatusRecord(SourceLocator source, String clueId, String availability) {}
}
