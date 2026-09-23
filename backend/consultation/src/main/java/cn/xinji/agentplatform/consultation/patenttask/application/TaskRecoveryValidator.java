package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentSubTask;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;
import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;

import java.util.Objects;

import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.*;

final class TaskRecoveryValidator {
    void validate(PatentTaskSnapshot current, PatentSubTask target, RecoveryPoint point) {
        if (!point.subTaskId().equals(target.subTaskId()) || point.failedFromTaskVersion() >= current.version()) {
            throw new TaskCommandException(INVALID_COMMAND,
                    "recovery point does not identify the failed activity", current.version());
        }
        point.dependencyVersions().forEach((key, expected) -> {
            if (!Objects.equals(current.dependencyVersions().get(key), expected)) {
                throw conflict(current, key, expected, current.dependencyVersions().get(key), true);
            }
        });
        point.contextVersionReferences().forEach((key, expected) -> {
            if (!Objects.equals(current.objectVersions().get(key), expected)) {
                throw conflict(current, key, expected, current.objectVersions().get(key), false);
            }
        });
    }

    private TaskCommandException conflict(PatentTaskSnapshot current, String key, Long expected,
                                          Long actual, boolean dependency) {
        TaskConflict.Type type = dependency
                ? TaskConflict.Type.DEPENDENCY_VERSION : TaskConflict.Type.OBJECT_VERSION;
        TaskConflict detail = new TaskConflict(type, current.taskId(), current.version(), key, expected, actual,
                "task:" + current.taskId() + "/snapshot:" + current.version());
        return new TaskCommandException(dependency ? DEPENDENCY_VERSION_CONFLICT : OBJECT_VERSION_CONFLICT,
                dependency ? "recovery dependency version conflict" : "recovery object version conflict",
                current.version(), detail);
    }
}
