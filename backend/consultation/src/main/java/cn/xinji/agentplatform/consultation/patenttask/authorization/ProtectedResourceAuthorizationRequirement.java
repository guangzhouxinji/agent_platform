package cn.xinji.agentplatform.consultation.patenttask.authorization;

public record ProtectedResourceAuthorizationRequirement(String resourceReference, String requiredScopeReference,
                                                         long requirementVersion) {
    public ProtectedResourceAuthorizationRequirement {
        if (resourceReference == null || resourceReference.isBlank()) {
            throw new IllegalArgumentException("resourceReference is required");
        }
        if (requiredScopeReference == null || requiredScopeReference.isBlank()) {
            throw new IllegalArgumentException("requiredScopeReference is required");
        }
        if (requirementVersion < 1) throw new IllegalArgumentException("requirementVersion must be positive");
    }
}
