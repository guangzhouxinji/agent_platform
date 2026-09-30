package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;

public interface DevCurrentWorkStateStore {
    record ResultRef(String setId, long inputRevision, boolean currentInput) { }
    record FocusRef(String setId, int ordinal, long inputRevision, boolean currentInput) { }
    record FeatureRef(long inputRevision, String manifestId, boolean currentInput) { }
    record State(String taskId, long taskRevision, String taskState, long resultSequence,
                 long focusSequence, long featureRevision, ResultRef resultSet, FocusRef focus,
                 FeatureRef feature) { }

    State read(Actor human, String taskId, Guard guard);
}
