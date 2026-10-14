package cn.xinji.agentplatform.consultation.application;

/** A model suggests retrieval conditions; it never executes tools or grants permissions. */
public interface ConsultationRetrievalModelPort {
    Invocation invoke(String minimizedInputJson, String traceId);
    record Invocation(String modelId, String promptVersion, String content, String rawOutput) { }
}
