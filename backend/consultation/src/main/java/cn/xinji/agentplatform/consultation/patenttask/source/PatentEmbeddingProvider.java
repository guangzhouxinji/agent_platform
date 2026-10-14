package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.List;

/** Versioned semantic representation supplied by an external model adapter. */
public interface PatentEmbeddingProvider {
    String COMPLETE_REPRESENTATION_VERSION = "ALL_TOKEN_WINDOWS_WEIGHTED_MEAN_L2/v1";
    record Model(String provider, String modelId, String modelRevision, String modelSha256,
                 String tokenizerSha256, int dimension, int maxTokens,
                 String normalization, String licenseId) { }

    Model model();

    List<float[]> embed(List<String> texts);

    /** Complete representation is explicit: an unsupported provider must never silently truncate. */
    default List<float[]> embedComplete(List<String> texts) {
        throw new IllegalStateException("Complete semantic representation is unavailable");
    }
}
