package cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory;

import cn.xinji.agentplatform.consultation.patenttask.application.ProtectedTaskAction;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProtectedResourceAuthorizationRequirement;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProtectedResourceAuthorizationResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Fixture-only server-side resource authorization facts. */
public final class FixtureProtectedResourceAuthorizationResolver
        implements ProtectedResourceAuthorizationResolver {
    private final Map<String, ProtectedResourceAuthorizationRequirement> requirements = new HashMap<>();

    public synchronized void bind(String taskId, String requiredScopeReference, long version) {
        requirements.put(taskId, new ProtectedResourceAuthorizationRequirement(
                "fixture-task:" + taskId, requiredScopeReference, version));
    }

    @Override public synchronized Optional<ProtectedResourceAuthorizationRequirement> resolve(
            ProtectedTaskAction action) {
        return Optional.ofNullable(requirements.get(action.taskId()));
    }
}
