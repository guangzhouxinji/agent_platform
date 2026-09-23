package cn.xinji.agentplatform.consultation.casesearch.domain;

import java.time.Instant;
import java.util.List;

public record CaseSearchTask(
        String taskId,
        String requester,
        String idempotencyKey,
        String requestFingerprint,
        CaseSearchCommand inputSnapshot,
        Status status,
        String dataMode,
        String sourceVersion,
        String rankingVersion,
        String actualScope,
        int totalCandidates,
        List<CaseSearchCandidate> candidates,
        String limitation,
        String traceId,
        Instant createdAt) {

    public CaseSearchTask { candidates = List.copyOf(candidates); }

    public enum Status { SUCCEEDED, NO_RESULT, FAILED }
}
