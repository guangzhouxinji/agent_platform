package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Mechanical evidence/digest corruption C, not a persisted request or real patent Benchmark. */
class ProductPatentFieldResultEvidenceTest {
    private final ProductPatentSearchEvidenceCodec codec=new ProductPatentSearchEvidenceCodec(new ObjectMapper());
    private ProductPatentSearchExecution.Member member(String id,double score) {
        return new ProductPatentSearchExecution.Member(id,0,"source","doc","v1","capture-v1","a".repeat(64),
                "raw/doc#abstract",Set.of("ABSTRACT","CLAIMS"),List.of("battery"),1.0,
                Map.of("ABSTRACT",1.0),1,score,1,0.02,2.0,1);
    }
    private ProductPatentFieldResultEvidence.Fields facts(double cosine,int claimsRank) {
        return new ProductPatentFieldResultEvidence.Fields(Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims"),
                Map.of("ABSTRACT",new ProductPatentFieldResultEvidence.Signal(cosine,1,"raw/doc#abstract","model-A/v1"),
                        "CLAIMS",new ProductPatentFieldResultEvidence.Signal(0.3,claimsRank,"raw/doc#claims","model-C/v1")));
    }
    private ProductPatentFieldResultEvidence.Bundle bundle(String id,ProductPatentFieldResultEvidence.Fields facts) {
        return new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,List.of("CLAIMS","ABSTRACT"),
                Map.of("CLAIMS","model-C/v1","ABSTRACT","model-A/v1"),"HYBRID_FIELD_RRF_COMPLETE_CE/v1",
                "COMPLETE_CE_SCORE_DESC_FIELD_RRF_DESC_DOCUMENT_KEY/v1","complete-CE/v1","SUCCEEDED",Map.of(id,facts));
    }
    @Test void fieldDigestIsDistinctFromLegacyAndIndependentOfStorageUuidAndFieldOrder() {
        var members=List.of(member("one",1.0/61)); var original=bundle("one",facts(0.8,1));
        String digest=ProductPatentFieldResultEvidence.digest(codec,original,members,false);
        assertThat(digest).matches("[0-9a-f]{64}").isNotEqualTo(codec.resultDigest(members,false));
        assertThat(ProductPatentFieldResultEvidence.digest(codec,bundle("two",facts(0.8,1)),List.of(member("two",1.0/61)),false))
                .isEqualTo(digest);
        assertThatThrownBy(() -> original.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void changedCosineAndLocatorAreDigestFactsNotDecorativeResponseFields() {
        var members=List.of(member("one",1.0/61));
        String original=ProductPatentFieldResultEvidence.digest(codec,bundle("one",facts(0.8,1)),members,false);
        assertThat(ProductPatentFieldResultEvidence.digest(codec,bundle("one",facts(0.7,1)),members,false)).isNotEqualTo(original);
        var changed=new ProductPatentFieldResultEvidence.Fields(Map.of("ABSTRACT","raw/doc#abstract","CLAIMS","raw/doc#claims-shift"),
                Map.of("ABSTRACT",new ProductPatentFieldResultEvidence.Signal(0.8,1,"raw/doc#abstract","model-A/v1"),
                        "CLAIMS",new ProductPatentFieldResultEvidence.Signal(0.3,1,"raw/doc#claims-shift","model-C/v1")));
        assertThat(ProductPatentFieldResultEvidence.digest(codec,bundle("one",changed),members,false)).isNotEqualTo(original);
    }
    @Test void missingFieldWrongRankAndWrongModelCannotValidate() {
        var members=List.of(member("one",1.0/61));
        assertThatThrownBy(() -> ProductPatentFieldResultEvidence.validate(bundle("one",facts(0.8,2)),members))
                .isInstanceOf(IllegalStateException.class);
        var missing=new ProductPatentFieldResultEvidence.Fields(Map.of("ABSTRACT","raw/doc#abstract"),facts(0.8,1).semantic());
        assertThatThrownBy(() -> ProductPatentFieldResultEvidence.validate(bundle("one",missing),members))
                .isInstanceOf(IllegalStateException.class);
        var wrongModel=new ProductPatentFieldResultEvidence.Fields(facts(0.8,1).locators(),
                Map.of("ABSTRACT",new ProductPatentFieldResultEvidence.Signal(0.8,1,"raw/doc#abstract","wrong-model"),
                        "CLAIMS",facts(0.8,1).semantic().get("CLAIMS")));
        assertThatThrownBy(() -> ProductPatentFieldResultEvidence.validate(bundle("one",wrongModel),members))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void storedDecimalPrecisionAndCanonicalJsonRoundTripKeepDigestStable() throws Exception {
        var raw=List.of(member("one",1.0/61)); var stored=List.of(member("one",BigDecimal.valueOf(1.0/61)
                .setScale(12,RoundingMode.HALF_UP).doubleValue()));
        var facts=facts(0.81234567891234,1); var bundle=bundle("one",facts);
        assertThat(ProductPatentFieldResultEvidence.digest(codec,bundle,raw,false))
                .isEqualTo(ProductPatentFieldResultEvidence.digest(codec,bundle,stored,false));
        var mapper=new ObjectMapper();
        var restored=mapper.readValue(mapper.writeValueAsString(ProductPatentFieldResultEvidence.canonical(facts)),
                ProductPatentFieldResultEvidence.Fields.class);
        assertThat(ProductPatentFieldResultEvidence.digest(codec,bundle("one",restored),stored,false))
                .isEqualTo(ProductPatentFieldResultEvidence.digest(codec,bundle,raw,false));
    }
    @Test void titleOnlyUnknownVersionAndNonfiniteSignalCannotPretendFieldV3() {
        assertThatThrownBy(() -> new ProductPatentFieldResultEvidence.Signal(Double.NaN,1,"raw/ref","model"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,
                List.of("TITLE"),Map.of("TITLE","model"),"HYBRID_FIELD_RRF/v1","ordering",null,null,Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductPatentFieldResultEvidence.Bundle("old-version",List.of("CLAIMS"),
                Map.of("CLAIMS","model"),"HYBRID_FIELD_RRF/v1","ordering",null,null,Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
