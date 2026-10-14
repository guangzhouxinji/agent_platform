package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRerankedQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRetrievalConfidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProductPatentSearchRanking {
    record Result(List<ProductPatentSearchExecution.Member> members, int candidateCount,
                  String normalizedQuery, boolean truncated, String method, String ordering,
                  String rerankModelVersion, String rerankStatus,
                  PatentRetrievalConfidence.Assessment confidence) { }

    private static final String LEXICAL_ORDERING = "BM25F_SCORE_DESC_DOCUMENT_KEY/v1";
    private static final String HYBRID_ORDERING = "RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1";
    private static final String RERANK_ORDERING =
            "CROSS_ENCODER_SCORE_DESC_RRF_SCORE_DESC_DOCUMENT_KEY/v1";

    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductSemanticIndex semantic;
    private final PatentReranker reranker;
    private final int maxResults;
    private final PatentRetrievalConfidence confidence = new PatentRetrievalConfidence();

    ProductPatentSearchRanking(ProductPatentSearchEvidenceCodec evidence,
            ProductSemanticIndex semantic, PatentReranker reranker, int maxResults) {
        this.evidence = evidence;
        this.semantic = semantic;
        this.reranker = reranker;
        this.maxResults = maxResults;
    }

    Result rank(List<String> sources, PatentQueryExpansion.Plan queryPlan,
            List<PatentLexicalQuery.Document> documents, PatentBm25fQuery.Result lexical) {
        if (semantic == null || lexical.methodVersion().equals("EXACT_PUBLICATION/v1"))
            return new Result(evidence.members(lexical.matches().stream().limit(maxResults).toList()),
                    lexical.candidateCount(), lexical.normalizedQuery(),
                    lexical.candidateCount() > maxResults, lexical.methodVersion(),
                    lexical.methodVersion().equals("EXACT_PUBLICATION/v1")
                            ? "EXACT_PUBLICATION_DOCUMENT_KEY/v1" : LEXICAL_ORDERING, null, null,
                    confidence.lexicalOnly(lexical.matches().isEmpty()));
        List<PatentHybridQuery.SemanticMatch> semanticMatches = new ArrayList<>();
        Set<String> allowedDocuments = documents.stream().map(document -> document.sourceId() + '\0'
                + document.documentId() + '\0' + document.documentVersion()).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        for (String sourceId : sources)
            for (var match : semantic.search(sourceId, queryPlan.retrievalQuery(), 1000))
                if (allowedDocuments.contains(match.sourceId() + '\0' + match.documentId() + '\0'
                        + match.documentVersion()))
                    semanticMatches.add(new PatentHybridQuery.SemanticMatch(match.sourceId(),
                            match.documentId(), match.documentVersion(), match.sourceVersion(),
                            match.rawSha(), match.locatorRef(), match.semanticScore()));
        semanticMatches.sort(java.util.Comparator.comparingDouble(PatentHybridQuery.SemanticMatch::semanticScore)
                .reversed().thenComparing(PatentHybridQuery.SemanticMatch::sourceId)
                .thenComparing(PatentHybridQuery.SemanticMatch::documentId)
                .thenComparing(PatentHybridQuery.SemanticMatch::documentVersion));
        if(semanticMatches.size()>1000) semanticMatches.subList(1000,semanticMatches.size()).clear();
        if (lexical.matches().isEmpty() && semanticMatches.isEmpty()) {
            String modelVersion = reranker == null ? null
                    : reranker.model().modelId() + "@" + reranker.model().revision();
            return new Result(List.of(), 0, lexical.normalizedQuery(), false,
                    reranker == null ? "HYBRID_RRF/v1" : PatentRerankedQuery.METHOD_VERSION,
                    reranker == null ? HYBRID_ORDERING : RERANK_ORDERING,
                    modelVersion, reranker == null ? null : "SUCCEEDED",
                    confidence.assess(List.of()));
        }
        int candidateBound = reranker == null ? maxResults : Math.max(maxResults, 200);
        var hybrid = new PatentHybridQuery().fuse(lexical, semanticMatches, candidateBound);
        if (reranker == null)
            return new Result(evidence.hybridMembers(hybrid.matches()), hybrid.candidateCount(),
                    hybrid.normalizedQuery(), hybrid.truncated(), hybrid.methodVersion(),
                    HYBRID_ORDERING, null, null, confidence.assess(hybrid.matches()));
        String modelVersion = reranker.model().modelId() + "@" + reranker.model().revision();
        try {
            Map<String, String> texts = new HashMap<>();
            for (var document : documents)
                texts.put(document.sourceId() + '\0' + document.documentId() + '\0'
                        + document.documentVersion(), document.fields().get("TITLE"));
            Map<String, String> selected = new HashMap<>();
            for (var candidate : hybrid.matches())
                selected.put(PatentRerankedQuery.key(candidate),
                        texts.get(PatentRerankedQuery.key(candidate)));
            var ranked = new PatentRerankedQuery().rerank(queryPlan.retrievalQuery(),
                    hybrid, selected, reranker, semantic.model(), maxResults);
            return new Result(evidence.rerankedMembers(ranked.matches()), hybrid.candidateCount(),
                    hybrid.normalizedQuery(), ranked.truncated(), ranked.methodVersion(),
                    RERANK_ORDERING, modelVersion, "SUCCEEDED",
                    confidence.assess(ranked.matches().isEmpty() ? List.of() : hybrid.matches()));
        } catch (RuntimeException unavailable) {
            return new Result(evidence.hybridMembers(hybrid.matches().stream().limit(maxResults).toList()),
                    hybrid.candidateCount(), hybrid.normalizedQuery(),
                    hybrid.candidateCount() > maxResults, hybrid.methodVersion(),
                    HYBRID_ORDERING, modelVersion, "RERANK_FAILED", confidence.assess(hybrid.matches()));
        }
    }
}
