package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.ResultFocusAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentCorrectionAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentEvidenceAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentRetrievalAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentComparisonAction;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentHumanAction;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Constrained business intentions; callers never submit a replacement task snapshot. */
public record TaskChange(
        List<SubTaskChange> subTaskChanges,
        Map<String, Long> objectVersionUpdates,
        Map<String, Long> dependencyVersionUpdates,
        ResultFocusAction resultFocusAction,
        PatentCorrectionAction correctionAction,
        PatentEvidenceAction evidenceAction,
        PatentRetrievalAction retrievalAction,
        PatentComparisonAction comparisonAction,
        PatentHumanAction humanAction) {
    public TaskChange {
        subTaskChanges = List.copyOf(Objects.requireNonNull(subTaskChanges));
        objectVersionUpdates = Map.copyOf(Objects.requireNonNull(objectVersionUpdates));
        dependencyVersionUpdates = Map.copyOf(Objects.requireNonNull(dependencyVersionUpdates));
        long distinct = subTaskChanges.stream().map(SubTaskChange::subTaskId).distinct().count();
        if (distinct != subTaskChanges.size()) {
            throw new IllegalArgumentException("a command may change each subtask at most once");
        }
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, null, null, null, null, null, null);
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates, ResultFocusAction resultFocusAction) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, resultFocusAction, null, null, null,
                null, null);
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates, ResultFocusAction resultFocusAction,
                      PatentCorrectionAction correctionAction) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, resultFocusAction,
                correctionAction, null, null, null, null);
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates, ResultFocusAction resultFocusAction,
                      PatentCorrectionAction correctionAction, PatentEvidenceAction evidenceAction) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, resultFocusAction,
                correctionAction, evidenceAction, null, null, null);
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates, ResultFocusAction resultFocusAction,
                      PatentCorrectionAction correctionAction, PatentEvidenceAction evidenceAction,
                      PatentRetrievalAction retrievalAction) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, resultFocusAction,
                correctionAction, evidenceAction, retrievalAction, null, null);
    }

    public TaskChange(List<SubTaskChange> subTaskChanges, Map<String, Long> objectVersionUpdates,
                      Map<String, Long> dependencyVersionUpdates, ResultFocusAction resultFocusAction,
                      PatentCorrectionAction correctionAction, PatentEvidenceAction evidenceAction,
                      PatentRetrievalAction retrievalAction, PatentComparisonAction comparisonAction) {
        this(subTaskChanges, objectVersionUpdates, dependencyVersionUpdates, resultFocusAction,
                correctionAction, evidenceAction, retrievalAction, comparisonAction, null);
    }
}
