package cn.xinji.agentplatform.consultation.patentassistant.application;

import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.util.List;

public interface PatentAnalysisPort {
    ModelStatus status();

    String analyze(String question, List<PatentAssistantSource> sources, String traceId);

    FeatureExtraction extractFeatures(String input, String purpose, String traceId);

    record ModelStatus(String provider, String model, boolean configured) {}
    record FeatureExtraction(List<String> features) {
        public FeatureExtraction { features = List.copyOf(features); }
    }
}
