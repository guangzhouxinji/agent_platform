package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.*;

/** Complete-window field reranking; TITLE calibration is deliberately not reused for this method. */
public final class PatentFieldRerankedQuery {
    public static final String METHOD_VERSION = "HYBRID_FIELD_RRF_COMPLETE_CE/v1";
    public PatentRerankedQuery.Result rerank(String query, PatentHybridQuery.Result hybrid,
            Map<String,String> texts, PatentReranker reranker, int maxResults) {
        if (query == null || query.isBlank() || hybrid == null
                || !"HYBRID_FIELD_RRF/v1".equals(hybrid.methodVersion()) || texts == null
                || reranker == null || maxResults < 1)
            throw new IllegalArgumentException("Complete field rerank input required");
        var model = reranker.model();
        String version = modelVersion(model);
        List<PatentReranker.Pair> pairs = new ArrayList<>(); Set<String> identities = new HashSet<>();
        for (var candidate : hybrid.matches()) {
            String id = PatentRerankedQuery.key(candidate); String text = texts.get(id);
            if (candidate.matchFields().isEmpty() || text == null || text.isBlank() || !identities.add(id))
                throw new IllegalStateException("Exact field candidate and complete text required");
            pairs.add(new PatentReranker.Pair(id,query,text));
        }
        if (!identities.equals(texts.keySet())) throw new IllegalStateException("Field rerank text cardinality differs");
        Map<String,Double> scores = new HashMap<>();
        if (!pairs.isEmpty()) {
            List<PatentReranker.Score> output = reranker.scoreComplete(List.copyOf(pairs));
            if (output == null) throw new IllegalStateException("Complete rerank response missing");
            for (var score : output) {
                if (score == null || !Double.isFinite(score.value()) || !identities.contains(score.candidateId())
                        || scores.put(score.candidateId(),score.value()) != null)
                    throw new IllegalStateException("Complete field rerank score is invalid");
            }
        }
        if (!scores.keySet().equals(identities) || !model.equals(reranker.model()))
            throw new IllegalStateException("Complete field rerank scores or model changed");
        List<PatentHybridQuery.Match> ordered = new ArrayList<>(hybrid.matches());
        ordered.sort(Comparator.<PatentHybridQuery.Match>comparingDouble(value -> scores.get(PatentRerankedQuery.key(value)))
                .reversed().thenComparing(PatentHybridQuery.Match::fusionScore,Comparator.reverseOrder())
                .thenComparing(PatentHybridQuery.Match::sourceId).thenComparing(PatentHybridQuery.Match::documentId)
                .thenComparing(PatentHybridQuery.Match::documentVersion));
        List<PatentRerankedQuery.Match> members = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults,ordered.size()); i++) {
            var candidate = ordered.get(i);
            members.add(new PatentRerankedQuery.Match(candidate,scores.get(PatentRerankedQuery.key(candidate)),i+1));
        }
        return new PatentRerankedQuery.Result(METHOD_VERSION,version,List.copyOf(members),hybrid.candidateCount(),
                hybrid.truncated() || hybrid.candidateCount()>maxResults);
    }
    public static String modelVersion(PatentReranker.Model model) {
        return PatentRerankedQuery.version(model) + "/" + PatentReranker.COMPLETE_SCORING
                + "/W" + model.maxTokens() + "/M" + model.modelSha256() + "/T" + model.tokenizerSha256();
    }
}
