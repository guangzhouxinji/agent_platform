package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class OnnxPatentEmbeddingProviderTest {
    private static final String MODEL_SHA = "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f";
    private static final String TOKENIZER_SHA = "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8";

    @Test
    @EnabledIfSystemProperty(named = "patent.embedding.model", matches = ".+")
    void pinnedModelProducesNormalizedMultilingualSemanticVectors() {
        var model = new PatentEmbeddingProvider.Model("ONNX_RUNTIME",
                "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
                "e8f8c211226b894fcb81acc59f3b34ba3efd5f42", MODEL_SHA, TOKENIZER_SHA,
                384, 128, "MEAN_POOL_L2/v1", "Apache-2.0");
        try (var provider = new OnnxPatentEmbeddingProvider(
                Path.of(System.getProperty("patent.embedding.model")),
                Path.of(System.getProperty("patent.embedding.tokenizer")), model)) {
            List<float[]> vectors = provider.embed(List.of(
                    "lithium battery energy storage", "锂电池储能系统", "protective optical coating"));
            assertThat(vectors).hasSize(3).allSatisfy(vector -> {
                assertThat(vector).hasSize(384);
                assertThat(norm(vector)).isBetween(0.9999, 1.0001);
            });
            assertThat(cosine(vectors.get(0), vectors.get(1)))
                    .isGreaterThan(cosine(vectors.get(0), vectors.get(2)));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "patent.embedding.model", matches = ".+")
    void completeRepresentationIncludesTheLongTailWithoutChangingLegacyTitleEmbedding() {
        var model = new PatentEmbeddingProvider.Model("ONNX_RUNTIME",
                "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
                "e8f8c211226b894fcb81acc59f3b34ba3efd5f42", MODEL_SHA,TOKENIZER_SHA,
                384,128,"MEAN_POOL_L2/v1","Apache-2.0");
        try (var provider = new OnnxPatentEmbeddingProvider(Path.of(System.getProperty("patent.embedding.model")),
                Path.of(System.getProperty("patent.embedding.tokenizer")),model)) {
            String shortText = "lithium battery energy storage";
            var shortVector = provider.embed(List.of(shortText)).getFirst();
            var completeShort = provider.embedComplete(List.of(shortText)).getFirst();
            assertThat(cosine(shortVector,completeShort)).isCloseTo(1.0,within(0.000001));
            String prefix = "lithium battery energy storage ".repeat(60);
            String longText = prefix + "protective optical coating film ".repeat(180);
            var oldPrefix = provider.embed(List.of(prefix)).getFirst();
            var oldLong = provider.embed(List.of(longText)).getFirst();
            assertThat(cosine(oldPrefix,oldLong)).isCloseTo(1.0,within(0.000001));
            var completeLong = provider.embedComplete(List.of(longText)).getFirst();
            var coating = provider.embedComplete(List.of("protective optical coating film")).getFirst();
            assertThat(norm(completeLong)).isBetween(0.9999,1.0001);
            assertThat(cosine(completeLong,coating)).isGreaterThan(cosine(oldLong,coating) + 0.05);
            System.out.printf(java.util.Locale.ROOT,"COMPLETE_MODEL_C shortCos=%.12f prefixLegacyCos=%.12f tailLegacyCos=%.12f tailCompleteCos=%.12f norm=%.12f%n",
                    cosine(shortVector,completeShort),cosine(oldPrefix,oldLong),cosine(oldLong,coating),cosine(completeLong,coating),norm(completeLong));
            assertThat(provider.embedComplete(List.of(longText)).getFirst()).containsExactly(completeLong);
            assertThat(provider.embed(List.of(shortText)).getFirst()).containsExactly(shortVector);
            assertThatThrownBy(() -> provider.embedComplete(List.of(" "))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static double norm(float[] vector) { return Math.sqrt(cosineRaw(vector, vector)); }
    private static double cosine(float[] left, float[] right) {
        return cosineRaw(left, right) / (norm(left) * norm(right));
    }
    private static double cosineRaw(float[] left, float[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) result += left[index] * right[index];
        return result;
    }
}
