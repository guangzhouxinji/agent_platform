package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.time.*;
import java.util.*;

/** Format-neutral content/citation facts; neither source admission nor current authorization. */
final class ProductCollectedPatentContent {
    record Entry(String id, String version, String rawRef, String sha, ProductCapturedPatentFields.Content content,
                 LocalDate published, List<String> ipc, List<String> cpc) {
        String locator() { return content.anchor(); }
    }
    record Capture(ProductCollectedPatentTitles.CaptureSnapshot source, String url, Instant collectedAt,
                   Map<String,Entry> entries) {
        List<PatentLexicalQuery.Document> documents(ProductPatentSearchFilters filters) {
            return entries.values().stream().filter(e -> filters.matches(e.published(), e.ipc(), e.cpc()))
                    .map(e -> new PatentLexicalQuery.Document(source.sourceId(), e.id(), e.version(),
                            source.sourceVersion(), e.sha(), e.locator(), e.content().values())).toList();
        }
        ProductPatentSearchCitations.Citation citation(ProductPatentSearchExecution.Member member) {
            return citation(member,false);
        }
        ProductPatentSearchCitations.Citation citation(ProductPatentSearchExecution.Member member, boolean includeFields) {
            Entry entry = entries.get(member.documentId());
            if (entry == null || !source.sourceId().equals(member.sourceId())
                    || !source.sourceVersion().equals(member.sourceVersion())
                    || !entry.version().equals(member.documentVersion()) || !entry.sha().equals(member.rawSha())
                    || !entry.locator().equals(member.locatorRef())
                    || (includeFields && member.matchFields().stream().anyMatch(field ->
                            !entry.content().values().containsKey(field) && !"DOCUMENT_ID".equals(field))))
                throw new IllegalStateException("Native citation does not match its original");
            return new ProductPatentSearchCitations.Citation(member.memberId(), member.sourceId(), member.documentId(),
                    member.documentVersion(), member.sourceVersion(), url, url, collectedAt, entry.sha(), entry.locator(),
                    includeFields ? entry.content().locators() : Map.of());
        }
    }
}
