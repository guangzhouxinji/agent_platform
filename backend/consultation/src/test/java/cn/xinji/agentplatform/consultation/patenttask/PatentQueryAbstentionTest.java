package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Numeric port-contract examples, not patent records or product validation identities. */
class PatentQueryAbstentionTest {
    private final PatentQueryAbstention rule = new PatentQueryAbstention();
    private final PatentReranker.Model model = new PatentReranker.Model("ONNX_RUNTIME",
            "cross-encoder/ms-marco-MiniLM-L-6-v2", "233902d25c440f23af6f7d6e94d2946bac0bee0a",
            "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9",
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
            512, "Apache-2.0");
    private final PatentEmbeddingProvider.Model embedding = new PatentEmbeddingProvider.Model("ONNX_RUNTIME",
            "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
            "e8f8c211226b894fcb81acc59f3b34ba3efd5f42",
            "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f",
            "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8",
            384, 128, "MEAN_POOL_L2/v1", "Apache-2.0");

    @Test void requiresWholeQueryWeakSupportWithoutLexicalEvidence() {
        assertThat(rule.abstains(model, embedding, List.of(candidate(null)), List.of(-11.0))).isTrue();
        assertThat(rule.abstains(model, List.of(candidate(null)), List.of(-11.0))).isFalse();
        assertThat(rule.abstains(model, List.of(candidate(1)), List.of(-11.0))).isFalse();
        assertThat(rule.abstains(model, List.of(candidate(null)), List.of(-1.0))).isFalse();
        assertThat(rule.abstains(model, List.of(candidate(null)),
                List.of(PatentQueryAbstention.MINIMUM_SUPPORTED_PEAK))).isFalse();
        assertThat(rule.abstains(model, List.of(candidate(null), candidate(null)),
                List.of(-11.0, 1.0))).isFalse();
    }

    @Test void neverTransfersCalibrationToOtherModelOrTokenizer() {
        var other = new PatentReranker.Model(model.provider(), model.modelId(), "other-revision",
                model.modelSha256(), model.tokenizerSha256(), 512, model.licenseId());
        assertThat(rule.abstains(other, embedding, List.of(candidate(null)), List.of(-11.0))).isFalse();
        var tokenizer = new PatentReranker.Model(model.provider(), model.modelId(), model.revision(),
                model.modelSha256(), "a".repeat(64), 512, model.licenseId());
        assertThat(rule.abstains(tokenizer, embedding, List.of(candidate(null)), List.of(-11.0))).isFalse();
        assertThat(rule.abstains(null, embedding, List.of(candidate(null)), List.of(-11.0))).isFalse();
        var unknown = new PatentEmbeddingProvider.Model(embedding.provider(), embedding.modelId(),
                "unknown", embedding.modelSha256(), embedding.tokenizerSha256(),
                384, 128, embedding.normalization(), embedding.licenseId());
        assertThat(rule.abstains(model, unknown, List.of(candidate(null)), List.of(-11.0))).isFalse();
    }

    @Test void semanticEvidenceProtectsSynonymsAndStrictBoundary() {
        for (double support : new double[]{0.58210722001722810,
                PatentQueryAbstention.MINIMUM_SEMANTIC_PEAK, .9})
            assertThat(rule.abstains(model, embedding, List.of(candidate(null, support)),
                    List.of(-10.000466346740723))).isFalse();
        assertThat(rule.abstains(model, embedding, List.of(candidate(null)),
                List.of(PatentQueryAbstention.MINIMUM_SUPPORTED_PEAK))).isFalse();
        assertThat(rule.abstains(model, embedding, List.of(candidate(1)), List.of(-11.0))).isFalse();
        assertThat(rule.abstains(model, embedding, List.of(candidate(null)), List.of(-1.0))).isFalse();
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThatThrownBy(() -> rule.abstains(model, embedding,
                    List.of(candidate(null, invalid)), List.of(-11.0)))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingAndNonFiniteScoresAreNotEvidenceForEmptyResults() {
        assertThatThrownBy(() -> rule.abstains(model, List.of(candidate(null)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThatThrownBy(() -> rule.abstains(model, List.of(candidate(null)), List.of(invalid)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(rule.abstains(model, List.of(), List.of())).isFalse();
    }

    private static PatentHybridQuery.Match candidate(Integer lexicalRank) {
        return candidate(lexicalRank, .2);
    }
    private static PatentHybridQuery.Match candidate(Integer lexicalRank, double support) {
        return new PatentHybridQuery.Match("port", "numeric-case", "1", "1", "a".repeat(64),
                "title", Set.of("TITLE"), List.of(), null, Map.of(), lexicalRank, support, 1, .02);
    }
}
