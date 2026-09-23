package cn.xinji.agentplatform.consultation.agentconfig.application;

import java.util.List;

public interface ConsultationAgentModelRegistry {
    List<RegisteredModel> models();

    record RegisteredModel(
            String modelId,
            String provider,
            String modelName,
            String displayName,
            boolean enabled,
            boolean configured,
            String dataChannel,
            String availability) {}
}
