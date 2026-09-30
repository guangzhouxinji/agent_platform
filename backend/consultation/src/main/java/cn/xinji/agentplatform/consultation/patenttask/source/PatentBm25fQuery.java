package cn.xinji.agentplatform.consultation.patenttask.source;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned BM25F retrieval over fields admitted by the source contract. */
public final class PatentBm25fQuery {
    public static final String METHOD_VERSION = "BM25F/v1";
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*");
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Map<String, Double> WEIGHTS = Map.of(
            "TITLE", 4.0, "ABSTRACT", 2.0, "CLAIMS", 1.5, "DESCRIPTION", 1.0);

    public record Match(String sourceId, String documentId, String documentVersion,
                        String sourceVersion, String rawSha, String locatorRef,
                        Set<String> matchFields, List<String> matchedTerms,
                        double lexicalScore, Map<String, Double> fieldScores) { }
    public record Result(String methodVersion, String normalizedQuery, List<String> terms,
                         List<Match> matches, int candidateCount, boolean truncated) { }

    public Result search(List<PatentLexicalQuery.Document> documents, String query,
            Set<String> allowedFields, int maxResults) {
        validate(documents, query, allowedFields, maxResults);
        String normalized = fold(query);
        List<String> terms = queryTokens(normalized);
        if (terms.isEmpty()) throw new IllegalArgumentException("Query has no searchable terms");
        if (documents.isEmpty())
            return new Result(METHOD_VERSION, normalized, terms, List.of(), 0, false);
        List<Prepared> prepared = documents.stream().map(d -> prepare(d, allowedFields)).toList();
        Map<String, Double> averageLength = averages(prepared, allowedFields);
        Map<String, Integer> documentFrequency = documentFrequency(prepared, terms);
        List<Match> matches = new ArrayList<>();
        for (Prepared document : prepared) {
            Match match = score(document, terms, documentFrequency, averageLength, prepared.size());
            if (match != null) matches.add(match);
        }
        matches.sort(Comparator.comparingDouble(Match::lexicalScore).reversed()
                .thenComparing(Match::sourceId).thenComparing(Match::documentId)
                .thenComparing(Match::documentVersion));
        boolean truncated = matches.size() > maxResults;
        return new Result(METHOD_VERSION, normalized, terms,
                List.copyOf(matches.subList(0, Math.min(matches.size(), maxResults))),
                matches.size(), truncated);
    }

    private static Match score(Prepared document, List<String> terms, Map<String, Integer> df,
            Map<String, Double> averageLength, int totalDocuments) {
        double total = 0;
        Map<String, Double> fieldScores = new LinkedHashMap<>();
        Set<String> matchedFields = new LinkedHashSet<>();
        Set<String> matchedTerms = new LinkedHashSet<>();
        for (String term : terms) {
            double weightedTf = 0;
            Map<String, Double> contributions = new HashMap<>();
            for (var field : document.tokens.entrySet()) {
                long frequency = field.getValue().stream().filter(term::equals).count();
                if (frequency == 0) continue;
                double length = field.getValue().size();
                double average = averageLength.getOrDefault(field.getKey(), 1.0);
                double normalizedTf = frequency / ((1 - B) + B * length / average);
                double contribution = WEIGHTS.getOrDefault(field.getKey(), 1.0) * normalizedTf;
                weightedTf += contribution;
                contributions.merge(field.getKey(), contribution, Double::sum);
                matchedFields.add(field.getKey());
                matchedTerms.add(term);
            }
            if (weightedTf == 0) continue;
            double idf = Math.log(1 + (totalDocuments - df.get(term) + 0.5) / (df.get(term) + 0.5));
            double termScore = idf * weightedTf * (K1 + 1) / (weightedTf + K1);
            total += termScore;
            for (var contribution : contributions.entrySet())
                fieldScores.merge(contribution.getKey(), termScore * contribution.getValue() / weightedTf,
                        Double::sum);
        }
        if (total == 0) return null;
        var d = document.document;
        return new Match(d.sourceId(), d.documentId(), d.documentVersion(), d.sourceVersion(),
                d.rawSha(), d.locatorRef(), Set.copyOf(matchedFields), List.copyOf(matchedTerms),
                total, Map.copyOf(fieldScores));
    }

    private static Prepared prepare(PatentLexicalQuery.Document document, Set<String> allowedFields) {
        validateDocument(document);
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String field : allowedFields) {
            String value = document.fields().get(field);
            if (value != null && !value.isBlank()) values.put(field, contentTokens(fold(value)));
        }
        return new Prepared(document, values);
    }

    private static Map<String, Double> averages(List<Prepared> documents, Set<String> fields) {
        Map<String, Double> result = new HashMap<>();
        for (String field : fields) {
            double total = documents.stream().mapToInt(d -> d.tokens.getOrDefault(field, List.of()).size()).sum();
            result.put(field, Math.max(1.0, total / documents.size()));
        }
        return result;
    }

    private static Map<String, Integer> documentFrequency(List<Prepared> documents, List<String> terms) {
        Map<String, Integer> result = new HashMap<>();
        for (String term : terms) {
            int count = (int) documents.stream().filter(document -> document.tokens.values().stream()
                    .anyMatch(tokens -> tokens.contains(term))).count();
            result.put(term, count);
        }
        return result;
    }

    private static List<String> queryTokens(String value) {
        return List.copyOf(new LinkedHashSet<>(contentTokens(value)));
    }

    private static List<String> contentTokens(String value) {
        Matcher matcher = TOKEN.matcher(value);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            result.add(token);
            if (token.codePoints().allMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.HAN)) {
                int[] points = token.codePoints().toArray();
                for (int index = 0; index + 1 < points.length; index++)
                    result.add(new String(points, index, 2));
            }
        }
        return List.copyOf(result);
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).strip();
    }

    private static void validate(List<PatentLexicalQuery.Document> documents, String query,
            Set<String> fields, int limit) {
        if (documents == null || query == null || query.isBlank()
                || fields == null || fields.isEmpty() || limit < 1
                || fields.stream().anyMatch(field -> !WEIGHTS.containsKey(field)))
            throw new IllegalArgumentException("Documents, query, admitted fields and limit required");
        Set<String> keys = new LinkedHashSet<>();
        for (var document : documents) {
            validateDocument(document);
            if (!keys.add(document.sourceId() + '\0' + document.documentId() + '\0'
                    + document.documentVersion()))
                throw new IllegalArgumentException("Duplicate source document version");
        }
    }

    private static void validateDocument(PatentLexicalQuery.Document document) {
        if (document == null || blank(document.sourceId()) || blank(document.documentId())
                || blank(document.documentVersion()) || blank(document.sourceVersion())
                || document.rawSha() == null || !document.rawSha().matches("[0-9a-fA-F]{64}")
                || blank(document.locatorRef()) || document.fields() == null)
            throw new IllegalArgumentException("Versioned source document required");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Prepared(PatentLexicalQuery.Document document, Map<String, List<String>> tokens) { }
}
