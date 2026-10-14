package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Mechanical final-code connection/failure C; no test ACTIVE registration or real patent/model quality claim. */
class ProductPatentFieldSearchRankingTest {
    @Test void exactPublicationDoesNotRequireOrInvokeModels() {
        var doc=new PatentLexicalQuery.Document("source","US-20250149543-A1","v1","capture-v1","a".repeat(64),
                "raw/doc#abstract",Map.of("ABSTRACT","mechanical exact identity input","CLAIMS","mechanical claim input"));
        var original=new PatentFieldSemanticFusion.DocumentEvidence(doc,Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims"));
        var result=new ProductPatentFieldSearchRanking(codec,null,null,100)
                .rank(List.of(source),doc.documentId(),List.of(original),fields);
        assertThat(result.ranking().method()).isEqualTo("EXACT_PUBLICATION/v1");
        assertThat(result.ranking().members()).hasSize(1);
        assertThat(ProductPatentFieldResultEvidence.from(result,fields).models()).isEmpty();
        assertThatThrownBy(() -> new ProductPatentFieldSearchRanking(codec,null,null,100)
                .rank(List.of(source),"mechanical",List.of(original),fields))
                .hasMessage("Final field ranking models required");
    }
    private final Set<String> fields = Set.of("ABSTRACT","CLAIMS");
    private final ProductSemanticIndex semantic = mock(ProductSemanticIndex.class);
    private final ProductSourceAvailability.Active source = new ProductSourceAvailability.Active("source","mechanical-C",
            "capture-v1","decision-C",1,2,"manifest.json","a".repeat(64),"b".repeat(64));
    private final ProductPatentSearchEvidenceCodec codec = new ProductPatentSearchEvidenceCodec(new ObjectMapper());
    private PatentFieldSemanticFusion.DocumentEvidence original() {
        return new PatentFieldSemanticFusion.DocumentEvidence(new PatentLexicalQuery.Document("source","document","v1","capture-v1",
                "a".repeat(64),"raw/doc#abstract",Map.of("ABSTRACT","battery mechanical abstract","CLAIMS","battery mechanical claims")),
                Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims"));
    }
    private void responses() {
        for (String field : fields) {
            when(semantic.fieldModelVersion(field)).thenReturn("complete-model/"+field+"/v1");
            when(semantic.searchField(eq("source"),eq(field),anyString(),eq(1000))).thenReturn(List.of(
                    new ProductSemanticIndex.Match("source","document","v1","capture-v1","a".repeat(64),
                            original().fieldLocators().get(field),0.8)));
        }
    }
    private PatentReranker provider(boolean fail, AtomicInteger calls) {
        return new PatentReranker() {
            public Model model() { return new Model("mechanical-C","model","revision","a".repeat(64),"b".repeat(64),512,"mechanical-C"); }
            public List<Score> score(List<Pair> pairs) { throw new AssertionError("No old scoring operation"); }
            public List<Score> scoreComplete(List<Pair> pairs) {
                calls.incrementAndGet();
                assertThat(pairs).hasSize(1);
                assertThat(pairs.getFirst().documentText()).contains("ABSTRACT\nbattery mechanical abstract\n",
                        "CLAIMS\nbattery mechanical claims\n").doesNotContain("TITLE", "DESCRIPTION");
                if (fail) throw new IllegalStateException("complete model unavailable");
                return List.of(new Score(pairs.getFirst().candidateId(),-20));
            }
        };
    }
    @Test void sourceFieldQueriesProduceTraceableFusionAndCompleteRerankWithoutTitleLeak() {
        responses(); var calls = new AtomicInteger();
        var result = new ProductPatentFieldSearchRanking(codec,semantic,provider(false,calls),100)
                .rank(List.of(source),"battery",List.of(original()),fields);
        assertThat(result.ranking().method()).isEqualTo("HYBRID_FIELD_RRF_COMPLETE_CE/v1");
        assertThat(result.ranking().rerankStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.ranking().members()).hasSize(1);
        var member = result.ranking().members().getFirst();
        assertThat(member.matchFields()).containsExactlyInAnyOrder("ABSTRACT","CLAIMS");
        assertThat(member.rerankScore()).isEqualTo(-20); assertThat(calls).hasValue(1);
        assertThat(result.ranking().confidence().status()).isEqualTo(PatentRetrievalConfidence.Status.LOW_CONFIDENCE);
        assertThat(result.ranking().confidence().reason()).isEqualTo("FIELD_CALIBRATION_UNAVAILABLE");
        assertThat(result.semanticEvidence().candidates().getFirst().fieldLocators()).isEqualTo(original().fieldLocators());
        assertThat(result.semanticEvidence().modelVersions()).containsOnlyKeys(fields);
        for (String field : fields) verify(semantic).searchField("source",field,result.queryPlan().expansion().retrievalQuery(),1000);
        verify(semantic,never()).search(anyString(),anyString(),anyInt());
        assertThat(codec.resultDigest(result.ranking().members(),false)).matches("[0-9a-f]{64}");
        var fieldFacts=ProductPatentFieldResultEvidence.from(result,fields);
        assertThat(ProductPatentFieldResultEvidence.digest(codec,fieldFacts,result.ranking().members(),false))
                .matches("[0-9a-f]{64}").isNotEqualTo(codec.resultDigest(result.ranking().members(),false));
    }
    @Test void completeModelFailureIsExplicitFieldHybridAndNeverOldScore() {
        responses(); var calls = new AtomicInteger();
        var result = new ProductPatentFieldSearchRanking(codec,semantic,provider(true,calls),100)
                .rank(List.of(source),"battery",List.of(original()),fields);
        assertThat(result.ranking().method()).isEqualTo("HYBRID_FIELD_RRF/v1");
        assertThat(result.ranking().rerankStatus()).isEqualTo("RERANK_FAILED");
        assertThat(result.ranking().rerankModelVersion()).contains(PatentReranker.COMPLETE_SCORING,"/M"+"a".repeat(64));
        assertThat(result.ranking().members().getFirst().rerankScore()).isNull(); assertThat(calls).hasValue(1);
        assertThat(result.ranking().confidence().status()).isEqualTo(PatentRetrievalConfidence.Status.LOW_CONFIDENCE);
        assertThat(ProductPatentFieldResultEvidence.digest(codec,ProductPatentFieldResultEvidence.from(result,fields),
                result.ranking().members(),false)).matches("[0-9a-f]{64}");
    }
    @Test void wrongSourceVersionOrFieldLocatorFailsBeforeCompleteScoringNotHybridSuccess() {
        responses(); var calls = new AtomicInteger();
        var service = new ProductPatentFieldSearchRanking(codec,semantic,provider(false,calls),100);
        when(semantic.searchField(eq("source"),eq("CLAIMS"),anyString(),eq(1000))).thenReturn(List.of(
                new ProductSemanticIndex.Match("source","document","v1","changed-source","a".repeat(64),"raw/doc#claims",0.8)));
        assertThatThrownBy(() -> service.rank(List.of(source),"battery",List.of(original()),fields))
                .hasMessage("Field semantic source snapshot differs");
        assertThat(calls).hasValue(0);
        when(semantic.searchField(eq("source"),eq("CLAIMS"),anyString(),eq(1000))).thenReturn(List.of(
                new ProductSemanticIndex.Match("source","document","v1","capture-v1","a".repeat(64),"raw/doc#abstract",0.8)));
        assertThatThrownBy(() -> service.rank(List.of(source),"battery",List.of(original()),fields))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("evidence differs");
        assertThat(calls).hasValue(0);
    }
    @Test void successfulEmptySemanticRecallStillReranksLexicalCandidatesWithNullMissingScore() {
        responses(); var calls = new AtomicInteger();
        for (String field : fields) when(semantic.searchField(eq("source"),eq(field),anyString(),eq(1000))).thenReturn(List.of());
        var result = new ProductPatentFieldSearchRanking(codec,semantic,provider(false,calls),100)
                .rank(List.of(source),"battery",List.of(original()),fields);
        assertThat(result.ranking().rerankStatus()).isEqualTo("SUCCEEDED"); assertThat(calls).hasValue(1);
        assertThat(result.ranking().members().getFirst().semanticScore()).isNull();
        assertThat(result.ranking().members().getFirst().semanticRank()).isNull();
        assertThat(codec.resultDigest(result.ranking().members(),false)).matches("[0-9a-f]{64}");
    }
    @Test void emptyTruePoolNeedsNoFabricatedModelOutputAndMissingFieldResponseFails() {
        responses(); var calls = new AtomicInteger();
        for (String field : fields) when(semantic.searchField(eq("source"),eq(field),anyString(),eq(1000))).thenReturn(List.of());
        var service = new ProductPatentFieldSearchRanking(codec,semantic,provider(false,calls),100);
        var empty = service.rank(List.of(source),"battery",List.of(),fields).ranking();
        assertThat(empty.members()).isEmpty();
        assertThat(empty.confidence().status()).isEqualTo(PatentRetrievalConfidence.Status.NO_RESULT);
        assertThat(empty.confidence().ruleVersion()).isEqualTo(PatentRetrievalConfidence.RULE_VERSION);
        assertThat(empty.confidence().reason()).isEqualTo("NO_MATCHING_CANDIDATE");
        assertThat(empty.rerankStatus()).isNull();
        assertThat(calls).hasValue(0);
        when(semantic.searchField(eq("source"),eq("CLAIMS"),anyString(),eq(1000))).thenReturn(null);
        assertThatThrownBy(() -> service.rank(List.of(source),"battery",List.of(original()),fields))
                .hasMessage("Bounded field semantic response missing");
        assertThat(calls).hasValue(0);
    }
}
