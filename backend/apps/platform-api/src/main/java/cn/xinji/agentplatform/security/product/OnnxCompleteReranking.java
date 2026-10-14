package cn.xinji.agentplatform.security.product;

import ai.djl.huggingface.tokenizers.Encoding;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** All document windows are scored; query tokens must be identical and complete in every pair. */
final class OnnxCompleteReranking {
    static final int MAX_WINDOWS = 8192;

    static double score(Encoding first, long[] query, int maxTokens, ToDoubleFunction<Encoding> infer) {
        if (first == null || query == null || query.length == 0 || maxTokens < 1 || infer == null)
            throw new IllegalStateException("Complete reranking input required");
        Encoding[] overflow = first.getOverflowing();
        if (overflow == null || (first.exceedMaxLength() && overflow.length == 0)
                || overflow.length >= MAX_WINDOWS)
            throw new IllegalStateException("Complete reranking overflow or budget is invalid");
        List<Encoding> windows = new ArrayList<>(); windows.add(first); windows.addAll(Arrays.asList(overflow));
        // Validate every window before any inference; malformed tails cannot yield partial success.
        for (int i = 0; i < windows.size(); i++) {
            Encoding window = windows.get(i);
            if (window == null || window.getIds() == null || window.getIds().length > maxTokens
                    || (i > 0 && window.getOverflowing() != null && window.getOverflowing().length > 0))
                throw new IllegalStateException("Complete reranking window shape is invalid");
            if (!Arrays.equals(content(window,0),query) || content(window,1).length == 0)
                throw new IllegalStateException("Complete query or document tokens missing");
        }
        double maximum = Double.NEGATIVE_INFINITY;
        for (Encoding window : windows) {
            double value = infer.applyAsDouble(window);
            if (!Double.isFinite(value)) throw new IllegalStateException("Complete reranking logit is not finite");
            maximum = Math.max(maximum,value);
        }
        return maximum;
    }

    static long[] content(Encoding encoding, int sequence) {
        if (encoding == null || encoding.getIds() == null)
            throw new IllegalStateException("Complete reranking encoding missing");
        long[] ids = encoding.getIds(), attention = encoding.getAttentionMask(), special = encoding.getSpecialTokenMask();
        long[] types = encoding.getTypeIds();
        if (attention == null || special == null || types == null || attention.length != ids.length
                || special.length != ids.length || types.length != ids.length)
            throw new IllegalStateException("Complete reranking masks differ");
        long[] result = new long[ids.length]; int size = 0;
        for (int i = 0; i < ids.length; i++) {
            if ((attention[i] != 0 && attention[i] != 1) || (special[i] != 0 && special[i] != 1)
                    || (types[i] != 0 && types[i] != 1))
                throw new IllegalStateException("Complete reranking mask is invalid");
            if (attention[i] == 1 && special[i] == 0 && types[i] == sequence) result[size++] = ids[i];
        }
        return Arrays.copyOf(result,size);
    }
    private OnnxCompleteReranking() { }
}
