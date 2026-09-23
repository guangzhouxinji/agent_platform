package cn.xinji.agentplatform.consultation.patenttask.application;

public final class TaskCommandException extends RuntimeException {
    private final Code code;
    private final Long currentVersion;
    private final TaskConflict conflict;

    public TaskCommandException(Code code, String message, Long currentVersion) {
        this(code, message, currentVersion, null);
    }

    public TaskCommandException(Code code, String message, Long currentVersion, TaskConflict conflict) {
        super(message);
        this.code = code;
        this.currentVersion = currentVersion;
        this.conflict = conflict;
    }

    public Code code() { return code; }
    public Long currentVersion() { return currentVersion; }
    public TaskConflict conflict() { return conflict; }

    public static TaskCommandException objectConflict(String taskId, long taskVersion, String objectId,
                                                       long expected, long current) {
        TaskConflict conflict = new TaskConflict(TaskConflict.Type.OBJECT_VERSION, taskId, taskVersion,
                objectId, expected, current, "task:" + taskId + "/snapshot:" + taskVersion);
        return new TaskCommandException(Code.OBJECT_VERSION_CONFLICT, "object version conflict", taskVersion, conflict);
    }

    public enum Code {
        PAYLOAD_CONFLICT,
        VERSION_CONFLICT,
        OBJECT_VERSION_CONFLICT,
        DEPENDENCY_VERSION_CONFLICT,
        ACCESS_DENIED,
        TASK_NOT_FOUND,
        TASK_ALREADY_EXISTS,
        CANCELLED_TASK,
        INVALID_COMMAND
    }
}
