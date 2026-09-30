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
import java.util.UUID;

/** Encodes immutable search evidence without owning execution or authorization decisions. */
final class ProductPatentSearchEvidenceCodec {
    private final ObjectMapper mapper;

    ProductPatentSearchEvidenceCodec(ObjectMapper mapper) {
        this.mapper = mapper;
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
        values.add(Boolean.toString(truncated));
        for (var member : members) values.add(member.sourceId() + "\u0000" + member.documentId()
                + "\u0000" + member.documentVersion() + "\u0000" + member.rawSha() + "\u0000"
                + String.valueOf(member.lexicalScore()) + "\u0000" + String.valueOf(member.semanticScore())
                + "\u0000" + String.valueOf(member.fusionScore()) + "\u0000"
                + String.valueOf(member.rerankScore()) + "\u0000"
                + String.join("\u0001", member.matchedTerms()));
        return digest(values.toArray(String[]::new));
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
                    match.lexicalScore(), match.fieldScores(), match.lexicalRank(), match.semanticScore(),
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
                    match.lexicalScore(), match.fieldScores(), match.lexicalRank(), match.semanticScore(),
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
