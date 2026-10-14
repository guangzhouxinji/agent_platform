package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reconstructs original encodings from historical bytes; never writes or activates a source. */
final class ProductLegacySearchRecovery {
    private record Query(String original,String actual,String plan,String filters) { }
    private final JdbcTemplate db;
    ProductLegacySearchRecovery(JdbcTemplate db) { this.db=db; }

    String digest(ProductPatentSearchExecution.Receipt receipt,
            List<ProductPatentSearchExecution.Member> members,
            Map<String,Map<String,PatentLexicalQuery.Document>> originals,
            Map<String,Map<String,PatentLexicalQuery.Document>> completeOriginals) {
        var queries=db.query("SELECT original_query,actual_query,query_plan_version,filters_json "
                +"FROM con4_search_attempt WHERE attempt_id=?",(r,n)->
                new Query(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),receipt.attemptId());
        if(queries.size()!=1) invalid();
        var query=queries.getFirst();
        // The caller reloads the historical source bytes with the recorded metadata filters.
        List<PatentLexicalQuery.Document> documents=originals.values().stream()
                .flatMap(rows->rows.values().stream()).toList();
        Map<String,PatentBm25fQuery.Match> lexical=new HashMap<>();
        String semanticQuery=query.actual();
        if("LEXICAL_AND/v1".equals(receipt.methodVersion())) {
            if(query.plan()!=null || query.filters()!=null) invalid();
            var result=new PatentLexicalQuery().search(documents,query.actual(),Set.of("TITLE"),1000);
            if(result.truncated()!=receipt.truncated() || result.matches().size()!=members.size()) invalid();
            for(int i=0;i<members.size();i++) {
                var expected=result.matches().get(i);var actual=members.get(i);
                if(!expected.sourceId().equals(actual.sourceId())
                        || !expected.documentId().equals(actual.documentId())
                        || !expected.documentVersion().equals(actual.documentVersion())) invalid();
            }
        } else {
            PatentBm25fQuery.Result result;
            if(query.plan()!=null) {
                var prepared=new PatentQueryRetrieval().prepare(query.original(),documents,1000);
                if(!prepared.expansion().version().equals(query.plan())
                        || !prepared.lexical().normalizedQuery().equals(query.actual())) invalid();
                result=prepared.lexical();
                semanticQuery=prepared.expansion().retrievalQuery();
            } else result=new PatentBm25fQuery().search(documents,query.actual(),Set.of("TITLE"),1000);
            for(var match:result.matches())
                if(lexical.put(ProductLegacySearchDigest.key(match),match)!=null) invalid();
        }
        Map<String,Double> semantic=new HashMap<>();
        if(members.stream().anyMatch(member->member.semanticScore()!=null)) {
            for(var source:receipt.sourceOutcomes()) if("SUCCEEDED".equals(source.status()))
                semantic.putAll(semantic(receipt,source.sourceId(),semanticQuery,
                        completeOriginals.get(source.sourceId()),members));
        }
        return ProductLegacySearchDigest.recompute(receipt.methodVersion(),receipt.truncated(),members,lexical,semantic);
    }

    private Map<String,Double> semantic(ProductPatentSearchExecution.Receipt receipt,String source,
            String query,Map<String,PatentLexicalQuery.Document> documents,
            List<ProductPatentSearchExecution.Member> members) {
        record Build(String model,String sha,int count) { }
        var builds=db.query("SELECT DISTINCT b.model_version,b.build_sha,b.document_count FROM con4_vector_build b JOIN con4_search_source s "
                +"ON s.source_id=b.source_id AND s.contract_version=b.contract_version "
                +"AND s.decision_id=b.decision_id AND s.source_epoch=b.source_epoch "
                +"JOIN con4_search_attempt a ON a.attempt_id=s.attempt_id "
                +"WHERE s.attempt_id=? AND s.source_id=? AND b.field_name='TITLE' "
                +"AND b.completed_at<=a.created_at",(r,n)->new Build(r.getString(1),r.getString(2),r.getInt(3)),receipt.attemptId(),source);
        if(builds.size()!=1) invalid();
        String version=builds.getFirst().model();
        var models=db.query("SELECT provider,model_id,model_revision,model_sha,tokenizer_sha," 
                +"vector_dimension,max_tokens,normalization,license_id FROM con4_embedding_model "
                +"WHERE model_version=?",(r,n)->new PatentEmbeddingProvider.Model(r.getString(1),r.getString(2),
                r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getInt(7),r.getString(8),r.getString(9)),version);
        if(models.size()!=1) invalid();
        var model=models.getFirst();
        if(!version.equals(model.modelId()+"@"+model.modelRevision()+"/"+model.normalization())) invalid();
        var ordered=documents.values().stream().sorted(Comparator.comparing(PatentLexicalQuery.Document::documentId)
                .thenComparing(PatentLexicalQuery.Document::documentVersion)).toList();
        if(ordered.isEmpty() || builds.getFirst().count()!=ordered.size()) invalid();
        var vectors=ProductSemanticVectors.load(db,source,ordered.getFirst().sourceVersion(),ordered,version,model.dimension());
        String buildSha=ProductPatentSearchEvidenceCodec.digestValues(ordered.stream()
                .map(document->ProductVectorCodec.sha(ProductVectorCodec.encode(vectors.get(document.documentId()),model.dimension())))
                .toArray(String[]::new));
        if(!builds.getFirst().sha().equals(buildSha)) invalid();
        try(var provider=new OnnxPatentEmbeddingProvider(requiredPath("PRODUCT_SOURCE_SEMANTIC_MODEL_PATH"),
                requiredPath("PRODUCT_SOURCE_SEMANTIC_TOKENIZER_PATH"),model,
                ProductOnnxSessionOptions.historicalAutomatic())) {
            float[] queryVector=provider.embed(List.of(query)).getFirst();
            Map<String,Double> result=new HashMap<>();
            for(var member:members) if(source.equals(member.sourceId()) && member.semanticScore()!=null) {
                var document=documents.get(member.documentId());
                if(document==null) invalid();
                float[] vector=vectors.get(member.documentId());
                if(vector==null) invalid();
                double score=0;for(int i=0;i<queryVector.length;i++) score+=queryVector[i]*vector[i];
                result.put(ProductLegacySearchDigest.key(member),Math.max(-1,Math.min(1,score)));
            }
            return Map.copyOf(result);
        }
    }
    private static Path requiredPath(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank()) throw new IllegalStateException("Historical model artifact required: "+key);
        return Path.of(value);
    }
    private static void invalid() { throw new IllegalStateException("Historical pre-storage evidence inconsistent"); }
}
