package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRetrievalConfidence;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatentRetrievalConfidenceTest {
    private final PatentRetrievalConfidence confidence = new PatentRetrievalConfidence();

    @Test void distinguishesEmptyLowAndSupportedRetrievalSignals() {
        assertThat(confidence.assess(List.of()).status())
                .isEqualTo(PatentRetrievalConfidence.Status.NO_RESULT);
        assertThat(confidence.assess(List.of(match(null, 1, 1.0 / 61))).status())
                .isEqualTo(PatentRetrievalConfidence.Status.LOW_CONFIDENCE);
        assertThat(confidence.assess(List.of(match(50, 50, 2.0 / 111))).reason())
                .isEqualTo("TOP_FUSION_BELOW_PREREGISTERED_THRESHOLD");
        var normal = confidence.assess(List.of(match(1, 1, 2.0 / 61)));
        assertThat(normal.status()).isEqualTo(PatentRetrievalConfidence.Status.NORMAL);
        assertThat(normal.ruleVersion()).isEqualTo("RETRIEVAL_CONFIDENCE/v1");
    }

    private static PatentHybridQuery.Match match(Integer lexicalRank, Integer semanticRank,
            double fusion) {
        return new PatentHybridQuery.Match("source", "document", "v1", "source-v1",
                "a".repeat(64), "raw.json#title", Set.of("TITLE"), List.of("battery"),
                lexicalRank == null ? null : 1.0, Map.of("TITLE", 1.0), lexicalRank,
                0.5, semanticRank, fusion);
    }
}
