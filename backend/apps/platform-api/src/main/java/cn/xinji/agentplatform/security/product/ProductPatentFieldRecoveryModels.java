package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies historical model/vector snapshots, never re-enables sources or replaces recorded machine output. */
final class ProductPatentFieldRecoveryModels {
    private record Build(String sha,int count) { }
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec codec;
    ProductPatentFieldRecoveryModels(JdbcTemplate db,ProductPatentSearchEvidenceCodec codec) { this.db=db;this.codec=codec; }
    void verify(ProductPatentSearchExecution.Receipt receipt,ProductPatentFieldResultEvidence.Bundle bundle,
            Map<String,PatentFieldSemanticFusion.DocumentEvidence> originals) {
        for(var field:bundle.models().entrySet()) {
            String version=field.getValue();
            var models=db.query("SELECT provider,model_id,model_revision,model_sha,tokenizer_sha,vector_dimension,"
                    + "max_tokens,normalization,license_id FROM con4_embedding_model WHERE model_version=?",
                    (rs,n)->new PatentEmbeddingProvider.Model(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                            rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getString(8),rs.getString(9)),version);
            if(models.size()!=1) invalid(); var model=models.getFirst();
            String expected=model.modelId()+"@"+model.modelRevision()+"/"+model.normalization();
            if(!"TITLE".equals(field.getKey())) expected+="/"+PatentEmbeddingProvider.COMPLETE_REPRESENTATION_VERSION
                    +"/W"+model.maxTokens()+"/D"+model.dimension();
            if(!expected.equals(version)) invalid();
            for(var source:receipt.sourceOutcomes()) if("SUCCEEDED".equals(source.status())) {
                var builds=db.query("SELECT DISTINCT b.build_sha,b.document_count FROM con4_vector_build b "
                        + "JOIN con4_search_source s ON s.source_id=b.source_id AND s.contract_version=b.contract_version "
                        + "AND s.decision_id=b.decision_id AND s.source_epoch=b.source_epoch "
                        + "JOIN con4_search_attempt a ON a.attempt_id=s.attempt_id WHERE s.attempt_id=? AND s.source_id=? "
                        + "AND b.field_name=? AND b.model_version=? AND b.completed_at<=a.created_at",
                        (rs,n)->new Build(rs.getString(1),rs.getInt(2)),receipt.attemptId(),source.sourceId(),field.getKey(),version);
                if(builds.size()!=1) invalid();
                var documents=originals.values().stream().filter(doc->doc.document().sourceId().equals(source.sourceId()))
                        .map(doc->new PatentLexicalQuery.Document(doc.document().sourceId(),doc.document().documentId(),
                                doc.document().documentVersion(),doc.document().sourceVersion(),doc.document().rawSha(),
                                doc.fieldLocators().get(field.getKey()),Map.of(field.getKey(),doc.document().fields().get(field.getKey()))))
                        .sorted(Comparator.comparing(PatentLexicalQuery.Document::documentId)
                                .thenComparing(PatentLexicalQuery.Document::documentVersion)).toList();
                if(documents.isEmpty() || builds.getFirst().count()!=documents.size()) invalid();
                var vectors=ProductSemanticVectors.loadField(db,source.sourceId(),documents.getFirst().sourceVersion(),
                        documents,version,model.dimension(),field.getKey());
                String sha=codec.digest(documents.stream().map(doc->ProductVectorCodec.sha(
                        ProductVectorCodec.encode(vectors.get(doc.documentId()),model.dimension()))).toArray(String[]::new));
                if(!builds.getFirst().sha().equals(sha)) invalid();
            }
        }
    }
    private static void invalid() { throw new IllegalStateException("Restored field model/vector evidence inconsistent"); }
}
