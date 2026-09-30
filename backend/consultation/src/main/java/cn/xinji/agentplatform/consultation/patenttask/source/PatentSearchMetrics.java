package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standard deterministic retrieval metrics over versioned relevance judgments. */
public final class PatentSearchMetrics {
    public record Scores(double recall, double precision, double ndcg, double reciprocalRank) { }

    public Scores evaluate(List<String> rankedDocumentIds, Map<String, Integer> relevance, int k) {
        if (rankedDocumentIds == null || relevance == null || relevance.isEmpty() || k < 1
                || relevance.values().stream().anyMatch(grade -> grade == null || grade < 1))
            throw new IllegalArgumentException("Ranked results, positive qrels and K required");
        int limit = Math.min(k, rankedDocumentIds.size());
        Set<String> seen = new HashSet<>();
        int relevantFound = 0;
        double dcg = 0;
        double reciprocalRank = 0;
        for (int index = 0; index < limit; index++) {
            String documentId = rankedDocumentIds.get(index);
            if (documentId == null || documentId.isBlank() || !seen.add(documentId))
                throw new IllegalArgumentException("Ranked document identities must be unique");
            int grade = relevance.getOrDefault(documentId, 0);
            if (grade > 0) {
                relevantFound++;
                if (reciprocalRank == 0) reciprocalRank = 1.0 / (index + 1);
            }
            dcg += gain(grade) / log2(index + 2);
        }
        List<Integer> ideal = relevance.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        double idcg = 0;
        for (int index = 0; index < Math.min(k, ideal.size()); index++)
            idcg += gain(ideal.get(index)) / log2(index + 2);
        return new Scores((double) relevantFound / relevance.size(), (double) relevantFound / k,
                idcg == 0 ? 0 : dcg / idcg, reciprocalRank);
    }

    private static double gain(int grade) { return Math.pow(2, grade) - 1; }
    private static double log2(int value) { return Math.log(value) / Math.log(2); }
}
