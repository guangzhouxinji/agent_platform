package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentSubTask;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;

@FunctionalInterface
public interface TaskCompletionCheck {
    boolean deterministicConditionsSatisfied(
            PatentTaskSnapshot current, PatentSubTask subTask, SubTaskChange requestedChange);
}
