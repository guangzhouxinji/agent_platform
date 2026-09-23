package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.consultation;

import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchApplicationService;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.util.List;

/** Adapts one consultation turn to the persisted V0 patent-search task snapshot. */
public final class TaskBackedConsultationPatentSearchAdapter implements PatentSearchPort {
    private final PatentSearchApplicationService tasks;

    public TaskBackedConsultationPatentSearchAdapter(PatentSearchApplicationService tasks) {
        this.tasks = tasks;
    }

    @Override
    public SearchResult search(String confirmedInput) {
        throw new UnsupportedOperationException("consultation requester and idempotency key are required");
    }

    @Override
    public SearchResult search(String requester, String idempotencyKey, String confirmedInput) {
        PatentSearchTask task = tasks.createForConsultation(requester, idempotencyKey,
                new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES, confirmedInput,
                        PatentSearchCommand.TECHNICAL_REFERENCE, List.of(), PatentSearchCommand.SearchScope.empty()));
        if (task.status() == PatentSearchTask.Status.FAILED || task.status() == PatentSearchTask.Status.CANCELLED) {
            return new SearchResult(SearchResult.Type.TOOL_FAILURE, List.of(),
                    task.limitation() + "；任务错误=" + task.errorCode(), task.actualScope(), task.taskId());
        }
        List<ConsultationCandidate> candidates = task.candidates().stream()
                .map(value -> new ConsultationCandidate(value.sourceId(), value.version(), location(task, value.location()),
                        "任务快照命中：" + String.join("、", value.matchedFeatures()), evidence(value)))
                .toList();
        SearchResult.Type type = candidates.isEmpty() ? SearchResult.Type.NO_RESULT : SearchResult.Type.SUCCESS;
        return new SearchResult(type, candidates, task.limitation(), task.actualScope(), task.taskId());
    }

    private static String location(PatentSearchTask task, String value) {
        return "PUBLIC_LOCAL".equals(task.dataMode()) ? "DM8 PUBLIC_LOCAL / " + value : value;
    }

    private static String evidence(cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate value) {
        return "标题：" + value.title()
                + "\n公开（公告）号：" + value.publicationNumber()
                + "\n专利类型：" + value.patentType()
                + "\n相关程度：" + value.relevanceLabel()
                + "\n命中特征：" + String.join("、", value.matchedFeatures())
                + "\n匹配片段：" + value.matchingExcerpt()
                + "\n片段定位：" + value.location();
    }

    @Override
    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return tasks.isSourceAvailable(sourceId, sourceVersion);
    }
}
