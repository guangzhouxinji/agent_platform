package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic source-neutral matching; admission and field-use checks precede this kernel. */
public final class ProductLexicalAnd {
    public static final String VERSION = "LEXICAL_AND/v1";
    public record Location(String field, String term, int byteStart, int byteEnd) { }
    public record Match(int matchedFieldCount, List<Location> locations) { }
    public record Query(List<String> terms) { }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-_][\\p{L}\\p{N}]+)*");

    private ProductLexicalAnd() { }

    public static Query parse(String text) {
        if (text == null || text.isBlank() || text.length() > 2000)
            throw new IllegalArgumentException("Query must contain bounded text");
        var words = WORD.matcher(text);
        Set<String> terms = new LinkedHashSet<>();
        while (words.find()) {
            terms.add(words.group().toLowerCase(java.util.Locale.ROOT));
            if (terms.size() > 32) throw new IllegalArgumentException("Too many query terms");
        }
        if (terms.isEmpty()) throw new IllegalArgumentException("Query has no searchable terms");
        return new Query(List.copyOf(terms));
    }

    public static Optional<Match> match(Query query, Map<String, String> document,
            Set<String> allowedFields) {
        if (query == null || query.terms().isEmpty() || document == null
                || allowedFields == null || allowedFields.isEmpty())
            throw new IllegalArgumentException("Query and permitted source fields are required");
        List<Location> locations = new ArrayList<>();
        Set<String> hitFields = new LinkedHashSet<>();
        for (String term : query.terms()) {
            boolean found = false;
            for (var entry : document.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                String field = entry.getKey();
                String value = entry.getValue();
                if (!allowedFields.contains(field) || value == null) continue;
                var matcher = Pattern.compile(Pattern.quote(term),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(value);
                if (matcher.find()) {
                    found = true;
                    hitFields.add(field);
                    locations.add(new Location(field, term,
                            value.substring(0, matcher.start()).getBytes(StandardCharsets.UTF_8).length,
                            value.substring(0, matcher.end()).getBytes(StandardCharsets.UTF_8).length));
                }
            }
            if (!found) return Optional.empty();
        }
        return Optional.of(new Match(hitFields.size(), List.copyOf(locations)));
    }
}
