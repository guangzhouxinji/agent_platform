package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies a real pairwise scorer without changing candidate membership or evidence. */
public final class PatentRerankedQuery {
    public static final String METHOD_VERSION = "HYBRID_RRF_CE/v1";

    public record Match(PatentHybridQuery.Match candidate, double rerankScore, int rerankRank) { }
    public record Result(String methodVersion, String modelVersion, List<Match> matches,
                         int candidateCount, boolean truncated) { }

    public Result rerank(String query, PatentHybridQuery.Result hybrid,
            Map<String, String> documentTexts, PatentReranker reranker, int maxResults) {
        if (query == null || query.isBlank() || hybrid == null || documentTexts == null
                || reranker == null || maxResults < 1)
            throw new IllegalArgumentException("Complete rerank input required");
        List<PatentReranker.Pair> pairs = new ArrayList<>();
        for (var candidate : hybrid.matches()) {
            String id = key(candidate);
            String text = documentTexts.get(id);
            if (text == null || text.isBlank())
                throw new IllegalStateException("Rerank candidate text missing");
            pairs.add(new PatentReranker.Pair(id, query, text));
        }
        Map<String, Double> scores = new HashMap<>();
        for (var score : reranker.score(pairs)) {
            if (!Double.isFinite(score.value()) || scores.put(score.candidateId(), score.value()) != null)
                throw new IllegalStateException("Reranker returned invalid scores");
        }
        if (scores.size() != pairs.size() || !scores.keySet().equals(documentTexts.keySet()))
            throw new IllegalStateException("Reranker score cardinality changed");
        List<PatentHybridQuery.Match> ordered = new ArrayList<>(hybrid.matches());
        ordered.sort(Comparator.<PatentHybridQuery.Match>comparingDouble(
                        candidate -> scores.get(key(candidate))).reversed()
                .thenComparing(PatentHybridQuery.Match::fusionScore, Comparator.reverseOrder())
                .thenComparing(PatentHybridQuery.Match::sourceId)
                .thenComparing(PatentHybridQuery.Match::documentId)
                .thenComparing(PatentHybridQuery.Match::documentVersion));
        List<Match> result = new ArrayList<>();
        int bound = Math.min(maxResults, ordered.size());
        for (int index = 0; index < bound; index++) {
            var candidate = ordered.get(index);
            result.add(new Match(candidate, scores.get(key(candidate)), index + 1));
        }
        return new Result(METHOD_VERSION, version(reranker.model()), List.copyOf(result),
                hybrid.candidateCount(), hybrid.candidateCount() > maxResults);
    }

    public static String key(PatentHybridQuery.Match match) {
        return match.sourceId() + '\0' + match.documentId() + '\0' + match.documentVersion();
    }

    private static String version(PatentReranker.Model model) {
        if (model == null || blank(model.provider()) || blank(model.modelId()) || blank(model.revision())
                || !sha(model.modelSha256()) || !sha(model.tokenizerSha256()) || model.maxTokens() < 1
                || blank(model.licenseId()))
            throw new IllegalArgumentException("Complete reranker model metadata required");
        return model.modelId() + "@" + model.revision();
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-fA-F]{64}"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
