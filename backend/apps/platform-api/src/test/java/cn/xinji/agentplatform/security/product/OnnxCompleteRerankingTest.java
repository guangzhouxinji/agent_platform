package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.djl.huggingface.tokenizers.Encoding;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Mechanical failure/aggregation C, not patent source data or professional relevance evidence. */
class OnnxCompleteRerankingTest {
    private Encoding window(long query, long document, Encoding... overflow) {
        Encoding value = mock(Encoding.class);
        when(value.getIds()).thenReturn(new long[]{101,query,102,document,102});
        when(value.getTypeIds()).thenReturn(new long[]{0,0,0,1,1});
        when(value.getAttentionMask()).thenReturn(new long[]{1,1,1,1,1});
        when(value.getSpecialTokenMask()).thenReturn(new long[]{1,0,1,0,1});
        when(value.getOverflowing()).thenReturn(overflow);
        return value;
    }
    @Test void everyWindowIsScoredAndMaximumIsNotProbability() {
        Encoding tail = window(10,20), first = window(10,30,tail);
        AtomicInteger calls = new AtomicInteger();
        assertThat(OnnxCompleteReranking.score(first,new long[]{10},5,e -> {
            calls.incrementAndGet(); return e == tail ? 8.4 : -3.2;
        })).isEqualTo(8.4);
        assertThat(calls).hasValue(2);
    }
    @Test void changedQueryAndMalformedTailRejectBeforeInference() {
        AtomicInteger calls = new AtomicInteger();
        Encoding changed = window(11,20), first = window(10,30,changed);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> calls.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
        when(changed.getIds()).thenReturn(new long[]{1,2,3,4,5,6});
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void missingOverflowNestedOverflowAndWindowBudgetReject() {
        Encoding first = window(10,20);
        when(first.exceedMaxLength()).thenReturn(true);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
        when(first.getOverflowing()).thenReturn((Encoding[]) null);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
        when(first.getOverflowing()).thenReturn(new Encoding[OnnxCompleteReranking.MAX_WINDOWS]);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
        Encoding nested = window(10,20,window(10,30));
        assertThatThrownBy(() -> OnnxCompleteReranking.score(window(10,20,nested),new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void missingDocumentMasksNonfiniteOrFailedInferenceNeverReturnsPartialScore() {
        Encoding first = window(10,20);
        when(first.getSpecialTokenMask()).thenReturn(new long[]{1,0,1,1,1});
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
        when(first.getSpecialTokenMask()).thenReturn(new long[]{1,0,1,2,1});
        assertThatThrownBy(() -> OnnxCompleteReranking.score(first,new long[]{10},5,e -> 1))
                .isInstanceOf(IllegalStateException.class);
        Encoding tail = window(10,20), valid = window(10,30,tail);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(valid,new long[]{10},5,e -> e == tail ? Double.NaN : 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OnnxCompleteReranking.score(valid,new long[]{10},5,e -> {
            if (e == tail) throw new IllegalStateException("failed"); return 1;
        })).isInstanceOf(IllegalStateException.class);
    }
    @Test void providerWithoutCompleteCapabilityCannotFallBack() {
        AtomicInteger calls = new AtomicInteger();
        PatentReranker provider = new PatentReranker() {
            public Model model() { return null; }
            public List<Score> score(List<Pair> pairs) { calls.incrementAndGet(); return List.of(); }
        };
        assertThatThrownBy(() -> provider.scoreComplete(List.of(new PatentReranker.Pair("id","q","d"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
    }
}
