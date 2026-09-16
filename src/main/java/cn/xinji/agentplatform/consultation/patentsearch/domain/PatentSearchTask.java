package cn.xinji.agentplatform.consultation.patentsearch.domain;

import java.time.Instant;
import java.util.List;

public record PatentSearchTask(
        String taskId,
        String requester,
        String idempotencyKey,
        String requestFingerprint,
        PatentSearchCommand inputSnapshot,
        Status status,
        int attempt,
        String dataMode,
        String scoringVersion,
        String actualScope,
        List<PatentSearchCandidate> candidates,
        String limitation,
        String errorCode,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {

    public PatentSearchTask {
        candidates = List.copyOf(candidates);
    }

    public static PatentSearchTask queued(String taskId, String requester, String key, String fingerprint,
                                          PatentSearchCommand snapshot, Instant now) {
        return new PatentSearchTask(taskId, requester, key, fingerprint, snapshot, Status.QUEUED, 1,
                "SYNTHETIC", "SYNTHETIC-RANK-V1", "", List.of(),
                "合成数据仅用于功能验证，不代表真实专利检索范围或效果。", "", taskId, now, now);
    }

    public PatentSearchTask processing(Instant now) { return with(Status.PROCESSING, attempt, actualScope, candidates, limitation, "", now); }
    public PatentSearchTask succeeded(String scope, List<PatentSearchCandidate> values, String resultLimitation, boolean partial, Instant now) {
        return with(partial ? Status.PARTIAL : Status.SUCCEEDED, attempt, scope, values, resultLimitation, "", now);
    }
    public PatentSearchTask failed(String code, Instant now) { return with(Status.FAILED, attempt, actualScope, List.of(), limitation, code, now); }
    public PatentSearchTask cancelled(Instant now) { return with(Status.CANCELLED, attempt, actualScope, List.of(), "任务已取消；迟到结果不会发布。", "", now); }
    public PatentSearchTask requeued(Instant now) { return with(Status.QUEUED, attempt + 1, "", List.of(), limitation, "", now); }

    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.PARTIAL || status == Status.FAILED || status == Status.CANCELLED; }

    private PatentSearchTask with(Status nextStatus, int nextAttempt, String scope, List<PatentSearchCandidate> values,
                                  String nextLimitation, String nextError, Instant now) {
        return new PatentSearchTask(taskId, requester, idempotencyKey, requestFingerprint, inputSnapshot, nextStatus,
                nextAttempt, dataMode, scoringVersion, scope, values, nextLimitation, nextError, traceId, createdAt, now);
    }

    public enum Status { QUEUED, PROCESSING, SUCCEEDED, PARTIAL, FAILED, CANCELLED }
}
