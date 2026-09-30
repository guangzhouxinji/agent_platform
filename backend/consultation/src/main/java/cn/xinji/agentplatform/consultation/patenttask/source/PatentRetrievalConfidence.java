package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.List;

/** Versioned retrieval-signal assessment; this is not a legal or probability conclusion. */
public final class PatentRetrievalConfidence {
    public static final String RULE_VERSION = "RETRIEVAL_CONFIDENCE/v1";
    public static final double MIN_TOP_FUSION_SCORE = 2.0 / 110.0;
    private static final int REVIEW_WINDOW = 50;

    public enum Status { NO_RESULT, LOW_CONFIDENCE, NORMAL }
    public record Assessment(Status status, String ruleVersion, String reason) { }

    public Assessment assess(List<PatentHybridQuery.Match> matches) {
        if (matches == null) throw new IllegalArgumentException("Retrieval matches required");
        if (matches.isEmpty())
            return new Assessment(Status.NO_RESULT, RULE_VERSION, "NO_MATCHING_CANDIDATE");
        List<PatentHybridQuery.Match> window = matches.stream().limit(REVIEW_WINDOW).toList();
        boolean overlap = window.stream().anyMatch(match -> match.lexicalRank() != null
                && match.semanticRank() != null);
        if (!overlap)
            return new Assessment(Status.LOW_CONFIDENCE, RULE_VERSION,
                    "NO_LEXICAL_SEMANTIC_OVERLAP_IN_TOP_50");
        double topFusion = window.stream().mapToDouble(PatentHybridQuery.Match::fusionScore)
                .max().orElse(0);
        if (topFusion < MIN_TOP_FUSION_SCORE)
            return new Assessment(Status.LOW_CONFIDENCE, RULE_VERSION,
                    "TOP_FUSION_BELOW_PREREGISTERED_THRESHOLD");
        return new Assessment(Status.NORMAL, RULE_VERSION, "LEXICAL_SEMANTIC_SIGNAL_SUPPORTED");
    }

    public Assessment lexicalOnly(boolean empty) {
        return empty
                ? new Assessment(Status.NO_RESULT, RULE_VERSION, "NO_MATCHING_CANDIDATE")
                : new Assessment(Status.LOW_CONFIDENCE, RULE_VERSION,
                        "SEMANTIC_RETRIEVAL_NOT_EXECUTED");
    }
}
