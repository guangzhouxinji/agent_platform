package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Arithmetic/encoding C only; real DM8 retained-result checks are separate evidence. */
class ProductLegacySearchDigestTest {
    @ParameterizedTest
    @ValueSource(strings={"LEXICAL_AND/v1","BM25F/v1","HYBRID_RRF/v1","HYBRID_RRF_CE/v1","EXACT_PUBLICATION/v1"})
    void originalEncodingsPreserveRawScoresInsteadOfStoredDecimals(String method) {
        boolean and=method.equals("LEXICAL_AND/v1"),exact=method.equals("EXACT_PUBLICATION/v1");
        boolean hybrid=method.startsWith("HYBRID"),ce=method.equals("HYBRID_RRF_CE/v1");
        double rawLexical=exact?1.0:0.12345678901234567,rawSemantic=0.987654321012345;
        double rawFusion=1.0/61+1.0/62,rawRerank=(double)0.12345679f;
        var fields=and?Map.<String,Double>of():Map.of(exact?"DOCUMENT_ID":"TITLE",rawLexical);
        var terms=and?List.<String>of():List.of(exact?"document":"battery");
        var member=new ProductPatentSearchExecution.Member("member",0,"source","document","v1","capture:v1",
                "a".repeat(64),"raw#title",Set.of(exact?"DOCUMENT_ID":"TITLE"),terms,
                and?null:stored(rawLexical),fields,and?null:1,hybrid?stored(rawSemantic):null,
                hybrid?2:null,hybrid?stored(rawFusion):null,ce?stored(rawRerank):null,ce?1:null);
        var lexical=new PatentBm25fQuery.Match("source","document","v1","capture:v1","a".repeat(64),
                "raw#title",member.matchFields(),terms,rawLexical,fields);
        String expected="source\u0000document\u0000v1\u0000"+"a".repeat(64);
        if(!and) {
            expected+="\u0000"+rawLexical;
            if(!method.equals("BM25F/v1")) {
                expected+="\u0000"+(hybrid?rawSemantic:null)+"\u0000"+(hybrid?rawFusion:null);
                if(!method.equals("HYBRID_RRF/v1")) expected+="\u0000"+(ce?rawRerank:null);
            }
            expected+="\u0000"+String.join("\u0001",terms);
        }
        assertThat(ProductLegacySearchDigest.recompute(method,false,List.of(member),
                and?Map.of():Map.of(ProductLegacySearchDigest.key(member),lexical),
                hybrid?Map.of(ProductLegacySearchDigest.key(member),rawSemantic):Map.of()))
                .isEqualTo(ProductPatentSearchEvidenceCodec.digestValues("false",expected));
    }
    @Test void missingOrChangedRawLexicalScoreFailsClosed() {
        var member=member(1.0);
        assertThatThrownBy(()->ProductLegacySearchDigest.recompute("BM25F/v1",false,List.of(member),Map.of(),Map.of()))
                .isInstanceOf(IllegalStateException.class);
        var wrong=new PatentBm25fQuery.Match("source","document","v1","source:v1","a".repeat(64),
                "raw#title",Set.of("TITLE"),List.of("battery"),1.1,Map.of("TITLE",1.0));
        assertThatThrownBy(()->ProductLegacySearchDigest.recompute("BM25F/v1",false,List.of(member),
                Map.of(ProductLegacySearchDigest.key(member),wrong),Map.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void unknownEncodingAndNonfiniteScoresAreRejected() {
        assertThatThrownBy(()->ProductLegacySearchDigest.recompute("UNKNOWN",false,List.of(),Map.of(),Map.of()))
                .isInstanceOf(IllegalStateException.class);
        var member=member(Double.NaN);
        var raw=new PatentBm25fQuery.Match("source","document","v1","source:v1","a".repeat(64),
                "raw#title",Set.of("TITLE"),List.of("battery"),Double.NaN,Map.of("TITLE",1.0));
        assertThatThrownBy(()->ProductLegacySearchDigest.recompute("BM25F/v1",false,List.of(member),
                Map.of(ProductLegacySearchDigest.key(member),raw),Map.of())).isInstanceOf(IllegalStateException.class);
    }
    private static ProductPatentSearchExecution.Member member(double score) {
        return new ProductPatentSearchExecution.Member("member",0,"source","document","v1","source:v1",
                "a".repeat(64),"raw#title",Set.of("TITLE"),List.of("battery"),score,Map.of("TITLE",1.0),
                1,null,null,null,null,null);
    }
    private static double stored(double value) {
        return BigDecimal.valueOf(value).setScale(12,RoundingMode.HALF_UP).doubleValue();
    }
}
