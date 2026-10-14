package cn.xinji.agentplatform.consultation.patenttask.source;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic, versioned expansion whose additions remain inspectable evidence. */
public final class PatentQueryExpansion {
    public static final String VERSION = "QUERY_EXPANSION/v1";
    private static final String SOURCE = "PROJECT_PATENT_TERMS/2026-09-29";
    private static final Map<String, List<Term>> TERMS = Map.ofEntries(
            Map.entry("battery", List.of(new Term("battery", "accumulator", "RELATED_TERM", SOURCE, VERSION))),
            Map.entry("accumulator", List.of(new Term("accumulator", "battery", "RELATED_TERM", SOURCE, VERSION))),
            Map.entry("coating", List.of(new Term("coating", "film", "RELATED_TERM", SOURCE, VERSION))),
            Map.entry("photovoltaic", List.of(new Term("photovoltaic", "solar cell", "SYNONYM", SOURCE, VERSION))),
            Map.entry("粘结剂", List.of(new Term("粘结剂", "binder", "TRANSLATION", SOURCE, VERSION))),
            Map.entry("锂电池", List.of(new Term("锂电池", "lithium battery", "TRANSLATION", SOURCE, VERSION))));

    public record Term(String original, String expansion, String relation, String source, String version) { }
    public record Plan(String version, String originalQuery, String retrievalQuery, List<Term> expansions) { }

    public Plan expand(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Query required");
        var syntax = new PatentQuerySyntax().parse(query, java.util.Set.of("TITLE"));
        return expand(query, syntax, false);
    }

    public Plan expandFields(String query, java.util.Set<String> admittedFields) {
        return expand(query, new PatentQuerySyntax().parseFields(query, admittedFields), true);
    }

    private Plan expand(String query, PatentQuerySyntax.Plan syntax, boolean fieldAware) {
        String normalized = Normalizer.normalize(syntax.positiveText(), Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).strip();
        List<Term> expansions = new ArrayList<>();
        for (var entry : TERMS.entrySet())
            if (containsTerm(normalized, entry.getKey())) expansions.addAll(entry.getValue());
        expansions.sort(java.util.Comparator.comparing(Term::original).thenComparing(Term::expansion));
        LinkedHashSet<String> additions = new LinkedHashSet<>();
        expansions.forEach(term -> additions.add(term.expansion()));
        String retrieval = additions.isEmpty() ? normalized
                : normalized + " " + String.join(" ", additions);
        if (retrieval.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4000)
            throw new PatentQuerySyntax.InvalidQuery("Expanded query exceeds persisted query capacity");
        return new Plan(syntax.explicit() || fieldAware ? VERSION + "+" + syntax.version() : VERSION,
                query, retrieval, List.copyOf(expansions));
    }

    private static boolean containsTerm(String query, String term) {
        if (term.codePoints().anyMatch(code -> Character.UnicodeScript.of(code)
                == Character.UnicodeScript.HAN)) return query.contains(term);
        return List.of(query.split("[^\\p{L}\\p{N}-]+", -1)).contains(term);
    }
}
