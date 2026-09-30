package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatentHybridQueryTest {
    @Test void reciprocalRankFusionKeepsBothSignalsAndStableEvidence() {
        var documents = List.of(document("a", "battery storage system"),
                document("b", "energy accumulator"), document("c", "protective coating"));
        var lexical = new PatentBm25fQuery().search(documents, "battery storage", Set.of("TITLE"), 1000);
        var semantic = List.of(semantic("b", 0.91), semantic("a", 0.88), semantic("c", 0.10));
        var result = new PatentHybridQuery().fuse(lexical, semantic, 2);

        assertThat(result.methodVersion()).isEqualTo("HYBRID_RRF/v1");
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.matches()).extracting(PatentHybridQuery.Match::documentId)
                .containsExactly("a", "b");
        assertThat(result.matches().getFirst().lexicalRank()).isEqualTo(1);
        assertThat(result.matches().getFirst().semanticRank()).isEqualTo(2);
        assertThat(result.matches().get(1).lexicalScore()).isNull();
        assertThat(result.matches().get(1).semanticScore()).isEqualTo(0.91);
    }

    @Test void mismatchedEvidenceAndMissingSemanticPathFailClosed() {
        var lexical = new PatentBm25fQuery().search(List.of(document("a", "battery")),
                "battery", Set.of("TITLE"), 10);
        var drifted = new PatentHybridQuery.SemanticMatch("source", "a", "v1", "source-v2",
                "b".repeat(64), "raw/a#title", 0.9);
        assertThatThrownBy(() -> new PatentHybridQuery().fuse(lexical, List.of(drifted), 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("versions differ");
        assertThatThrownBy(() -> new PatentHybridQuery().fuse(lexical, List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PatentLexicalQuery.Document document(String id, String title) {
        return new PatentLexicalQuery.Document("source", id, "v1", "source-v1", "a".repeat(64),
                "raw/" + id + "#title", Map.of("TITLE", title));
    }
    private static PatentHybridQuery.SemanticMatch semantic(String id, double score) {
        return new PatentHybridQuery.SemanticMatch("source", id, "v1", "source-v1", "a".repeat(64),
                "raw/" + id + "#title", score);
    }
}
