package cn.xinji.agentplatform.consultation.patenttask.authorization;

public record DataScope(String reference) {
    public DataScope {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference is required");
    }
}
