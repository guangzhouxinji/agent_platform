package cn.xinji.agentplatform.consultation.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import org.springframework.stereotype.Component;

@Component
public final class V0AuthorizationAdapter implements ConsultationAuthorizationPort {
    @Override public boolean maySearch(String requesterRef) { return "v0-authorized-user".equals(requesterRef); }
}
