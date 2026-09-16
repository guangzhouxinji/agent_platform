package cn.xinji.agentplatform.consultation.trademarksearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import java.time.Instant;
import java.util.List;

public record TrademarkSearchTaskResponse(
        String taskId,
        String status,
        int attempt,
        String dataMode,
        String rankingVersion,
        TrademarkSearchCommand inputSnapshot,
        String actualScope,
        int totalCandidates,
        boolean truncated,
        List<TrademarkSearchCandidate> candidates,
        String limitation,
        String errorCode,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {

    static TrademarkSearchTaskResponse from(TrademarkSearchTask task) {
        return new TrademarkSearchTaskResponse(task.taskId(), task.status().name(), task.attempt(), task.dataMode(),
                task.rankingVersion(), task.inputSnapshot(), task.actualScope(), task.totalCandidates(), task.truncated(),
                task.candidates(), task.limitation(), task.errorCode(), task.traceId(), task.createdAt(), task.updatedAt());
    }
}
