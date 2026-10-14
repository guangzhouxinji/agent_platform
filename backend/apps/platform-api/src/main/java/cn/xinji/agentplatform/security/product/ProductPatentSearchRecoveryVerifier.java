package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only historical evidence closure; never grants current business access or revives a source. */
final class ProductPatentSearchRecoveryVerifier {
    private final JdbcTemplate db;
    private final ProductPatentSearchEvidenceCodec evidence;
    private final ProductCollectedPatentTitles titles;
    ProductPatentSearchRecoveryVerifier(JdbcTemplate db, Path root, long maxBytes) {
        this.db=db;
        var mapper=new ObjectMapper();
        evidence=new ProductPatentSearchEvidenceCodec(mapper);
        titles=new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root,maxBytes),mapper);
    }
    void verify() { verify(null); }
    ProductHistoricalResultIsolation.Report report() {
        List<ProductHistoricalResultIsolation.Gap> gaps=new java.util.ArrayList<>();
        verify(gaps);
        return new ProductHistoricalResultIsolation(db).report(gaps,gaps.isEmpty());
    }
    private void verify(List<ProductHistoricalResultIsolation.Gap> gaps) {
        zero("SELECT COUNT(*) FROM con4_search_attempt a LEFT JOIN con3_execution_attempt h "
                + "ON h.attempt_id=a.attempt_id LEFT JOIN con3_search_input i ON i.input_id=a.input_revision "
                + "LEFT JOIN con3_task t ON t.task_id=a.task_id WHERE h.attempt_id IS NULL OR i.input_id IS NULL "
                + "OR t.task_id IS NULL OR h.task_id<>a.task_id OR h.input_id<>a.input_revision "
                + "OR h.task_version<>a.task_version OR h.fencing_token<>a.fencing_token "
                + "OR h.worker_ref<>a.actor_ref OR i.task_id<>a.task_id OR i.task_version<>a.task_version "
                + "OR i.content_sha<>a.input_sha "
                + "OR t.organization_id<>a.organization_id OR t.version<a.task_version "
                + "OR a.expires_at>i.expires_at OR a.status NOT IN ('SUCCEEDED','PARTIAL')");
        zero("SELECT COUNT(*) FROM con4_search_attempt a WHERE "
                + "(SELECT COUNT(*) FROM con4_result_set r WHERE r.attempt_id=a.attempt_id)<>1 OR "
                + "(SELECT COUNT(*) FROM con4_search_request q WHERE q.attempt_id=a.attempt_id)<>1");
        db.query("SELECT a.original_query,i.query_text FROM con4_search_attempt a "
                + "JOIN con3_search_input i ON i.input_id=a.input_revision",
                (org.springframework.jdbc.core.RowCallbackHandler) row->{
                    if(!row.getString(1).equals(row.getString(2))) invalid();
                });
        zero("SELECT COUNT(*) FROM con4_search_request q LEFT JOIN con4_result_set r "
                + "ON r.result_set_id=q.result_set_id LEFT JOIN con4_search_attempt a ON a.attempt_id=q.attempt_id "
                + "WHERE r.result_set_id IS NULL OR a.attempt_id IS NULL OR r.attempt_id<>q.attempt_id "
                + "OR q.request_sha<>a.request_sha");
        zero("SELECT COUNT(*) FROM con4_selection_revision s LEFT JOIN con4_result_set r "
                + "ON r.result_set_id=s.result_set_id LEFT JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id "
                + "LEFT JOIN con3_task t ON t.task_id=s.task_id WHERE r.result_set_id IS NULL "
                + "OR a.attempt_id IS NULL OR t.task_id IS NULL OR s.task_id<>a.task_id "
                + "OR s.result_sha<>r.result_sha OR s.result_set_version<>1 "
                + "OR s.task_version<a.task_version OR s.task_version>t.version OR s.expires_at>a.expires_at "
                + "OR NOT EXISTS (SELECT 1 FROM con4_selection_member m WHERE m.selection_id=s.selection_id)");
        zero("SELECT COUNT(*) FROM con4_selection_member s LEFT JOIN con4_selection_revision p "
                + "ON p.selection_id=s.selection_id LEFT JOIN con4_result_member m "
                + "ON m.result_set_id=s.result_set_id AND m.member_id=s.member_id "
                + "WHERE p.selection_id IS NULL OR p.result_set_id<>s.result_set_id OR m.member_id IS NULL "
                + "OR s.ordinal<>m.ordinal OR s.source_id<>m.source_id OR s.document_id<>m.document_id "
                + "OR s.document_version<>m.document_version OR s.source_version<>m.source_version "
                + "OR s.raw_sha<>m.raw_sha OR s.locator_ref<>m.locator_ref");
        zero("SELECT COUNT(*) FROM con4_result_member m LEFT JOIN con4_result_set r "
                + "ON r.result_set_id=m.result_set_id WHERE r.result_set_id IS NULL OR r.attempt_id<>m.attempt_id");
        for(String id:db.query("SELECT result_set_id FROM con4_result_set",(row,n)->row.getString(1))) {
            try { result(id); }
            catch(ProductHistoricalResultIsolation.Unverified missing) {
                if(gaps==null) throw missing;
                gaps.addAll(missing.gaps());
            }
        }
    }
    private void result(String id) {
        var receipt=new ProductPatentSearchReceipts(db).readOriginalEvidence(id);
        boolean legacy="RESULT_DIGEST_PRE_STORAGE/v1".equals(receipt.digestVersion());
        boolean fieldResult=ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(receipt.digestVersion());
        boolean legacyAnd=legacy && "LEXICAL_AND/v1".equals(receipt.methodVersion());
        if(!legacy && !fieldResult && !"RESULT_DIGEST_DECIMAL_12/v2".equals(receipt.digestVersion())) invalid();
        var members=ProductPatentSearchPages.members(db,evidence,id,legacyAnd);
        if(!legacyAnd && receipt.candidateCount()<members.size()) invalid();
        if(!legacy && !fieldResult && !receipt.resultSha().equals(evidence.resultDigest(members,receipt.truncated()))) invalid();
        long failures=receipt.sourceOutcomes().stream().filter(row->"FAILED".equals(row.status())).count();
        if(failures==receipt.sourceOutcomes().size()
                || !(failures>0?"PARTIAL":"SUCCEEDED").equals(receipt.status())
                || !(failures>0||receipt.truncated()?"LOWER_BOUND":"EXACT").equals(receipt.totalReliability())) invalid();
        new ProductHistoricalResultIsolation(db).require(id);
        if(fieldResult) {
            new ProductPatentFieldRecoveryEvidence(db,evidence,titles).verify(id,receipt,members);
            return;
        }
        Map<String,Map<String,PatentLexicalQuery.Document>> originals=new HashMap<>();
        Map<String,Map<String,PatentLexicalQuery.Document>> filtered=new HashMap<>();
        var filterJson=db.queryForObject("SELECT filters_json FROM con4_search_attempt WHERE attempt_id=?",
                String.class,receipt.attemptId());
        var filters=ProductPatentSearchFilters.parse(new ObjectMapper(),filterJson);
        for(var source:receipt.sourceOutcomes()) if("SUCCEEDED".equals(source.status())) {
            var snapshots=db.query("SELECT v.source_version,v.manifest_ref,v.manifest_sha,v.records_sha," 
                    + "s.coverage_sha FROM con4_source_verification v JOIN con4_search_source s "
                    + "ON s.source_id=v.source_id AND s.contract_version=v.contract_version "
                    + "WHERE s.attempt_id=? AND s.source_id=?",(row,n)-> {
                        if(!row.getString(5).equals(evidence.digest(row.getString(3),row.getString(4)))) invalid();
                        return new ProductCollectedPatentTitles.CaptureSnapshot(source.sourceId(),
                                row.getString(1),row.getString(2),row.getString(3),row.getString(4));
                    },receipt.attemptId(),source.sourceId());
            if(snapshots.size()!=1) invalid();
            Map<String,PatentLexicalQuery.Document> documents=new HashMap<>();
            for(var document:titles.load(snapshots.getFirst(),ProductPatentSearchFilters.none()))
                if(documents.put(document.documentId(),document)!=null) invalid();
            originals.put(source.sourceId(),documents);
            Map<String,PatentLexicalQuery.Document> admitted=new HashMap<>();
            for(var document:titles.load(snapshots.getFirst(),filters))
                if(admitted.put(document.documentId(),document)!=null) invalid();
            filtered.put(source.sourceId(),admitted);
        }
        for(int ordinal=0;ordinal<members.size();ordinal++) {
            var member=members.get(ordinal);
            var original=filtered.getOrDefault(member.sourceId(),Map.of()).get(member.documentId());
            boolean exact="EXACT_PUBLICATION/v1".equals(receipt.methodVersion());
            if(member.ordinal()!=ordinal || original==null
                    || !member.matchFields().equals(exact?Set.of("DOCUMENT_ID"):Set.of("TITLE"))
                    || !member.documentVersion().equals(original.documentVersion())
                    || !member.sourceVersion().equals(original.sourceVersion())
                    || !member.rawSha().equals(original.rawSha()) || !member.locatorRef().equals(original.locatorRef())) invalid();
            if(exact && (!member.matchedTerms().equals(List.of(member.documentId()))
                    || !member.fieldScores().equals(Map.of("DOCUMENT_ID",1.0))
                    || !Double.valueOf(1.0).equals(member.lexicalScore())
                    || member.semanticScore()!=null || member.semanticRank()!=null
                    || member.fusionScore()!=null || member.rerankScore()!=null || member.rerankRank()!=null)) invalid();
            for(Double value:new Double[]{member.lexicalScore(),member.semanticScore(),member.fusionScore(),member.rerankScore()})
                if(value!=null && !Double.isFinite(value)) invalid();
        }
        if(legacy) {
            try {
                if(!receipt.resultSha().equals(new ProductLegacySearchRecovery(db).digest(receipt,members,filtered,originals)))
                    throw new IllegalStateException("Historical pre-storage original digest mismatch");
            } catch(org.springframework.dao.DataAccessException missing) {
                throw new IllegalStateException("Historical pre-storage evidence unavailable",missing);
            }
        }
        db.query("SELECT query_plan_version,expansions_json,expansion_sha,filter_version,filters_json,filter_sha "
                + "FROM con4_search_attempt WHERE attempt_id=?",(org.springframework.jdbc.core.RowCallbackHandler) row->{
                    for(int start:new int[]{1,4}) {
                        String version=row.getString(start),json=row.getString(start+1),sha=row.getString(start+2);
                        if(legacy && version==null && json==null && sha==null) continue;
                        if(version==null || json==null || sha==null || !sha.equals(evidence.digest(version,json))) invalid();
                    }
                },receipt.attemptId());
    }
    private void zero(String sql) { if(db.queryForObject(sql,Long.class)!=0) invalid(); }
    private static void invalid() { throw new IllegalStateException("Restored search/selection evidence inconsistent"); }
}


