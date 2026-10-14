package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;

/** Final field-ranking chain; authorization/publication and persisted evidence remain with the product executor. */
final class ProductPatentFieldSearchRanking {
    record Result(ProductPatentSearchRanking.Result ranking, PatentQueryRetrieval.Prepared queryPlan,
                  Map<String,PatentFieldSemanticFusion.DocumentEvidence> originals,
                  PatentFieldSemanticFusion.Result semanticEvidence) {
        Result { originals = Map.copyOf(originals); }
    }
    private static final List<String> ORDER = List.of("TITLE","ABSTRACT","CLAIMS","DESCRIPTION");
    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductSemanticIndex semantic;
    private final PatentReranker reranker;
    private final int maxResults;
    ProductPatentFieldSearchRanking(ProductPatentSearchEvidenceCodec evidence, ProductSemanticIndex semantic,
            PatentReranker reranker, int maxResults) {
        if (evidence == null || maxResults < 1 || maxResults > 1000)
            throw new IllegalArgumentException("Final field ranking codec and bound required");
        this.evidence = evidence; this.semantic = semantic; this.reranker = reranker; this.maxResults = maxResults;
    }
    Result rank(List<ProductSourceAvailability.Active> sources, String query,
            List<PatentFieldSemanticFusion.DocumentEvidence> pool, Set<String> selected) {
        ProductCapturedPatentFields.validate(selected); var fields = Set.copyOf(selected);
        if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("Current successful source snapshots required");
        Map<String,ProductSourceAvailability.Active> snapshots = new HashMap<>();
        for (var source : sources) {
            if (source == null || snapshots.put(source.sourceId(),source) != null)
                throw new IllegalArgumentException("Unique source snapshots required");
        }
        var allOriginals = PatentFieldSemanticFusion.validateDocuments(pool,fields);
        for (var original : allOriginals.values()) {
            var doc = original.document(); var active = snapshots.get(doc.sourceId());
            if (active == null || !active.sourceVersion().equals(doc.sourceVersion()))
                throw new IllegalStateException("Field original differs from current source snapshot");
        }
        var prepared = new PatentQueryRetrieval().prepareFields(query,pool.stream().map(
                PatentFieldSemanticFusion.DocumentEvidence::document).toList(),fields,1000);
        Map<String,PatentFieldSemanticFusion.DocumentEvidence> originals = new TreeMap<>();
        for (var doc : prepared.documents()) originals.put(key(doc),allOriginals.get(key(doc)));
        var lexical = prepared.lexical();
        if ("EXACT_PUBLICATION/v1".equals(lexical.methodVersion())) return new Result(
                new ProductPatentSearchRanking.Result(evidence.members(lexical.matches().stream().limit(maxResults).toList()),
                        lexical.candidateCount(),lexical.normalizedQuery(),lexical.truncated() || lexical.candidateCount()>maxResults,
                        "EXACT_PUBLICATION/v1","EXACT_PUBLICATION_DOCUMENT_KEY/v1",null,null,
                        new PatentRetrievalConfidence().lexicalOnly(lexical.matches().isEmpty())),prepared,originals,null);
        if(semantic==null || reranker==null) throw new IllegalStateException("Final field ranking models required");
        Map<String,String> models = new TreeMap<>(); Map<String,List<PatentFieldSemanticFusion.FieldScore>> responses = new TreeMap<>();
        for (String field : new TreeSet<>(fields)) {
            String model = semantic.fieldModelVersion(field); models.put(field,model);
            List<PatentFieldSemanticFusion.FieldScore> scores = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (var active : sources) {
                var matches = semantic.searchField(active.sourceId(),field,prepared.expansion().retrievalQuery(),1000);
                if (matches == null || matches.size()>1000) throw new IllegalStateException("Bounded field semantic response missing");
                for (var match : matches) {
                    if (match == null || !active.sourceId().equals(match.sourceId())
                            || !active.sourceVersion().equals(match.sourceVersion()))
                        throw new IllegalStateException("Field semantic source snapshot differs");
                    String id = match.sourceId()+'\0'+match.documentId()+'\0'+match.documentVersion();
                    if (!seen.add(id)) throw new IllegalStateException("Duplicate field semantic document response");
                    if (!originals.containsKey(id)) {
                        boolean changed = originals.values().stream().anyMatch(original -> original.document().sourceId()
                                .equals(match.sourceId()) && original.document().documentId().equals(match.documentId()));
                        if (changed) throw new IllegalStateException("Field semantic document version differs");
                        continue; // A valid source document outside the hard-filtered pool cannot be ranked.
                    }
                    scores.add(new PatentFieldSemanticFusion.FieldScore(match.sourceId(),match.documentId(),match.documentVersion(),
                            match.sourceVersion(),match.rawSha(),field,match.locatorRef(),model,match.semanticScore()));
                }
            }
            scores.sort(Comparator.comparingDouble(PatentFieldSemanticFusion.FieldScore::cosine).reversed()
                    .thenComparing(PatentFieldSemanticFusion.FieldScore::sourceId)
                    .thenComparing(PatentFieldSemanticFusion.FieldScore::documentId)
                    .thenComparing(PatentFieldSemanticFusion.FieldScore::documentVersion));
            if (scores.size()>1000) scores.subList(1000,scores.size()).clear();
            responses.put(field,List.copyOf(scores));
        }
        var fused = new PatentFieldSemanticFusion().fuse(new ArrayList<>(originals.values()),fields,models,responses,1000);
        var confidence = new PatentRetrievalConfidence.Assessment(PatentRetrievalConfidence.Status.LOW_CONFIDENCE,
                "FIELD_CALIBRATION_UNAVAILABLE/v1","FIELD_CALIBRATION_UNAVAILABLE");
        if (lexical.matches().isEmpty() && fused.candidates().isEmpty()) return new Result(
                new ProductPatentSearchRanking.Result(List.of(),0,lexical.normalizedQuery(),lexical.truncated() || fused.truncated(),
                        "HYBRID_FIELD_RRF/v1","FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1",null,null,
                        new PatentRetrievalConfidence().assess(List.of())),prepared,originals,fused);
        var hybrid = new PatentHybridQuery().fuseFields(lexical,fused.candidates().stream().map(
                PatentFieldSemanticFusion.Candidate::match).toList(),Math.max(maxResults,200));
        Map<String,String> texts = new TreeMap<>();
        for (var candidate : hybrid.matches()) {
            String id = PatentRerankedQuery.key(candidate); var original = originals.get(id);
            if (original == null) throw new IllegalStateException("Complete rerank original missing");
            StringBuilder text = new StringBuilder();
            for (String field : ORDER) if (fields.contains(field))
                text.append(field).append('\n').append(original.document().fields().get(field)).append('\n');
            texts.put(id,text.toString());
        }
        String rerankVersion=PatentFieldRerankedQuery.modelVersion(reranker.model());
        try {
            var ranked = new PatentFieldRerankedQuery().rerank(prepared.expansion().retrievalQuery(),hybrid,texts,reranker,maxResults);
            return new Result(new ProductPatentSearchRanking.Result(evidence.rerankedMembers(ranked.matches()),hybrid.candidateCount(),
                    hybrid.normalizedQuery(),lexical.truncated() || fused.truncated() || ranked.truncated()
                            || prepared.documents().size()>1000,ranked.methodVersion(),
                    "COMPLETE_CE_SCORE_DESC_FIELD_RRF_DESC_DOCUMENT_KEY/v1",ranked.modelVersion(),"SUCCEEDED",confidence),
                    prepared,originals,fused);
        } catch (RuntimeException unavailable) {
            return new Result(new ProductPatentSearchRanking.Result(evidence.hybridMembers(hybrid.matches().stream().limit(maxResults).toList()),
                    hybrid.candidateCount(),hybrid.normalizedQuery(),lexical.truncated() || fused.truncated()
                            || hybrid.truncated() || hybrid.candidateCount()>maxResults || prepared.documents().size()>1000,
                    hybrid.methodVersion(),"FIELD_RRF_SCORE_DESC_LEXICAL_SCORE_DESC_DOCUMENT_KEY/v1",rerankVersion,"RERANK_FAILED",confidence),prepared,originals,fused);
        }
    }
    private static String key(PatentLexicalQuery.Document doc) {
        return doc.sourceId()+'\0'+doc.documentId()+'\0'+doc.documentVersion();
    }
}
