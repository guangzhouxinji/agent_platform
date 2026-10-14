package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mechanical aggregation C; these vectors are not a patent source or model evaluation. */
class OnnxCompleteEmbeddingTest {
    @Test void weightsEveryContentWindowAndRenormalizes() {
        var vector = OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(new float[]{1,0},3),
                new OnnxCompleteEmbedding.Window(new float[]{0,1},4)),2);
        assertThat(vector[0]).isCloseTo(0.6f,within(0.00001f));
        assertThat(vector[1]).isCloseTo(0.8f,within(0.00001f));
        assertThat(OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(new float[]{1,0},50)),2))
                .containsExactly(1,0);
    }
    @Test void missingEmptyZeroWeightAndBudgetExceededCannotProducePartialVector() {
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(List.of(),2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(null,2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(new float[]{1,0},0)),2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(Collections.nCopies(OnnxCompleteEmbedding.MAX_WINDOWS + 1,
                new OnnxCompleteEmbedding.Window(new float[]{1,0},1)),2)).isInstanceOf(IllegalStateException.class);
    }
    @Test void badDimensionNonfiniteUnnormalizedAndCancellingValuesReject() {
        for (float[] invalid : List.of(new float[]{1},new float[]{Float.NaN,0},new float[]{2,0}))
            assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(invalid,1)),2))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(new float[]{1,0},1),
                new OnnxCompleteEmbedding.Window(new float[]{-1,0},1)),2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteEmbedding.mean(List.of(new OnnxCompleteEmbedding.Window(new float[]{1,0},Long.MAX_VALUE),
                new OnnxCompleteEmbedding.Window(new float[]{1,0},1)),2)).isInstanceOf(IllegalStateException.class);
    }
}
