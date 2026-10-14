package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mechanical ranking/version fault C only; these documents/scores are not source data or Benchmark. */
class PatentFieldSemanticFusionTest {
    private final Set<String> fields = Set.of("ABSTRACT","CLAIMS");
    private final Map<String,String> models = Map.of("ABSTRACT","complete-model/v1","CLAIMS","complete-model/v1");
    private final PatentFieldSemanticFusion fusion = new PatentFieldSemanticFusion();
    private PatentFieldSemanticFusion.DocumentEvidence doc(String id) {
        return new PatentFieldSemanticFusion.DocumentEvidence(new PatentLexicalQuery.Document("source",id,"v1","capture-v1",
                "a".repeat(64),"raw/"+id+"#abstract",Map.of("ABSTRACT","mechanical text","CLAIMS","mechanical text")),
                Map.of("ABSTRACT","raw/"+id+"#abstract","CLAIMS","raw/"+id+"#claims"));
    }
    private PatentFieldSemanticFusion.FieldScore score(String id,String field,double value) {
        return new PatentFieldSemanticFusion.FieldScore("source",id,"v1","capture-v1","a".repeat(64),field,
                "raw/"+id+(field.equals("ABSTRACT")?"#abstract":"#claims"),models.get(field),value);
    }
    @Test void independentlyRanksFieldsThenUsesEqualWeightRrfWithStableTies() {
        var inputs = Map.of("ABSTRACT",List.of(score("b","ABSTRACT",0.99),score("a","ABSTRACT",0.98)),
                "CLAIMS",List.of(score("b","CLAIMS",0.1),score("a","CLAIMS",0.2)));
        var result = fusion.fuse(List.of(doc("b"),doc("a")),fields,models,inputs,1000);
        assertThat(result.methodVersion()).isEqualTo("FIELD_SEMANTIC_RRF_K60/v1");
        assertThat(result.candidates()).extracting(value -> value.match().documentId()).containsExactly("a","b");
        var first = result.candidates().getFirst();
        assertThat(first.match().semanticScore()).isEqualTo((1.0/62+1.0/61)/2);
        assertThat(first.fieldScores()).containsExactlyInAnyOrderEntriesOf(Map.of("ABSTRACT",0.98,"CLAIMS",0.2));
        assertThat(first.fieldRanks()).containsExactlyInAnyOrderEntriesOf(Map.of("ABSTRACT",2,"CLAIMS",1));
        assertThat(first.fieldLocators()).isEqualTo(doc("a").fieldLocators());
        assertThat(first.match().semanticFields()).isEqualTo(fields);
        assertThatThrownBy(() -> first.fieldScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        var bounded = fusion.fuse(List.of(doc("a"),doc("b")),fields,models,inputs,1);
        assertThat(bounded.candidateCount()).isEqualTo(2); assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.candidates()).hasSize(1);
    }
    @Test void missingFromOneSuccessfulRecallBoundaryIsNotMissingIndexOrTitleFallback() {
        var inputs = Map.of("ABSTRACT",List.of(score("a","ABSTRACT",0.8)),"CLAIMS",List.<PatentFieldSemanticFusion.FieldScore>of());
        var candidate = fusion.fuse(List.of(doc("a")),fields,models,inputs,1000).candidates().getFirst();
        assertThat(candidate.match().semanticScore()).isEqualTo(1.0/61/2);
        assertThat(candidate.match().semanticFields()).containsExactly("ABSTRACT");
        var lexical = new PatentBm25fQuery().search(List.of(doc("a").document()),"unmatched",fields,1000);
        var hybrid = new PatentHybridQuery().fuseFields(lexical,List.of(candidate.match()),10);
        assertThat(hybrid.methodVersion()).isEqualTo("HYBRID_FIELD_RRF/v1");
        assertThat(hybrid.matches().getFirst().matchFields()).containsExactly("ABSTRACT");
        assertThatThrownBy(() -> new PatentHybridQuery().fuse(lexical,List.of(candidate.match()),10))
                .isInstanceOf(IllegalArgumentException.class);
        var legacy = new PatentHybridQuery.SemanticMatch("source","a","v1","capture-v1","a".repeat(64),"raw/a#abstract",0.8);
        assertThat(new PatentHybridQuery().fuse(lexical,List.of(legacy),10).methodVersion()).isEqualTo("HYBRID_RRF/v1");
        assertThatThrownBy(() -> new PatentHybridQuery().fuseFields(lexical,List.of(legacy),10))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everySelectedFieldResponseAndModelIsMandatoryEvenWhenEmpty() {
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,models,
                Map.of("ABSTRACT",List.of(score("a","ABSTRACT",0.9))),10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,Map.of("ABSTRACT","model"),
                Map.of("ABSTRACT",List.of(),"CLAIMS",List.of()),10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fusion.fuse(List.of(),fields,models,Map.of("ABSTRACT",List.of(),"CLAIMS",List.of()),10).candidateCount()).isZero();
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),Set.of("LEGAL_STATUS"),Map.of(),Map.of(),10))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongModelVersionOriginalLocatorOrCosineCannotBeFused() {
        var value = score("a","ABSTRACT",0.9);
        var bad = List.of(new PatentFieldSemanticFusion.FieldScore(value.sourceId(),value.documentId(),"v2",value.sourceVersion(),
                        value.rawSha(),value.field(),value.locatorRef(),value.modelVersion(),value.cosine()),
                new PatentFieldSemanticFusion.FieldScore(value.sourceId(),value.documentId(),value.documentVersion(),value.sourceVersion(),
                        value.rawSha(),value.field(),"raw/a#claims",value.modelVersion(),value.cosine()));
        for (var wrong : bad) assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,models,
                Map.of("ABSTRACT",List.of(wrong),"CLAIMS",List.of()),10)).isInstanceOf(IllegalStateException.class);
        for (double invalid : new double[]{Double.NaN,Double.POSITIVE_INFINITY,1.2,-1.2})
            assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,models,
                    Map.of("ABSTRACT",List.of(score("a","ABSTRACT",invalid)),"CLAIMS",List.of()),10))
                    .isInstanceOf(IllegalArgumentException.class);
        var wrongModel = new HashMap<>(models); wrongModel.put("ABSTRACT","different");
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,wrongModel,
                Map.of("ABSTRACT",List.of(value),"CLAIMS",List.of()),10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicatesWrongPoolScopeAndRecallBudgetCannotCreatePartialCandidates() {
        var value = score("a","ABSTRACT",0.9);
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,models,
                Map.of("ABSTRACT",List.of(value,value),"CLAIMS",List.of()),10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a"),doc("a")),fields,models,
                Map.of("ABSTRACT",List.of(value),"CLAIMS",List.of()),10)).isInstanceOf(IllegalArgumentException.class);
        var wrong = new PatentFieldSemanticFusion.DocumentEvidence(doc("a").document(),
                Map.of("ABSTRACT","raw/a#abstract","CLAIMS","different/file#claims"));
        assertThatThrownBy(() -> fusion.fuse(List.of(wrong),fields,models,
                Map.of("ABSTRACT",List.of(value),"CLAIMS",List.of()),10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(doc("a")),fields,models,
                Map.of("ABSTRACT",Collections.nCopies(1001,value),"CLAIMS",List.of()),10)).isInstanceOf(IllegalArgumentException.class);
    }
}
