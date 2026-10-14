package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Original, checksum-proven encodings. This reader never updates old results or grants access. */
final class ProductLegacySearchDigest {
    static String recompute(String method, boolean truncated,
            List<ProductPatentSearchExecution.Member> members,
            Map<String,PatentBm25fQuery.Match> lexical,Map<String,Double> semantic) {
        if(!List.of("LEXICAL_AND/v1","BM25F/v1","HYBRID_RRF/v1","HYBRID_RRF_CE/v1",
                "EXACT_PUBLICATION/v1").contains(method)) invalid();
        List<String> values=new ArrayList<>();values.add(Boolean.toString(truncated));
        for(var member:members) {
            String value=member.sourceId()+"\u0000"+member.documentId()+"\u0000"
                    +member.documentVersion()+"\u0000"+member.rawSha();
            if(!method.equals("LEXICAL_AND/v1")) {
                var rawLexical=lexical.get(key(member));
                Double lexicalScore=member.lexicalScore()==null?null:rawLexical==null?null:rawLexical.lexicalScore();
                sameDecimal(lexicalScore,member.lexicalScore());
                if(lexicalScore!=null && (!rawLexical.fieldScores().equals(member.fieldScores())
                        || !rawLexical.matchedTerms().equals(member.matchedTerms()))) invalid();
                if(lexicalScore==null && (!member.fieldScores().isEmpty() || !member.matchedTerms().isEmpty())) invalid();
                value+="\u0000"+lexicalScore;
                if(!method.equals("BM25F/v1")) {
                    Double semanticScore=semantic.get(key(member));
                    sameDecimal(semanticScore,member.semanticScore());
                    Double fusion=member.fusionScore()==null?null:
                            (member.lexicalRank()==null?0:1.0/(60+member.lexicalRank()))
                            +(member.semanticRank()==null?0:1.0/(60+member.semanticRank()));
                    sameDecimal(fusion,member.fusionScore());
                    value+="\u0000"+semanticScore+"\u0000"+fusion;
                    if(!method.equals("HYBRID_RRF/v1")) {
                        Double rerank=member.rerankScore()==null?null:(double)member.rerankScore().floatValue();
                        sameDecimal(rerank,member.rerankScore());value+="\u0000"+rerank;
                    } else if(member.rerankScore()!=null) invalid();
                } else if(member.semanticScore()!=null || member.fusionScore()!=null || member.rerankScore()!=null) invalid();
                value+="\u0000"+String.join("\u0001",member.matchedTerms());
            } else if(member.lexicalScore()!=null || member.semanticScore()!=null
                    || member.fusionScore()!=null || member.rerankScore()!=null
                    || member.lexicalRank()!=null || member.semanticRank()!=null || member.rerankRank()!=null
                    || !member.matchedTerms().isEmpty() || !member.fieldScores().isEmpty()) invalid();
            values.add(value);
        }
        return ProductPatentSearchEvidenceCodec.digestValues(values.toArray(String[]::new));
    }
    static String key(ProductPatentSearchExecution.Member member) {
        return member.sourceId()+"\u0000"+member.documentId()+"\u0000"+member.documentVersion();
    }
    static String key(PatentBm25fQuery.Match match) {
        return match.sourceId()+"\u0000"+match.documentId()+"\u0000"+match.documentVersion();
    }
    private static void sameDecimal(Double raw,Double stored) {
        if(raw==null || stored==null) { if(raw!=stored) invalid();return; }
        if(!Double.isFinite(raw) || !Double.isFinite(stored)
                || BigDecimal.valueOf(raw).setScale(12,RoundingMode.HALF_UP)
                        .compareTo(BigDecimal.valueOf(stored).setScale(12,RoundingMode.HALF_UP))!=0) invalid();
    }
    private static void invalid() { throw new IllegalStateException("Historical pre-storage score evidence inconsistent"); }
    private ProductLegacySearchDigest() { }
}
