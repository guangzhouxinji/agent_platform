package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;

import java.util.Map;
import java.util.Objects;

import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.*;

final class TaskCommandValidator {
    PatentTaskCommandRepository.StoredCommand findReplayOrConflict(
            PatentTaskCommandRepository.Transaction tx, TaskCommand command, String payloadFingerprint) {
        PatentTaskCommandRepository.StoredCommand byIdentity = tx
                .findByCommandIdentity(command.taskId(), command.commandIdentity()).orElse(null);
        PatentTaskCommandRepository.StoredCommand byKey = tx
                .findByIdempotencyKey(command.taskId(), command.idempotencyKey()).orElse(null);
        if (byIdentity != null && !sameCommand(byIdentity, command, payloadFingerprint)) {
            throw payloadConflict(command, byIdentity, "command identity was used with a different payload");
        }
        if (byKey != null && !sameCommand(byKey, command, payloadFingerprint)) {
            throw payloadConflict(command, byKey, "idempotency key was used with a different command or payload");
        }
        if (byIdentity != null && byKey != null
                && !byIdentity.commandIdentity().equals(byKey.commandIdentity())) {
            throw payloadConflict(command, byIdentity,
                    "command identity and idempotency key resolve to different commands");
        }
        return byIdentity != null ? byIdentity : byKey;
    }

    void validateVersions(TaskCommand command, PatentTaskSnapshot current) {
        if (command.type() == TaskCommandType.CREATE) {
            if (current != null) throw failure(TASK_ALREADY_EXISTS, "task already exists", current.version());
            return;
        }
        if (current == null) throw failure(TASK_NOT_FOUND, "task not found", null);
        if (current.version() != command.expectedTaskVersion()) {
            TaskConflict conflict = new TaskConflict(TaskConflict.Type.TASK_VERSION, command.taskId(),
                    current.version(), command.taskId(), command.expectedTaskVersion(), current.version(),
                    "task:" + command.taskId() + "/snapshot:" + current.version());
            throw new TaskCommandException(VERSION_CONFLICT, "task version is stale", current.version(), conflict);
        }
        validateVersionMap(command.referencedObjectVersions(), current.objectVersions(), OBJECT_VERSION_CONFLICT,
                command.taskId(), current.version());
        validateVersionMap(command.dependencyVersions(), current.dependencyVersions(), DEPENDENCY_VERSION_CONFLICT,
                command.taskId(), current.version());
    }

    private boolean sameCommand(PatentTaskCommandRepository.StoredCommand stored, TaskCommand command,
                                String payloadFingerprint) {
        return stored.commandIdentity().equals(command.commandIdentity())
                && stored.idempotencyKey().equals(command.idempotencyKey())
                && stored.payloadFingerprint().equals(payloadFingerprint);
    }

    private void validateVersionMap(Map<String, Long> expected, Map<String, Long> current,
                                    TaskCommandException.Code code, String taskId, long currentTaskVersion) {
        expected.forEach((key, version) -> {
            if (!Objects.equals(current.get(key), version)) {
                Long actual = current.get(key);
                TaskConflict.Type type = code == OBJECT_VERSION_CONFLICT
                        ? TaskConflict.Type.OBJECT_VERSION : TaskConflict.Type.DEPENDENCY_VERSION;
                TaskConflict conflict = new TaskConflict(type, taskId, currentTaskVersion,
                        key, version, actual, "task:" + taskId + "/snapshot:" + currentTaskVersion);
                throw new TaskCommandException(code, "version conflict for " + key, currentTaskVersion, conflict);
            }
        });
    }

    private TaskCommandException payloadConflict(TaskCommand command,
                                                  PatentTaskCommandRepository.StoredCommand stored,
                                                  String message) {
        TaskConflict conflict = new TaskConflict(TaskConflict.Type.PAYLOAD, command.taskId(),
                stored.result().version(), command.commandIdentity(), null, null,
                "task:" + command.taskId() + "/snapshot:" + stored.result().version());
        return new TaskCommandException(PAYLOAD_CONFLICT, message, stored.result().version(), conflict);
    }

    private TaskCommandException failure(TaskCommandException.Code code, String message, Long version) {
        return new TaskCommandException(code, message, version);
    }
}
