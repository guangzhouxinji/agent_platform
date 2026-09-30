package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;

public interface DevPurposeSelectionStore {
    record Selection(String taskId, long taskRevision, long purposeSequence, String analysisPurpose,
                     long featureRevision, long focusSequence, String setId, int ordinal,
                     String actor, String receiptId, boolean currentInput) { }

    @FunctionalInterface
    interface PreviewCheck {
        DevComparisonInputPreviewStore.Preview read(long featureRevision, long focusSequence);
    }

    Selection select(Actor human, String taskId, String key, long expectedTaskRevision,
                     long featureRevision, long focusSequence, String setId, int ordinal,
                     String analysisPurpose, Guard guard, PreviewCheck preview);

    Selection current(Actor human, String taskId, Guard guard, PreviewCheck preview);

    Selection read(Actor human, String taskId, long purposeSequence, Guard guard, PreviewCheck preview);
}
