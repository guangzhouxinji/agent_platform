package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRerankedQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatentRerankedQueryTest {
    @Test void ordersAllCandidatesByRealPairScoresAndRetainsEvidence() {
        var first = match("a", .03);
        var second = match("b", .02);
        var hybrid = new PatentHybridQuery.Result("HYBRID_RRF/v1", "battery",
                List.of(first, second), 2, false);
        PatentReranker scorer = scorer(List.of(
                new PatentReranker.Score(PatentRerankedQuery.key(first), -1.2),
                new PatentReranker.Score(PatentRerankedQuery.key(second), 3.4)));
        var result = new PatentRerankedQuery().rerank("battery", hybrid, Map.of(
                PatentRerankedQuery.key(first), "battery holder",
                PatentRerankedQuery.key(second), "lithium battery"), scorer, 2);
        assertThat(result.methodVersion()).isEqualTo(PatentRerankedQuery.METHOD_VERSION);
        assertThat(result.matches()).extracting(item -> item.candidate().documentId())
                .containsExactly("b", "a");
        assertThat(result.matches()).extracting(PatentRerankedQuery.Match::rerankRank)
                .containsExactly(1, 2);
        assertThat(result.matches().getFirst().candidate()).isEqualTo(second);
    }

    @Test void rejectsMissingOrPartialScores() {
        var candidate = match("a", .03);
        var hybrid = new PatentHybridQuery.Result("HYBRID_RRF/v1", "battery",
                List.of(candidate), 1, false);
        assertThatThrownBy(() -> new PatentRerankedQuery().rerank("battery", hybrid,
                Map.of(PatentRerankedQuery.key(candidate), "battery"), scorer(List.of()), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PatentHybridQuery.Match match(String id, double fusion) {
        return new PatentHybridQuery.Match("source", id, "v1", "source-v1", "a".repeat(64),
                "raw#" + id, Set.of("TITLE"), List.of("battery"), 1.0, Map.of("TITLE", 1.0),
                1, .5, 1, fusion);
    }
    private static PatentReranker scorer(List<PatentReranker.Score> scores) {
        return new PatentReranker() {
            @Override public Model model() {
                return new Model("ONNX_RUNTIME", "cross-encoder/model", "revision",
                        "a".repeat(64), "b".repeat(64), 512, "Apache-2.0");
            }
            @Override public List<Score> score(List<Pair> pairs) { return scores; }
        };
    }
}
