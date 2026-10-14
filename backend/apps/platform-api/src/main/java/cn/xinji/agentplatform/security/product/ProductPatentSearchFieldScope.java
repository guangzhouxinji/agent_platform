package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQuerySyntax;
import java.util.*;

/** Confirmed text fields and actual filter fields are distinct, exact parts of one product request. */
record ProductPatentSearchFieldScope(Set<String> textFields, Set<String> requestedFields) {
    private static final Set<String> TEXT = Set.of("TITLE","ABSTRACT","CLAIMS","DESCRIPTION");
    ProductPatentSearchFieldScope {
        ProductCapturedPatentFields.validate(textFields);
        textFields = Collections.unmodifiableSet(new TreeSet<>(textFields));
        requestedFields = Collections.unmodifiableSet(new TreeSet<>(requestedFields));
        if (!requestedFields.containsAll(textFields)) throw new IllegalArgumentException("Text fields must be requested");
    }
    static ProductPatentSearchFieldScope from(List<String> requested, ProductPatentSearchFilters filters) {
        if (requested == null || requested.isEmpty() || filters == null
                || requested.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(requested).size()!=requested.size())
            throw new IllegalArgumentException("Unique explicit search fields required");
        Set<String> fields = new HashSet<>();
        for (String field : requested) if (TEXT.contains(field)) fields.add(field);
        ProductCapturedPatentFields.validate(fields);
        Set<String> expected = new HashSet<>(fields); expected.addAll(filters.requiredSourceFields());
        if (!expected.equals(new HashSet<>(requested)))
            throw new IllegalArgumentException("Search fields must exactly match selected text and actual filters");
        return new ProductPatentSearchFieldScope(fields,expected);
    }
    boolean legacyTitle() { return textFields.equals(Set.of("TITLE")); }
    PatentQuerySyntax.Plan syntax(String query) {
        return legacyTitle() ? new PatentQuerySyntax().parse(query,textFields)
                : new PatentQuerySyntax().parseFields(query,textFields);
    }
    PatentQueryExpansion.Plan expansion(String query) {
        return legacyTitle() ? new PatentQueryExpansion().expand(query)
                : new PatentQueryExpansion().expandFields(query,textFields);
    }
    String scopeSha(ProductPatentSearchEvidenceCodec codec, List<String> sources,
            String filterVersion, String filtersJson) {
        if (sources == null || sources.isEmpty() || new HashSet<>(sources).size()!=sources.size()
                || sources.stream().anyMatch(source -> source == null || source.isBlank()))
            throw new IllegalArgumentException("Unique explicit sources required");
        if (legacyTitle()) return codec.digest(String.join("\u0000",sources),"TITLE",filterVersion,filtersJson);
        return codec.digest("SEARCH_FIELD_SCOPE/v2",codec.json(new TreeSet<>(sources)),
                codec.json(textFields),filterVersion,filtersJson);
    }
}
