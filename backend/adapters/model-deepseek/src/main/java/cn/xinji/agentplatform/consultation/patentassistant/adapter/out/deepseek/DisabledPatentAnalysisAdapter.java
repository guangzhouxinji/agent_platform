package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.deepseek;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantService.ModelUnavailableException;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.util.List;

public final class DisabledPatentAnalysisAdapter implements PatentAnalysisPort {
    private final String model;

    public DisabledPatentAnalysisAdapter(String model) { this.model = model; }

    @Override public ModelStatus status() { return new ModelStatus("deepseek", model, false); }
    @Override public String analyze(String question, List<PatentAssistantSource> sources, String traceId) {
        throw new ModelUnavailableException("MODEL_NOT_CONFIGURED");
    }
    @Override public FeatureExtraction extractFeatures(String input, String purpose, String traceId) {
        throw new ModelUnavailableException("MODEL_NOT_CONFIGURED");
    }
}
