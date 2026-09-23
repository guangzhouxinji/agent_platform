package cn.xinji.agentplatform.consultation.trademarksearch.domain;

import java.time.Instant;
import java.util.List;

public record TrademarkSearchTask(String taskId, String requester, String idempotencyKey, String requestFingerprint, TrademarkSearchCommand inputSnapshot, Status status, int attempt, String dataMode, String rankingVersion, String actualScope, int totalCandidates, boolean truncated, List<TrademarkSearchCandidate> candidates, String limitation, String errorCode, String traceId, Instant createdAt, Instant updatedAt) {
    public TrademarkSearchTask { candidates = List.copyOf(candidates); }
    public static TrademarkSearchTask queued(String id, String requester, String key, String fingerprint, TrademarkSearchCommand command, Instant now) { return queued(id, requester, key, fingerprint, command, "SYNTHETIC", "SYNTHETIC-TRADEMARK-RANK-V1", "合成商标数据仅用于功能验证，不代表真实商标库、近似结论或注册成功率。", now); }
    public static TrademarkSearchTask queued(String id, String requester, String key, String fingerprint, TrademarkSearchCommand command, String dataMode, String rankingVersion, String limitation, Instant now) { return new TrademarkSearchTask(id, requester, key, fingerprint, command, Status.QUEUED, 1, dataMode, rankingVersion, "", 0, false, List.of(), limitation, "", id, now, now); }
    public TrademarkSearchTask processing(Instant now) { return with(Status.PROCESSING, attempt, actualScope, totalCandidates, truncated, candidates, limitation, "", now); }
    public TrademarkSearchTask succeeded(String scope, int total, boolean cut, List<TrademarkSearchCandidate> values, String note, boolean partial, Instant now) { return with(partial ? Status.PARTIAL : Status.SUCCEEDED, attempt, scope, total, cut, values, note, "", now); }
    public TrademarkSearchTask failed(String code, Instant now) { return with(Status.FAILED, attempt, actualScope, 0, false, List.of(), limitation, code, now); }
    public TrademarkSearchTask cancelled(Instant now) { return with(Status.CANCELLED, attempt, actualScope, 0, false, List.of(), "任务已取消；迟到结果不会发布。", "", now); }
    public TrademarkSearchTask requeued(Instant now) { return with(Status.QUEUED, attempt + 1, "", 0, false, List.of(), limitation, "", now); }
    public boolean terminal() { return status == Status.SUCCEEDED || status == Status.PARTIAL || status == Status.FAILED || status == Status.CANCELLED; }
    private TrademarkSearchTask with(Status next, int nextAttempt, String scope, int total, boolean cut, List<TrademarkSearchCandidate> values, String note, String error, Instant now) { return new TrademarkSearchTask(taskId, requester, idempotencyKey, requestFingerprint, inputSnapshot, next, nextAttempt, dataMode, rankingVersion, scope, total, cut, values, note, error, traceId, createdAt, now); }
    public enum Status { QUEUED, PROCESSING, SUCCEEDED, PARTIAL, FAILED, CANCELLED }
}
