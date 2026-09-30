package cn.xinji.agentplatform.security.product;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local, pinned ONNX sentence embedding adapter. Missing or changed artifacts fail startup. */
final class OnnxPatentEmbeddingProvider implements PatentEmbeddingProvider, AutoCloseable {
    private final Model model;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    OnnxPatentEmbeddingProvider(Path modelPath, Path tokenizerPath, Model model) {
        this.model = validate(model);
        verify(modelPath, model.modelSha256());
        verify(tokenizerPath, model.tokenizerSha256());
        try {
            environment = OrtEnvironment.getEnvironment();
            session = environment.createSession(modelPath.toAbsolutePath().toString());
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath.toAbsolutePath())
                    .optAddSpecialTokens(true).optTruncation(true)
                    .optMaxLength(model.maxTokens()).build();
            if (!session.getInputNames().containsAll(List.of("input_ids", "attention_mask")))
                throw new IllegalStateException("Embedding model input contract is unsupported");
        } catch (Exception failure) {
            throw new IllegalStateException("Semantic embedding model is unavailable", failure);
        }
    }

    @Override public Model model() { return model; }

    @Override public synchronized List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty() || texts.stream().anyMatch(text -> text == null || text.isBlank()))
            throw new IllegalArgumentException("Non-empty embedding texts required");
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) vectors.add(embedOne(text));
        return List.copyOf(vectors);
    }

    private float[] embedOne(String text) {
        Encoding encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] types = encoding.getTypeIds();
        try (OnnxTensor inputIds = OnnxTensor.createTensor(environment, new long[][] {ids});
             OnnxTensor attention = OnnxTensor.createTensor(environment, new long[][] {mask});
             OnnxTensor tokenTypes = OnnxTensor.createTensor(environment, new long[][] {types})) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", inputIds);
            inputs.put("attention_mask", attention);
            if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", tokenTypes);
            try (OrtSession.Result output = session.run(inputs)) {
                Object value = output.get(0).getValue();
                if (!(value instanceof float[][][] hidden) || hidden.length != 1
                        || hidden[0].length != mask.length)
                    throw new IllegalStateException("Embedding model output contract is unsupported");
                return meanPool(hidden[0], mask, model.dimension());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Semantic embedding execution failed", failure);
        }
    }

    private static float[] meanPool(float[][] tokens, long[] mask, int dimension) {
        if (tokens.length == 0 || tokens[0].length != dimension)
            throw new IllegalStateException("Embedding dimension changed");
        float[] result = new float[dimension];
        int count = 0;
        for (int token = 0; token < tokens.length; token++) {
            if (mask[token] == 0) continue;
            count++;
            for (int index = 0; index < dimension; index++) result[index] += tokens[token][index];
        }
        if (count == 0) throw new IllegalStateException("Embedding has no unmasked tokens");
        double norm = 0;
        for (int index = 0; index < dimension; index++) {
            result[index] /= count;
            norm += result[index] * result[index];
        }
        norm = Math.sqrt(norm);
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalStateException("Embedding is not normalizable");
        for (int index = 0; index < dimension; index++) result[index] /= (float) norm;
        return result;
    }

    private static Model validate(Model model) {
        if (model == null || blank(model.provider()) || blank(model.modelId())
                || blank(model.modelRevision()) || !sha(model.modelSha256())
                || !sha(model.tokenizerSha256()) || model.dimension() < 1 || model.maxTokens() < 1
                || !"MEAN_POOL_L2/v1".equals(model.normalization()) || blank(model.licenseId()))
            throw new IllegalArgumentException("Complete versioned embedding model metadata required");
        return model;
    }

    private static void verify(Path path, String expectedSha) {
        if (path == null || !Files.isRegularFile(path))
            throw new IllegalStateException("Embedding artifact is missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha))
                throw new IllegalStateException("Embedding artifact digest changed");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Embedding artifact cannot be verified", failure);
        }
    }

    @Override public void close() {
        tokenizer.close();
        try { session.close(); } catch (Exception failure) {
            throw new IllegalStateException("Embedding session could not close", failure);
        }
    }

    private static boolean sha(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
