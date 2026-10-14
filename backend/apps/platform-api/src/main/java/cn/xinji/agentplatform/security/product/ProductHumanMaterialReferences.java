package cn.xinji.agentplatform.security.product;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads originals under a request's complete lock plan and returns no source body. */
final class ProductHumanMaterialReferences {
    record Selection(String sourceId, String documentId, String documentVersion, Set<String> fields) {
        Selection {
            if (blank(sourceId) || blank(documentId) || blank(documentVersion))
                throw new IllegalArgumentException("Exact material coordinates required");
            fields = java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(fields));
            ProductCapturedPatentFields.validate(fields);
        }
        ProductHumanRequestLocks.SourceUse use() { return new ProductHumanRequestLocks.SourceUse(sourceId, fields, Set.of()); }
    }
    record Reference(String type, String documentId, String documentVersion, String rawSha,
                     Map<String, String> fieldLocators, ProductHumanRequestLocks.SourceReference source) {
        Reference {
            fieldLocators = Map.copyOf(fieldLocators);
            if (!"SOURCE_DOCUMENT".equals(type) || blank(documentId) || blank(documentVersion)
                    || rawSha == null || !rawSha.matches("[a-f0-9]{64}") || fieldLocators.isEmpty()
                    || fieldLocators.values().stream().anyMatch(ProductHumanMaterialReferences::blank))
                throw new IllegalArgumentException("Authenticated material reference required");
            Objects.requireNonNull(source);
        }
    }
    private final ProductCollectedPatentTitles originals;
    ProductHumanMaterialReferences(ProductCollectedPatentTitles originals) { this.originals = Objects.requireNonNull(originals); }

    Reference load(ProductHumanTaskTransaction.Context context, ProductHumanRequestLocks.Plan plan,
                   Selection selection, Jwt jwt) {
        Reference before = read(context, plan, selection, jwt);
        Reference after = read(context, plan, selection, jwt);
        if (!before.equals(after)) throw unavailable();
        // Authority must still hold after the last actual original-byte read.
        if (!after.source().equals(plan.ordinarySource(context, selection.sourceId(), selection.fields(), jwt))) throw unavailable();
        return before;
    }

    private Reference read(ProductHumanTaskTransaction.Context context, ProductHumanRequestLocks.Plan plan,
                           Selection selection, Jwt jwt) {
        var source = plan.ordinarySource(context, selection.sourceId(), selection.fields(), jwt);
        var matches = originals.loadFieldEvidence(source.source(), ProductPatentSearchFilters.none(), selection.fields())
                .stream().filter(item -> selection.sourceId().equals(item.document().sourceId())
                    && selection.documentId().equals(item.document().documentId())
                    && selection.documentVersion().equals(item.document().documentVersion())).toList();
        if (matches.size() != 1) throw unavailable();
        var evidence = matches.getFirst(); var document = evidence.document();
        if (!source.source().sourceVersion().equals(document.sourceVersion())) throw unavailable();
        var locators = new TreeMap<String, String>();
        for (String field : selection.fields()) {
            String locator = evidence.fieldLocators().get(field);
            if (blank(locator) || !document.fields().containsKey(field)) throw unavailable();
            locators.put(field, locator);
        }
        return new Reference("SOURCE_DOCUMENT", document.documentId(), document.documentVersion(), document.rawSha(), locators, source);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Human material reference unavailable"); }
}
