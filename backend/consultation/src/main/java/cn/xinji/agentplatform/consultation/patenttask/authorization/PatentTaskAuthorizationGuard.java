package cn.xinji.agentplatform.consultation.patenttask.authorization;

import cn.xinji.agentplatform.consultation.patenttask.application.ProtectedTaskAction;
import cn.xinji.agentplatform.consultation.patenttask.application.TaskAuthorizationCheck;
import cn.xinji.agentplatform.consultation.patenttask.application.TaskAuthorizationDecision;

import java.util.Objects;
import java.util.Optional;

public final class PatentTaskAuthorizationGuard implements TaskAuthorizationCheck {
    private final CurrentAuthorizationContextProvider contextProvider;
    private final ProtectedResourceAuthorizationResolver resourceResolver;
    private final AuthorizationPolicy policy;

    public PatentTaskAuthorizationGuard(CurrentAuthorizationContextProvider contextProvider,
                                        ProtectedResourceAuthorizationResolver resourceResolver,
                                        AuthorizationPolicy policy) {
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.resourceResolver = resourceResolver;
        this.policy = policy;
    }

    public static PatentTaskAuthorizationGuard defaultDeny(CurrentAuthorizationContextProvider provider) {
        return new PatentTaskAuthorizationGuard(provider, null, null);
    }

    @Override
    public TaskAuthorizationDecision revalidate(ProtectedTaskAction action) {
        Optional<CurrentAuthorizationContext> current = contextProvider.current();
        if (current.isEmpty()) return TaskAuthorizationDecision.deny("IDENTITY_MISSING");
        CurrentAuthorizationContext context = current.get();
        if (!context.valid()) return TaskAuthorizationDecision.deny("AUTHORIZATION_CONTEXT_INVALID");
        if (resourceResolver == null || policy == null) return TaskAuthorizationDecision.deny("DEFAULT_DENY");
        Optional<ProtectedResourceAuthorizationRequirement> requirement = resourceResolver.resolve(action);
        if (requirement.isEmpty()) return TaskAuthorizationDecision.deny("RESOURCE_AUTHORIZATION_UNRESOLVED");
        if (!policy.permits(context, action, requirement.get())) {
            return TaskAuthorizationDecision.deny("SCOPE_OR_ACTION_DENIED");
        }
        // Request claims are intentionally ignored; only the server-provided current context is authoritative.
        return TaskAuthorizationDecision.allow(context.contextReference());
    }
}
