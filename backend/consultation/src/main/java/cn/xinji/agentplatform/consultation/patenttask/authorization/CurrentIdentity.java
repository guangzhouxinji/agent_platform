package cn.xinji.agentplatform.consultation.patenttask.authorization;

public record CurrentIdentity(String subjectReference, boolean testIdentity) {
    public CurrentIdentity {
        if (subjectReference == null || subjectReference.isBlank()) {
            throw new IllegalArgumentException("subjectReference is required");
        }
    }

    public static CurrentIdentity fixture(String subjectReference) {
        return new CurrentIdentity(subjectReference, true);
    }
}
