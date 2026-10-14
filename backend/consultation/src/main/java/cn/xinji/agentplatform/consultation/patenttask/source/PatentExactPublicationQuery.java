package cn.xinji.agentplatform.consultation.patenttask.source;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact publication identity is a deterministic retrieval signal, not title similarity. */
public final class PatentExactPublicationQuery {
    public PatentBm25fQuery.Result search(List<PatentLexicalQuery.Document> documents,
            PatentQuerySyntax.Plan plan, int maxResults) {
        if (!plan.exactNumber() || maxResults < 1) throw new IllegalArgumentException("Exact identity plan required");
        var matches = documents.stream().filter(plan::matches)
                .sorted(Comparator.comparing(PatentLexicalQuery.Document::sourceId)
                        .thenComparing(PatentLexicalQuery.Document::documentId)
                        .thenComparing(PatentLexicalQuery.Document::documentVersion))
                .map(d -> new PatentBm25fQuery.Match(d.sourceId(), d.documentId(), d.documentVersion(),
                        d.sourceVersion(), d.rawSha(), d.locatorRef(), Set.of("DOCUMENT_ID"),
                        List.of(d.documentId()), 1.0, Map.of("DOCUMENT_ID", 1.0))).toList();
        return new PatentBm25fQuery.Result("EXACT_PUBLICATION/v1", plan.positiveText(),
                List.of(plan.positiveText()), matches.stream().limit(maxResults).toList(),
                matches.size(), matches.size() > maxResults);
    }
}
