package cn.xinji.agentplatform.consultation.taskcore;

public final class DevTaskCoreFailure extends RuntimeException {
    private final int status;
    private final String code;

    public DevTaskCoreFailure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static DevTaskCoreFailure invalid() { return new DevTaskCoreFailure(422, "INVALID_INPUT"); }
    public static DevTaskCoreFailure denied() { return new DevTaskCoreFailure(403, "ACCESS_DENIED"); }
    public static DevTaskCoreFailure absent() { return new DevTaskCoreFailure(404, "TASK_NOT_FOUND"); }
    public static DevTaskCoreFailure expired() { return new DevTaskCoreFailure(410, "TASK_EXPIRED"); }
    public static DevTaskCoreFailure conflict(String code) { return new DevTaskCoreFailure(409, code); }
}
