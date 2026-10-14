package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Same-transaction immutable field evidence. This store grants neither Task nor Source access. */
final class ProductPatentFieldEvidencePersistence {
    private record Root(String attempt, String scopeSha, String method, String ordering, String rerankModel,
                        String rerankStatus, boolean truncated, String sha, String digestVersion) { }
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ProductPatentSearchEvidenceCodec codec;
    ProductPatentFieldEvidencePersistence(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db; this.mapper=mapper; codec=new ProductPatentSearchEvidenceCodec(mapper);
        db.queryForObject("SELECT COUNT(*) FROM con4_search_field_scope",Integer.class);
    }
    void save(String attemptId,String resultId,String scopeSha,ProductPatentFieldResultEvidence.Bundle bundle,
            List<ProductPatentSearchExecution.Member> members,boolean truncated) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Field evidence requires the fenced business transaction");
        ProductPatentFieldResultEvidence.validate(bundle,members);
        Root root=root(resultId);
        if(!root.attempt().equals(attemptId) || !root.scopeSha().equals(scopeSha) || root.truncated()!=truncated
                || !root.method().equals(bundle.method()) || !root.ordering().equals(bundle.ordering())
                || !Objects.equals(root.rerankModel(),bundle.rerankModel()) || !Objects.equals(root.rerankStatus(),bundle.rerankStatus())
                || !ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(root.digestVersion())
                || !root.sha().equals(ProductPatentFieldResultEvidence.digest(codec,bundle,members,truncated)))
            throw new IllegalStateException("Field evidence differs from committed result root");
        var stored=ProductPatentSearchPages.members(db,codec,resultId);
        if(!stored.stream().map(ProductPatentSearchExecution.Member::memberId).toList().equals(
                members.stream().map(ProductPatentSearchExecution.Member::memberId).toList())
                || !codec.resultDigest(stored,truncated).equals(codec.resultDigest(members,truncated)))
            throw new IllegalStateException("Field evidence differs from stored members");
        db.update("INSERT INTO con4_search_field_scope(attempt_id,result_set_id,digest_version,scope_version,"
                        + "text_fields_json,models_json,scope_sha) VALUES(?,?,?,?,?,?,?)",attemptId,resultId,
                ProductPatentFieldResultEvidence.DIGEST_VERSION,"SEARCH_FIELD_SCOPE/v2",codec.json(bundle.fields()),
                codec.json(bundle.models()),scopeSha);
        for(var member:members) {
            String json=codec.json(ProductPatentFieldResultEvidence.canonical(bundle.members().get(member.memberId())));
            db.update("INSERT INTO con4_result_field_evidence(result_set_id,member_id,evidence_version,fields_json,evidence_sha) "
                            + "VALUES(?,?,?,?,?)",resultId,member.memberId(),ProductPatentFieldResultEvidence.VERSION,json,
                    codec.digest(ProductPatentFieldResultEvidence.VERSION,json));
        }
        var reread=read(resultId);
        if(!ProductPatentFieldResultEvidence.digest(codec,reread,stored,truncated).equals(root.sha()))
            throw new IllegalStateException("Persisted field evidence reread differs");
    }
    ProductPatentFieldResultEvidence.Bundle read(String resultId) {
        Root root=root(resultId);
        List<Object[]> scopes=db.query("SELECT attempt_id,digest_version,scope_version,text_fields_json,models_json,scope_sha "
                + "FROM con4_search_field_scope WHERE result_set_id=?",(rs,row)->new Object[]{rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)},resultId);
        if(scopes.size()!=1 || !root.attempt().equals(scopes.getFirst()[0]) || !root.digestVersion().equals(scopes.getFirst()[1])
                || !ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(root.digestVersion())
                || !"SEARCH_FIELD_SCOPE/v2".equals(scopes.getFirst()[2]) || !root.scopeSha().equals(scopes.getFirst()[5]))
            throw new IllegalStateException("Exact field result scope missing");
        Map<String,String> models;
        try { models=mapper.readValue((String)scopes.getFirst()[4],new TypeReference<Map<String,String>>(){}); }
        catch(Exception bad) { throw new IllegalStateException("Field model facts invalid",bad); }
        Map<String,ProductPatentFieldResultEvidence.Fields> members=new TreeMap<>();
        db.query("SELECT member_id,evidence_version,fields_json,evidence_sha FROM con4_result_field_evidence "
                + "WHERE result_set_id=? ORDER BY member_id",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            String json=rs.getString(3);
            if(!ProductPatentFieldResultEvidence.VERSION.equals(rs.getString(2))
                    || !codec.digest(ProductPatentFieldResultEvidence.VERSION,json).equals(rs.getString(4)))
                throw new IllegalStateException("Field member evidence digest differs");
            try {
                var facts=mapper.readValue(json,ProductPatentFieldResultEvidence.Fields.class);
                if(!codec.json(ProductPatentFieldResultEvidence.canonical(facts)).equals(json)
                        || members.put(rs.getString(1),facts)!=null)
                    throw new IllegalStateException("Field member encoding differs");
            } catch(Exception bad) { throw new IllegalStateException("Field member evidence invalid",bad); }
        },resultId);
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,
                codec.nonEmptyStringList((String)scopes.getFirst()[3]),models,root.method(),root.ordering(),root.rerankModel(),
                root.rerankStatus(),members);
        var stored=ProductPatentSearchPages.members(db,codec,resultId);
        if(!root.sha().equals(ProductPatentFieldResultEvidence.digest(codec,bundle,stored,root.truncated())))
            throw new IllegalStateException("Field result digest differs");
        return bundle;
    }
    private Root root(String resultId) {
        List<Root> roots=db.query("SELECT a.attempt_id,a.scope_sha,a.method_version,r.ordering_version,a.rerank_model_version,"
                        + "a.rerank_status,r.truncated,r.result_sha,r.digest_version FROM con4_search_attempt a "
                        + "JOIN con4_result_set r ON r.attempt_id=a.attempt_id WHERE r.result_set_id=?",
                (rs,row)->new Root(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                        rs.getInt(7)==1,rs.getString(8),rs.getString(9)),resultId);
        if(roots.size()!=1) throw new IllegalStateException("Field result root missing");
        return roots.getFirst();
    }
}
