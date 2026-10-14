package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;

/** Prepares both recall paths from the same admitted, hard-constrained document pool. */
public final class PatentQueryRetrieval {
    public record Prepared(PatentQueryExpansion.Plan expansion,
                           List<PatentLexicalQuery.Document> documents, PatentBm25fQuery.Result lexical) { }
    public Prepared prepare(String query, List<PatentLexicalQuery.Document> documents, int bound) {
        var syntax = new PatentQuerySyntax().parse(query, Set.of("TITLE"));
        var plan = new PatentQueryExpansion().expand(query);
        var admitted = documents.stream().filter(syntax::matches).toList();
        var lexical = syntax.exactNumber()
                ? new PatentExactPublicationQuery().search(admitted, syntax, bound)
                : new PatentBm25fQuery().search(admitted, plan.retrievalQuery(), Set.of("TITLE"), bound);
        return new Prepared(plan, admitted, lexical);
    }

    /** Final field-aware core; it does not grant source rights or authorize its caller. */
    public Prepared prepareFields(String query, List<PatentLexicalQuery.Document> documents,
            Set<String> admittedFields, int bound) {
        var syntax = new PatentQuerySyntax().parseFields(query, admittedFields);
        var plan = new PatentQueryExpansion().expandFields(query, admittedFields);
        if (documents == null || bound < 1) throw new IllegalArgumentException("Documents and bound required");
        Set<String> scope = Set.copyOf(admittedFields);
        Set<String> keys = new HashSet<>();
        var projected = documents.stream().map(document -> project(document, scope, keys)).toList();
        var admitted = projected.stream().filter(syntax::matches).toList();
        var lexical = syntax.exactNumber()
                ? new PatentExactPublicationQuery().search(admitted, syntax, bound)
                : new PatentBm25fQuery().search(admitted, plan.retrievalQuery(), scope, bound);
        return new Prepared(plan, admitted, lexical);
    }

    private static PatentLexicalQuery.Document project(PatentLexicalQuery.Document document,
            Set<String> scope, Set<String> keys) {
        if (document == null || blank(document.sourceId()) || blank(document.documentId())
                || blank(document.documentVersion()) || blank(document.sourceVersion())
                || document.rawSha() == null || !document.rawSha().matches("[0-9a-fA-F]{64}")
                || blank(document.locatorRef()) || document.fields() == null)
            throw new IllegalArgumentException("Versioned source document required");
        if (!keys.add(document.sourceId() + '\0' + document.documentId() + '\0' + document.documentVersion()))
            throw new IllegalArgumentException("Duplicate source document version");
        Map<String,String> selected = new LinkedHashMap<>();
        for (String field : scope.stream().sorted().toList()) {
            String value = document.fields().get(field);
            if (value != null) selected.put(field, value);
        }
        return new PatentLexicalQuery.Document(document.sourceId(), document.documentId(), document.documentVersion(),
                document.sourceVersion(), document.rawSha(), document.locatorRef(), Map.copyOf(selected));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
