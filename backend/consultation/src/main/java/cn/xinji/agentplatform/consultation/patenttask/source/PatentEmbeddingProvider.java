package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.List;

/** Versioned semantic representation supplied by an external model adapter. */
public interface PatentEmbeddingProvider {
    record Model(String provider, String modelId, String modelRevision, String modelSha256,
                 String tokenizerSha256, int dimension, int maxTokens,
                 String normalization, String licenseId) { }

    Model model();

    List<float[]> embed(List<String> texts);
}
