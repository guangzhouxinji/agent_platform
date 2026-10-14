package cn.xinji.agentplatform.security.product;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local pinned cross-encoder; query and admitted document text are scored as a pair. */
final class OnnxPatentReranker implements PatentReranker, AutoCloseable {
    private final Model model;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final OrtSession.SessionOptions sessionOptions;
    private final HuggingFaceTokenizer tokenizer;
    private final HuggingFaceTokenizer completeTokenizer;
    private final HuggingFaceTokenizer queryTokenizer;
    private final ProductCompleteRerankCache completeScores = new ProductCompleteRerankCache(1024);
    private boolean closed;

    OnnxPatentReranker(Path modelPath, Path tokenizerPath, Model model) {
        this(modelPath,tokenizerPath,model,ProductOnnxSessionOptions.current());
    }
    OnnxPatentReranker(Path modelPath, Path tokenizerPath, Model model,
            OrtSession.SessionOptions sessionOptions) {
        this.sessionOptions=java.util.Objects.requireNonNull(sessionOptions);
        this.model = validate(model);
        verify(modelPath, model.modelSha256());
        verify(tokenizerPath, model.tokenizerSha256());
        try {
            environment = OrtEnvironment.getEnvironment();
            session = environment.createSession(modelPath.toAbsolutePath().toString(),sessionOptions);
            tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerPath.toAbsolutePath())
                    .optAddSpecialTokens(true).optTruncation(true).optMaxLength(model.maxTokens()).build();
            completeTokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerPath.toAbsolutePath())
                    .optAddSpecialTokens(true).optTruncateSecondOnly().optWithOverflowingTokens(true)
                    .optStride(0).optMaxLength(model.maxTokens()).build();
            queryTokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerPath.toAbsolutePath())
                    .optAddSpecialTokens(true).optTruncation(false).build();
            if (!session.getInputNames().containsAll(List.of("input_ids", "attention_mask")))
                throw new IllegalStateException("Reranker input contract is unsupported");
        } catch (Exception failure) {
            throw new IllegalStateException("Patent reranker is unavailable", failure);
        }
    }

    @Override public Model model() { return model; }

    @Override public synchronized List<Score> score(List<Pair> pairs) {
        if(closed)throw new IllegalStateException("Patent reranker is closed");
        if (pairs == null || pairs.isEmpty() || pairs.stream().anyMatch(pair -> pair == null
                || blank(pair.candidateId()) || blank(pair.query()) || blank(pair.documentText())))
            throw new IllegalArgumentException("Complete reranker pairs required");
        List<Score> result = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) result.add(new Score(pair.candidateId(),
                score(pair.query(), pair.documentText())));
        return List.copyOf(result);
    }

    private double score(String query, String document) {
        Encoding encoding = tokenizer.encode(query, document);
        return infer(encoding);
    }

    @Override public synchronized List<Score> scoreComplete(List<Pair> pairs) {
        if(closed)throw new IllegalStateException("Patent reranker is closed");
        if (pairs == null || pairs.isEmpty() || pairs.stream().anyMatch(pair -> pair == null
                || blank(pair.candidateId()) || blank(pair.query()) || blank(pair.documentText())))
            throw new IllegalArgumentException("Complete reranker pairs required");
        List<Score> result = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            double value=completeScores.score(model,pair,()-> {
                long[] query = OnnxCompleteReranking.content(queryTokenizer.encode(pair.query()),0);
                // BERT pair has three special tokens; reserve at least one document token.
                if (query.length == 0 || query.length > model.maxTokens() - 4)
                    throw new IllegalStateException("Complete reranking query exceeds pair budget");
                return OnnxCompleteReranking.score(completeTokenizer.encode(pair.query(),pair.documentText()),
                        query,model.maxTokens(),this::infer);
            });
            result.add(new Score(pair.candidateId(),value));
        }
        return List.copyOf(result);
    }

    private double infer(Encoding encoding) {
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
                double result = scalar(output.get(0).getValue());
                if (!Double.isFinite(result)) throw new IllegalStateException("Reranker score is not finite");
                return result;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Patent reranker execution failed", failure);
        }
    }

    private static double scalar(Object value) {
        if (value instanceof float[][] matrix && matrix.length == 1 && matrix[0].length == 1)
            return matrix[0][0];
        if (value instanceof float[] vector && vector.length == 1) return vector[0];
        throw new IllegalStateException("Reranker output contract is unsupported");
    }
    private static Model validate(Model model) {
        if (model == null || blank(model.provider()) || blank(model.modelId()) || blank(model.revision())
                || !sha(model.modelSha256()) || !sha(model.tokenizerSha256()) || model.maxTokens() < 1
                || blank(model.licenseId()))
            throw new IllegalArgumentException("Complete versioned reranker metadata required");
        return model;
    }
    private static void verify(Path path, String expected) {
        if (path == null || !Files.isRegularFile(path)) throw new IllegalStateException("Reranker artifact missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected))
                throw new IllegalStateException("Reranker artifact digest changed");
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Reranker artifact cannot be verified", failure);
        }
    }
    @Override public synchronized void close() {
        if(closed)return;
        closed=true;completeScores.clear();
        tokenizer.close();
        completeTokenizer.close();
        queryTokenizer.close();
        try { session.close(); } catch (Exception failure) {
            throw new IllegalStateException("Reranker session could not close", failure);
        } finally { sessionOptions.close(); }
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
