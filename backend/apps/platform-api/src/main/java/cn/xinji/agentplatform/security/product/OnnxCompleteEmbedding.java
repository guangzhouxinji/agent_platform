package cn.xinji.agentplatform.security.product;

import ai.djl.huggingface.tokenizers.Encoding;
import java.util.*;
import java.util.function.Function;

/** Complete, nonoverlapping tokenizer windows; no partial vector can become a successful field index. */
final class OnnxCompleteEmbedding {
    static final int MAX_WINDOWS = 8192;
    record Window(float[] vector, long contentTokens) { }

    static float[] embed(Encoding encoding, int dimension, int maxTokens, Function<Encoding,float[]> infer) {
        if (encoding == null || dimension < 1 || maxTokens < 1)
            throw new IllegalStateException("Complete token encoding required");
        Encoding[] overflow = encoding.getOverflowing();
        if (overflow == null || (encoding.exceedMaxLength() && overflow.length == 0))
            throw new IllegalStateException("Complete token overflow is unavailable");
        if (overflow.length >= MAX_WINDOWS) throw new IllegalStateException("Complete embedding exceeds window budget");
        List<Encoding> encodings = new ArrayList<>(); encodings.add(encoding); encodings.addAll(Arrays.asList(overflow));
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < encodings.size(); i++) {
            Encoding current = encodings.get(i);
            if (current == null || current.getIds().length > maxTokens
                    || (i > 0 && current.getOverflowing() != null && current.getOverflowing().length > 0))
                throw new IllegalStateException("Complete window shape is unsupported");
            long[] attention = current.getAttentionMask(), special = current.getSpecialTokenMask();
            if (attention.length != current.getIds().length || special.length != attention.length)
                throw new IllegalStateException("Complete window mask differs");
            long count = 0;
            for (int token = 0; token < attention.length; token++) {
                if ((attention[token] != 0 && attention[token] != 1) || (special[token] != 0 && special[token] != 1))
                    throw new IllegalStateException("Complete window mask is invalid");
                if (attention[token] == 1 && special[token] == 0) count++;
            }
            if (count == 0) throw new IllegalStateException("Complete window has no content tokens");
            windows.add(new Window(infer.apply(current),count));
        }
        return mean(windows,dimension);
    }

    static float[] mean(List<Window> windows, int dimension) {
        if (windows == null || windows.isEmpty() || windows.size() > MAX_WINDOWS || dimension < 1)
            throw new IllegalStateException("Complete embedding window budget is invalid");
        double[] sum = new double[dimension]; long tokens = 0;
        for (var window : windows) {
            if (window == null || window.contentTokens() < 1) throw new IllegalStateException("Complete token weight required");
            try { ProductVectorCodec.encode(window.vector(),dimension); tokens = Math.addExact(tokens,window.contentTokens()); }
            catch (IllegalArgumentException | ArithmeticException invalid) {
                throw new IllegalStateException("Complete window vector or token weight is invalid",invalid);
            }
            for (int i = 0; i < dimension; i++) sum[i] += window.vector()[i] * (double) window.contentTokens();
        }
        double norm = 0;
        for (int i = 0; i < dimension; i++) { sum[i] /= tokens; norm += sum[i] * sum[i]; }
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalStateException("Complete embedding cannot be normalized");
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) result[i] = (float) (sum[i] / norm);
        ProductVectorCodec.encode(result,dimension);
        return result;
    }
    private OnnxCompleteEmbedding() { }
}
