package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRerankedQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Encodes immutable search evidence without owning execution or authorization decisions. */
final class ProductPatentSearchEvidenceCodec {
    private final ObjectMapper mapper;

    ProductPatentSearchEvidenceCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ProductPatentFieldEvidencePersistence fieldStore(org.springframework.jdbc.core.JdbcTemplate db) {
        return new ProductPatentFieldEvidencePersistence(db,mapper);
    }

    List<String> nonEmptyStringList(String json) {
        List<String> values = stringList(json);
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalStateException("Stored search scope is empty");
        return values;
    }

    List<String> stringList(String json) {
        try {
            List<String> values = mapper.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : List.copyOf(values);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored string evidence is invalid", failure);
        }
    }

    Map<String, Double> scoreMap(String json) {
        try {
            Map<String, Double> values = mapper.readValue(json,
                    new TypeReference<Map<String, Double>>() { });
            return values == null ? Map.of() : Map.copyOf(values);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored score evidence is invalid", failure);
        }
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Search explanation is invalid", failure);
        }
    }

    String resultDigest(List<ProductPatentSearchExecution.Member> members, boolean truncated) {
        List<String> values = new ArrayList<>();
        values.add("RESULT_DIGEST_DECIMAL_12/v2");
        values.add(Boolean.toString(truncated));
        for (var member : members) values.add(member.sourceId() + "\u0000" + member.documentId()
                + "\u0000" + member.documentVersion() + "\u0000" + member.sourceVersion() + "\u0000"
                + member.rawSha() + "\u0000" + member.locatorRef() + "\u0000" + member.ordinal() + "\u0000"
                + String.join(",",new java.util.TreeSet<>(member.matchFields().isEmpty()?Set.of("TITLE"):member.matchFields())) + "\u0000"
                + storedScore(member.lexicalScore()) + "\u0000" + storedScore(member.semanticScore())
                + "\u0000" + storedScore(member.fusionScore()) + "\u0000"
                + storedScore(member.rerankScore()) + "\u0000"
                + String.join("\u0001", member.matchedTerms()) + "\u0000"
                + fieldScores(member.fieldScores()) + "\u0000" + member.lexicalRank() + "\u0000"
                + member.semanticRank() + "\u0000" + member.rerankRank());
        return digest(values.toArray(String[]::new));
    }
    private static String storedScore(Double score) {
        if(score==null) return "null";
        if(!Double.isFinite(score)) throw new IllegalStateException("Nonfinite search score");
        return java.math.BigDecimal.valueOf(score).setScale(12,java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
    private String fieldScores(Map<String,Double> scores) {
        var values=new java.util.TreeMap<String,String>();
        scores.forEach((field,score)->values.put(field,storedScore(score)));
        return json(values);
    }

    List<ProductPatentSearchExecution.Member> members(List<PatentBm25fQuery.Match> matches) {
        List<ProductPatentSearchExecution.Member> result = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            var match = matches.get(index);
            result.add(new ProductPatentSearchExecution.Member(UUID.randomUUID().toString(), index,
                    match.sourceId(), match.documentId(), match.documentVersion(), match.sourceVersion(),
                    match.rawSha(), match.locatorRef(), match.matchFields(), match.matchedTerms(),
                    match.lexicalScore(), match.fieldScores(), index + 1, null, null, null, null, null));
        }
        return List.copyOf(result);
    }

    List<ProductPatentSearchExecution.Member> hybridMembers(List<PatentHybridQuery.Match> matches) {
        List<ProductPatentSearchExecution.Member> result = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            var match = matches.get(index);
            result.add(new ProductPatentSearchExecution.Member(UUID.randomUUID().toString(), index,
                    match.sourceId(), match.documentId(), match.documentVersion(), match.sourceVersion(),
                    match.rawSha(), match.locatorRef(), match.matchFields(), match.matchedTerms(),
                    match.lexicalScore(), match.fieldScores(), match.lexicalRank(), match.semanticRank() == null ? null : match.semanticScore(),
                    match.semanticRank(), match.fusionScore(), null, null));
        }
        return List.copyOf(result);
    }

    List<ProductPatentSearchExecution.Member> rerankedMembers(List<PatentRerankedQuery.Match> matches) {
        List<ProductPatentSearchExecution.Member> result = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            var ranked = matches.get(index);
            var match = ranked.candidate();
            result.add(new ProductPatentSearchExecution.Member(UUID.randomUUID().toString(), index,
                    match.sourceId(), match.documentId(), match.documentVersion(), match.sourceVersion(),
                    match.rawSha(), match.locatorRef(), match.matchFields(), match.matchedTerms(),
                    match.lexicalScore(), match.fieldScores(), match.lexicalRank(), match.semanticRank() == null ? null : match.semanticScore(),
                    match.semanticRank(), match.fusionScore(), ranked.rerankScore(), ranked.rerankRank()));
        }
        return List.copyOf(result);
    }

    String digest(String... values) {
        return digestValues(values);
    }

    static String digestValues(String... values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : values)
                hash.update((value.getBytes(StandardCharsets.UTF_8).length + ":" + value)
                        .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
