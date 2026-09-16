package cn.xinji.agentplatform.enforcement.adapter.out.synthetic;

import cn.xinji.agentplatform.enforcement.application.EnforcementAuthorizationPort;
import java.util.HashSet;
import java.util.Set;

public final class V0EnforcementAuthorizationAdapter implements EnforcementAuthorizationPort {
    private final Set<String> deniedResources = new HashSet<>();

    public V0EnforcementAuthorizationAdapter() {
        deniedResources.add("V0-SYN-EVD-005");
    }

    @Override
    public boolean mayAccess(String actorRef, String action, String resourceId) {
        return ("v0-enforcement-user".equals(actorRef) || "v0-enforcement-reviewer".equals(actorRef))
                && !deniedResources.contains(resourceId);
    }

    @Override
    public boolean isTrustedHuman(String actorRef) {
        return "v0-enforcement-reviewer".equals(actorRef);
    }

    public void deny(String resourceId) { deniedResources.add(resourceId); }
    public void allow(String resourceId) { deniedResources.remove(resourceId); }
}
