package cn.xinji.agentplatform.adjudication.adapter.out.synthetic;

import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort;

public final class V0AdjudicationAuthorizationAdapter implements AuthorizationPort {
    @Override public boolean mayAccess(String actor, String action, String resource) { return actor.equals("v0-adjudication-user") || actor.equals("v0-adjudication-reviewer"); }
    @Override public boolean isTrustedHuman(String actor) { return actor.equals("v0-adjudication-reviewer"); }
}
