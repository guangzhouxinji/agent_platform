package cn.xinji.agentplatform.consultation.patentsearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.time.Instant;
import java.util.List;

public record PatentSearchTaskResponse(
        String taskId,
        String status,
        int attempt,
        String dataMode,
        String scoringVersion,
        PatentSearchCommand inputSnapshot,
        String actualScope,
        List<PatentSearchCandidate> candidates,
        String limitation,
        String errorCode,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {

    static PatentSearchTaskResponse from(PatentSearchTask task) {
        return new PatentSearchTaskResponse(task.taskId(), task.status().name(), task.attempt(), task.dataMode(),
                task.scoringVersion(), task.inputSnapshot(), task.actualScope(), task.candidates(), task.limitation(),
                task.errorCode(), task.traceId(), task.createdAt(), task.updatedAt());
    }
}
