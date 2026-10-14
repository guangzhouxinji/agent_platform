package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Exact, bounded reuse of real complete inference; never owns source access or candidate retrieval. */
final class ProductCompleteRerankCache {
    static final String VERSION = "ONNX_COMPLETE_SCORE_SHA256_LRU1024/v1";
    private final int capacity;
    private final LinkedHashMap<String,Double> scores = new LinkedHashMap<>(16,0.75f,true);
    ProductCompleteRerankCache(int capacity) {
        if(capacity<1 || capacity>1024)throw new IllegalArgumentException("Bounded complete score cache required");
        this.capacity=capacity;
    }
    synchronized double score(PatentReranker.Model model,PatentReranker.Pair pair,DoubleSupplier inference) {
        if(model==null || pair==null || inference==null || pair.query()==null || pair.query().isBlank()
                || pair.documentText()==null || pair.documentText().isBlank())
            throw new IllegalArgumentException("Exact complete model input required");
        String key=key(model,pair);
        Double existing=scores.get(key);
        if(existing!=null)return existing;
        double actual=inference.getAsDouble();
        if(!Double.isFinite(actual))throw new IllegalStateException("Complete inference score is not finite");
        scores.put(key,actual);
        if(scores.size()>capacity)scores.remove(scores.keySet().iterator().next());
        return actual;
    }
    private static String key(PatentReranker.Model model,PatentReranker.Pair pair) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(PatentReranker.COMPLETE_SCORING,model.provider(),model.modelId(),model.revision(),
                    model.modelSha256(),model.tokenizerSha256(),Integer.toString(model.maxTokens()),model.licenseId(),
                    pair.query(),pair.documentText())) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Complete scoring digest unavailable",impossible);
        }
    }
    synchronized void clear() { scores.clear(); }
}
