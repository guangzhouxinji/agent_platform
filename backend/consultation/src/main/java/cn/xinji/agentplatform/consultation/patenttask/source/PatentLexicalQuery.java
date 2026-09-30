package cn.xinji.agentplatform.consultation.patenttask.source;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Literal AND over the fields supplied by a licensed source adapter. */
public final class PatentLexicalQuery {
    public record Document(String sourceId, String documentId, String documentVersion,
                           String sourceVersion, String rawSha, String locatorRef,
                           Map<String, String> fields) { }
    public record Match(String sourceId, String documentId, String documentVersion,
                        String sourceVersion, String rawSha, String locatorRef,
                        Set<String> matchFields) { }
    public record Result(List<Match> matches, boolean truncated) { }

    public Result search(List<Document> documents, String query, Set<String> allowedFields,
            int maxResults) {
        if (documents == null || query == null || query.isBlank() || allowedFields == null
                || allowedFields.isEmpty() || maxResults < 1 || allowedFields.stream().anyMatch(
                    field -> field == null || field.isBlank()))
            throw new IllegalArgumentException("Exact query, fields and result bound required");
        List<String> terms = List.of(fold(query.strip()).split("\\s+"));
        List<Match> matches = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Document document : documents) {
            if (document == null || blank(document.sourceId()) || blank(document.documentId())
                    || blank(document.documentVersion()) || blank(document.sourceVersion())
                    || document.rawSha() == null || !document.rawSha().matches("[0-9a-fA-F]{64}")
                    || blank(document.locatorRef()) || document.fields() == null)
                throw new IllegalArgumentException("Versioned source document required");
            if (!keys.add(document.sourceId() + "\u0000" + document.documentId() + "\u0000"
                    + document.documentVersion()))
                throw new IllegalArgumentException("Duplicate source document version");
            Set<String> matched = new HashSet<>();
            Set<String> remaining = new HashSet<>(terms);
            for (String field : allowedFields) {
                String value = document.fields().get(field);
                if (value == null) continue;
                String text = fold(value);
                for (String term : terms) {
                    if (text.contains(term)) {
                        matched.add(field);
                        remaining.remove(term);
                    }
                }
            }
            if (remaining.isEmpty())
                matches.add(new Match(document.sourceId(), document.documentId(),
                        document.documentVersion(), document.sourceVersion(),
                        document.rawSha(), document.locatorRef(), Set.copyOf(matched)));
        }
        matches.sort(Comparator.<Match>comparingInt(match -> match.matchFields().size()).reversed()
                .thenComparing(Match::sourceId).thenComparing(Match::documentId)
                .thenComparing(Match::documentVersion));
        boolean truncated = matches.size() > maxResults;
        return new Result(List.copyOf(matches.subList(0, Math.min(matches.size(), maxResults))), truncated);
    }

    private static String fold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
