package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** One query per immutable source/model snapshot; no cross-request or authorization cache. */
final class ProductSemanticVectors {
    private record Key(String documentId,String documentVersion) { }
    private record Stored(String rawSha,String locator,String vectorSha,byte[] bytes) { }
    static Map<String,float[]> load(JdbcTemplate db, ProductSourceAvailability.Active active,
            List<PatentLexicalQuery.Document> documents,String modelVersion,int dimension) {
        return load(db,active.sourceId(),active.sourceVersion(),documents,modelVersion,dimension);
    }
    static Map<String,float[]> load(JdbcTemplate db, String sourceId,String sourceVersion,
            List<PatentLexicalQuery.Document> documents,String modelVersion,int dimension) {
        return loadField(db,sourceId,sourceVersion,documents,modelVersion,dimension,"TITLE");
    }
    static Map<String,float[]> loadField(JdbcTemplate db, String sourceId,String sourceVersion,
            List<PatentLexicalQuery.Document> documents,String modelVersion,int dimension,String field) {
        if (field == null) throw new IllegalArgumentException("Exact semantic field required");
        ProductCapturedPatentFields.validate(Set.of(field));
        if (documents == null || sourceId == null || sourceId.isBlank() || sourceVersion == null
                || sourceVersion.isBlank() || modelVersion == null || modelVersion.isBlank() || dimension < 1)
            throw new IllegalArgumentException("Exact semantic snapshot required");
        for (var document : documents)
            if (document == null || document.fields() == null || !document.fields().keySet().equals(Set.of(field))
                    || document.fields().get(field) == null || document.fields().get(field).isBlank())
                throw new IllegalArgumentException("Single selected semantic field projection required");
        Map<Key,Stored> rows=new HashMap<>();
        db.query("SELECT b.document_id,b.document_version,b.raw_sha,b.locator_ref,b.vector_sha,v.vector_data "
                + "FROM con4_document_vector_binding b JOIN con4_document_vector v "
                + "ON v.source_id=b.source_id AND v.document_id=b.document_id "
                + "AND v.document_version=b.document_version AND v.field_name=b.field_name "
                + "AND v.model_version=b.model_version AND v.vector_sha=b.vector_sha "
                + "WHERE b.source_id=? AND b.source_version=? AND b.model_version=? AND b.field_name=?",
                (org.springframework.jdbc.core.RowCallbackHandler) row->{
                    var key=new Key(row.getString(1),row.getString(2));
                    if(rows.put(key,new Stored(row.getString(3),row.getString(4),
                            row.getString(5),row.getBytes(6)))!=null)
                        throw new ProductSemanticIndex.Unavailable("Semantic index contains duplicate bindings");
                },sourceId,sourceVersion,modelVersion,field);
        Map<String,float[]> vectors=new HashMap<>();
        for(var document:documents) {
            var stored=rows.get(new Key(document.documentId(),document.documentVersion()));
            if(!sourceId.equals(document.sourceId())
                    || !sourceVersion.equals(document.sourceVersion()) || stored==null
                    || !stored.rawSha().equals(document.rawSha()) || !stored.locator().equals(document.locatorRef()))
                throw new ProductSemanticIndex.Unavailable("Semantic index is incomplete or stale");
            if(stored.bytes()==null || !ProductVectorCodec.sha(stored.bytes()).equals(stored.vectorSha()))
                throw new ProductSemanticIndex.Unavailable("Semantic vector digest changed");
            try {
                float[] vector=ProductVectorCodec.decode(stored.bytes(),dimension);
                ProductVectorCodec.encode(vector,dimension);
                if(vectors.put(document.documentId(),vector)!=null)
                    throw new IllegalStateException("Duplicate source document identity");
            } catch(IllegalArgumentException|IllegalStateException failure) {
                throw new ProductSemanticIndex.Unavailable("Semantic vector values changed",failure);
            }
        }
        return Map.copyOf(vectors);
    }
    private ProductSemanticVectors() { }
}
