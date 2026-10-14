package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.Collection;
import java.util.List;

/** Model-bound query abstention, not a per-document or legal probability threshold. */
public final class PatentQueryAbstention {
    public static final String RULE_VERSION = "CE_SEMANTIC_TITLE_ABSTENTION/v2";
    public static final double MINIMUM_SUPPORTED_PEAK = -3.923138;
    public static final double MINIMUM_SEMANTIC_PEAK = 0.533658;
    public static final String CALIBRATION_SHA =
            "b5c2b9d137dbada626771decc7d171df2d0b8518d5959c8c1ec3e73a63b07381";

    public boolean abstains(PatentReranker.Model model, List<PatentHybridQuery.Match> candidates,
            Collection<Double> scores) {
        return abstains(model, null, candidates, scores);
    }

    public boolean abstains(PatentReranker.Model model, PatentEmbeddingProvider.Model embedding,
            List<PatentHybridQuery.Match> candidates, Collection<Double> scores) {
        if (candidates == null || scores == null || scores.size() != candidates.size()
                || scores.stream().anyMatch(s -> s == null || !Double.isFinite(s)))
            throw new IllegalArgumentException("Complete finite query support required");
        if (candidates.isEmpty() || !calibrated(model) || !calibrated(embedding)
                || candidates.stream().anyMatch(c -> c.lexicalRank() != null)) return false;
        if (candidates.stream().anyMatch(c -> c.semanticRank() == null
                || !Double.isFinite(c.semanticScore())))
            throw new IllegalArgumentException("Complete finite semantic support required");
        return scores.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                < MINIMUM_SUPPORTED_PEAK
                && candidates.stream().mapToDouble(PatentHybridQuery.Match::semanticScore)
                        .max().orElseThrow() < MINIMUM_SEMANTIC_PEAK;
    }

    private boolean calibrated(PatentEmbeddingProvider.Model model) {
        return model != null && model.dimension() == 384 && model.maxTokens() == 128
                && "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2".equals(model.modelId())
                && "e8f8c211226b894fcb81acc59f3b34ba3efd5f42".equals(model.modelRevision())
                && "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f"
                        .equalsIgnoreCase(model.modelSha256())
                && "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8"
                        .equalsIgnoreCase(model.tokenizerSha256())
                && "MEAN_POOL_L2/v1".equals(model.normalization())
                && "Apache-2.0".equals(model.licenseId());
    }

    private boolean calibrated(PatentReranker.Model model) {
        return model != null && model.maxTokens() == 512
                && "cross-encoder/ms-marco-MiniLM-L-6-v2".equals(model.modelId())
                && "233902d25c440f23af6f7d6e94d2946bac0bee0a".equals(model.revision())
                && "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9"
                        .equalsIgnoreCase(model.modelSha256())
                && "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"
                        .equalsIgnoreCase(model.tokenizerSha256())
                && "Apache-2.0".equals(model.licenseId());
    }
}
