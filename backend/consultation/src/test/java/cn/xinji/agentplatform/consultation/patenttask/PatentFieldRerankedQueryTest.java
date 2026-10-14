package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mechanical provider C; no source admission, actual model quality or production identity is asserted. */
class PatentFieldRerankedQueryTest {
    private final PatentFieldRerankedQuery ranking = new PatentFieldRerankedQuery();
    private PatentReranker.Model model() {
        return new PatentReranker.Model("mechanical-C","model","revision","a".repeat(64),"b".repeat(64),512,"mechanical-C");
    }
    private PatentHybridQuery.Match candidate(String id) {
        return new PatentHybridQuery.Match("source",id,"v1","capture-v1","a".repeat(64),"raw/"+id+"#claims",
                Set.of("CLAIMS"),List.of(),null,Map.of(),null,0.01,1,0.02);
    }
    private PatentHybridQuery.Result hybrid() {
        return new PatentHybridQuery.Result("HYBRID_FIELD_RRF/v1","query",List.of(candidate("a"),candidate("b")),2,false);
    }
    private Map<String,String> texts() {
        return Map.of(PatentRerankedQuery.key(candidate("a")),"CLAIMS\nmechanical first full text",
                PatentRerankedQuery.key(candidate("b")),"CLAIMS\nmechanical second full text");
    }
    private PatentReranker provider(java.util.function.Function<List<PatentReranker.Pair>,List<PatentReranker.Score>> complete) {
        return new PatentReranker() {
            public Model model() { return PatentFieldRerankedQueryTest.this.model(); }
            public List<Score> score(List<Pair> pairs) { throw new AssertionError("Legacy truncating method must not be called"); }
            public List<Score> scoreComplete(List<Pair> pairs) { return complete.apply(pairs); }
        };
    }
    @Test void explicitCompleteMethodKeepsNegativeLogitsWithoutApplyingTitleThreshold() {
        var provider = provider(pairs -> {
            assertThat(pairs).hasSize(2); assertThat(pairs.getFirst().documentText()).startsWith("CLAIMS\n");
            return List.of(new PatentReranker.Score(pairs.get(1).candidateId(),-90),
                    new PatentReranker.Score(pairs.getFirst().candidateId(),-100));
        });
        var result = ranking.rerank("query",hybrid(),texts(),provider,1);
        assertThat(result.methodVersion()).isEqualTo("HYBRID_FIELD_RRF_COMPLETE_CE/v1");
        assertThat(result.modelVersion()).contains(PatentReranker.COMPLETE_SCORING,"/W512","/M"+"a".repeat(64),"/T"+"b".repeat(64));
        assertThat(result.matches()).hasSize(1); assertThat(result.matches().getFirst().candidate().documentId()).isEqualTo("b");
        assertThat(result.matches().getFirst().rerankScore()).isEqualTo(-90); assertThat(result.truncated()).isTrue();
    }
    @Test void tiesKeepDeterministicIdentityOrderAndOriginalFields() {
        var provider = provider(pairs -> pairs.stream().map(pair -> new PatentReranker.Score(pair.candidateId(),1)).toList());
        var result = ranking.rerank("query",hybrid(),texts(),provider,10);
        assertThat(result.matches()).extracting(value -> value.candidate().documentId()).containsExactly("a","b");
        assertThat(result.matches()).allSatisfy(value -> assertThat(value.candidate().matchFields()).containsExactly("CLAIMS"));
    }
    @Test void missingDuplicateUnexpectedOrNonfiniteScoresCannotBecomeSuccessfulMembers() {
        String a = PatentRerankedQuery.key(candidate("a"));
        for (var output : List.of(List.<PatentReranker.Score>of(),List.of(new PatentReranker.Score(a,1)),
                List.of(new PatentReranker.Score(a,1),new PatentReranker.Score(a,2)),
                List.of(new PatentReranker.Score("unknown",1)),List.of(new PatentReranker.Score(a,Double.NaN))))
            assertThatThrownBy(() -> ranking.rerank("query",hybrid(),texts(),provider(pairs -> output),10))
                    .isInstanceOf(IllegalStateException.class);
    }
    @Test void oldMethodExtraTextAndProviderWithoutCompleteSupportReject() {
        var old = new PatentHybridQuery.Result("HYBRID_RRF/v1","query",hybrid().matches(),2,false);
        assertThatThrownBy(() -> ranking.rerank("query",old,texts(),provider(pairs -> List.of()),10))
                .isInstanceOf(IllegalArgumentException.class);
        var extra = new HashMap<>(texts()); extra.put("unknown","text");
        assertThatThrownBy(() -> ranking.rerank("query",hybrid(),extra,provider(pairs -> List.of()),10))
                .isInstanceOf(IllegalStateException.class);
        var incomplete = new PatentReranker() {
            public Model model() { return PatentFieldRerankedQueryTest.this.model(); }
            public List<Score> score(List<Pair> pairs) { throw new AssertionError("No legacy fallback"); }
        };
        assertThatThrownBy(() -> ranking.rerank("query",hybrid(),texts(),incomplete,10))
                .isInstanceOf(IllegalStateException.class);
    }
}
