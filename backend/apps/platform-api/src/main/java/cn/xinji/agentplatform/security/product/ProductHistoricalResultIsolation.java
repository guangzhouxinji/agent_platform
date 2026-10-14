package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Read-only historical closure. Call only AFTER current task/source authority; never grants access. */
final class ProductHistoricalResultIsolation {
    static final String CODE = "HISTORICAL_RESULT_UNVERIFIED";
    record Tuple(String source, String contract, String decision, long epoch, String field, String model) { }
    record Build(String sha, int count, Instant completed) { }
    record Gap(String resultId, Tuple tuple, String missing) { }
    record Report(boolean overallIntegrityVerified, List<Gap> gaps, List<String> dependencyImpacts,
                  boolean fullOriginalValidationCompleted) {
        Report { gaps=List.copyOf(gaps); dependencyImpacts=List.copyOf(dependencyImpacts); }
    }
    static final class Unverified extends ResponseStatusException {
        private final List<Gap> gaps;
        Unverified(List<Gap> gaps) {
            super(HttpStatus.CONFLICT, CODE); this.gaps=List.copyOf(gaps);
        }
        List<Gap> gaps() { return gaps; }
    }
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec codec;
    ProductHistoricalResultIsolation(JdbcTemplate db) {
        this.db=db; codec=new ProductPatentSearchEvidenceCodec(new ObjectMapper());
    }
    void require(String id) {
        var gaps=inspect(id);
        if(!gaps.isEmpty()) throw new Unverified(gaps);
    }
    List<Gap> inspect(String id) {
        var receipt=new ProductPatentSearchReceipts(db).readOriginalEvidence(id);
        boolean fields=ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(receipt.digestVersion());
        boolean legacy="RESULT_DIGEST_PRE_STORAGE/v1".equals(receipt.digestVersion());
        var members=ProductPatentSearchPages.members(db,codec,id,legacy && "LEXICAL_AND/v1".equals(receipt.methodVersion()));
        if(!id.equals(receipt.resultSetId()) || members.size()!=receipt.resultCount()
                || (!(legacy && "LEXICAL_AND/v1".equals(receipt.methodVersion())) && receipt.candidateCount()<members.size())) corrupt();
        if(receipt.sourceOutcomes().stream().map(ProductPatentSearchSourceBatch.Outcome::sourceId).distinct().count()
                !=receipt.sourceOutcomes().size() || receipt.sourceOutcomes().stream().anyMatch(s->
                    !Set.of("SUCCEEDED","FAILED").contains(s.status()))) corrupt();
        for(int i=0;i<members.size();i++) if(members.get(i).ordinal()!=i) corrupt();
        Map<String,String> models=Map.of();
        if(fields) {
            var bundle=codec.fieldStore(db).read(id); // Validates original field/member/root digests first.
            ProductPatentFieldResultEvidence.validate(bundle,members);
            models=bundle.models();
        } else if(!legacy) {
            if(!"RESULT_DIGEST_DECIMAL_12/v2".equals(receipt.digestVersion())
                    || !receipt.resultSha().equals(codec.resultDigest(members,receipt.truncated()))) corrupt();
        }
        var times=db.query("SELECT created_at FROM con4_search_attempt WHERE attempt_id=?",
                (rs,n)->rs.getTimestamp(1).toInstant(),receipt.attemptId());
        if(times.size()!=1) corrupt();
        List<Gap> gaps=new ArrayList<>();
        for(var source:receipt.sourceOutcomes()) if("SUCCEEDED".equals(source.status())) {
            var sourceModels=models;
            ProductTitleBuildProvenance.Witness recorded=null;
            if(!fields && (receipt.methodVersion().startsWith("HYBRID")
                    || members.stream().anyMatch(m->m.sourceId().equals(source.sourceId()) && m.semanticScore()!=null))) {
                // Legacy result has no recorded model: never infer it from a current or sole build.
                recorded=ProductTitleBuildProvenance.read(db,id,source);
                if(recorded==null)gaps.add(new Gap(id,tuple(source,"TITLE",null),"RECORDED_MODEL_VERSION_MISSING"));
                else sourceModels=Map.of("TITLE",recorded.model());
            }
            for(var model:sourceModels.entrySet()) {
                Tuple tuple=tuple(source,model.getKey(),model.getValue());
                var builds=db.query("SELECT build_sha,document_count,completed_at FROM con4_vector_build "
                        + "WHERE source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? "
                        + "AND field_name=? AND model_version=?",(rs,n)->new Build(rs.getString(1),rs.getInt(2),
                                rs.getTimestamp(3).toInstant()),tuple.source(),tuple.contract(),tuple.decision(),
                        tuple.epoch(),tuple.field(),tuple.model());
                var recordedWitness=recorded;
                if(recordedWitness!=null && builds.stream().noneMatch(b->b.sha().equals(recordedWitness.buildSha()) && b.count()==recordedWitness.count() && b.completed().equals(recordedWitness.completed())))throw new IllegalStateException("Recorded TITLE build witness differs");
                String missing=missing(builds,times.getFirst());
                if(missing!=null) gaps.add(new Gap(id,tuple,missing));
                else verifyVectors(tuple,builds.stream().filter(b->!b.completed().isAfter(times.getFirst())).findFirst().orElseThrow());
            }
        }
        return List.copyOf(gaps);
    }
    static String missing(List<Build> builds,Instant executed) {
        for(var build:builds)
            if(build.sha()==null || !build.sha().matches("[0-9a-f]{64}") || build.count()<1 || build.completed()==null) corrupt();
        if(builds.isEmpty()) return "EXACT_TUPLE_BUILD_MISSING";
        var past=builds.stream().filter(b->!b.completed().isAfter(executed)).toList();
        if(past.isEmpty()) return "BUILD_AFTER_EXECUTION";
        if(past.stream().map(b->b.sha()+":"+b.count()).distinct().count()!=1) return "CONFLICTING_BUILD_WITNESSES";
        return null;
    }
    private void verifyVectors(Tuple tuple,Build build) {
        var versions=db.query("SELECT source_version FROM con4_source_verification WHERE source_id=? AND contract_version=?",
                (rs,n)->rs.getString(1),tuple.source(),tuple.contract());
        var models=db.query("SELECT provider,model_id,model_revision,model_sha,tokenizer_sha,vector_dimension,"
                + "max_tokens,normalization,license_id FROM con4_embedding_model WHERE model_version=?",
                (rs,n)->new cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider.Model(
                    rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),
                    rs.getInt(7),rs.getString(8),rs.getString(9)),tuple.model());
        if(versions.size()!=1 || models.size()!=1) corrupt();
        var model=models.getFirst();
        String expected=model.modelId()+"@"+model.modelRevision()+"/"+model.normalization();
        if(!"TITLE".equals(tuple.field())) expected+="/"
                +cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider.COMPLETE_REPRESENTATION_VERSION
                +"/W"+model.maxTokens()+"/D"+model.dimension();
        if(!expected.equals(tuple.model())) corrupt();
        var hashes=db.query("SELECT b.document_id,b.document_version,b.vector_sha,v.vector_data FROM con4_document_vector_binding b "
                + "LEFT JOIN con4_document_vector v ON v.source_id=b.source_id AND v.document_id=b.document_id "
                + "AND v.document_version=b.document_version AND v.field_name=b.field_name AND v.model_version=b.model_version "
                + "AND v.vector_sha=b.vector_sha WHERE b.source_id=? AND b.source_version=? AND b.field_name=? "
                + "AND b.model_version=? ORDER BY b.document_id,b.document_version",(rs,n)-> {
                    byte[] bytes=rs.getBytes(4); String sha=rs.getString(3);
                    if(bytes==null || !ProductVectorCodec.sha(bytes).equals(sha)) corrupt();
                    ProductVectorCodec.encode(ProductVectorCodec.decode(bytes,model.dimension()),model.dimension());
                    return List.of(rs.getString(1),rs.getString(2),sha);
                },tuple.source(),versions.getFirst(),tuple.field(),tuple.model());
        if(hashes.size()!=build.count() || hashes.stream().map(h->h.get(0)+"\n"+h.get(1)).distinct().count()!=hashes.size()
                || !codec.digest(hashes.stream().map(h->h.get(2)).toArray(String[]::new)).equals(build.sha())) corrupt();
    }
    Report report(List<Gap> gaps,boolean fullOriginalValidationCompleted) {
        List<String> impacts=new ArrayList<>();
        for(String id:gaps.stream().map(Gap::resultId).distinct().toList()) {
            impacts.add("RESULT:"+id+":DISPLAY,CITATION,EXPLANATION,SELECTION,COMPARISON,CONTINUATION_REJECTED");
            for(String selection:db.query("SELECT selection_id FROM con4_selection_revision WHERE result_set_id=?",
                    (rs,n)->rs.getString(1),id)) impacts.add("SELECTION:"+selection+":"+CODE);
            if(ProductModelTurnRetentionReferences.present(db))
                for(String turn:db.query("SELECT turn_id FROM con3_model_turn WHERE result_set_id=?",
                        (rs,n)->rs.getString(1),id)) impacts.add("MODEL_TURN:"+turn+":"+CODE);
        }
        return new Report(gaps.isEmpty() && fullOriginalValidationCompleted,gaps,impacts,fullOriginalValidationCompleted);
    }
    private static Tuple tuple(ProductPatentSearchSourceBatch.Outcome source,String field,String model) {
        return new Tuple(source.sourceId(),source.contractVersion(),source.decisionId(),source.sourceEpoch(),field,model);
    }
    private static void corrupt() { throw new IllegalStateException("Historical result evidence integrity differs"); }
}



