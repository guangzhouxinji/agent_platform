package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** New-result provenance only. Never derives or backfills an old result's embedding configuration. */
final class ProductTitleBuildProvenance {
    record Witness(String source,String contract,String decision,long epoch,String model,String buildSha,int count,Instant completed) { }
    static void requireSchema(JdbcTemplate db) {
        try {db.query("SELECT result_set_id,source_id,contract_version,decision_id,source_epoch,model_version,build_sha,document_count,completed_at,record_sha FROM con4_result_title_build WHERE 1=0",rs->{});}
        catch(org.springframework.dao.DataAccessException missing){throw new IllegalStateException("TITLE_BUILD_PROVENANCE_SCHEMA_REQUIRED:V104");}
    }
    static List<Witness> capture(JdbcTemplate db,List<ProductSourceAvailability.Active> sources,Map<String,String> failures,String model,Instant now) {
        requireSchema(db);
        if(model==null||model.isBlank())throw new IllegalStateException("RECORDED_TITLE_MODEL_REQUIRED");
        List<Witness> result=new ArrayList<>();
        for(var source:sources)if(!failures.containsKey(source.sourceId())) {
            var builds=db.query("SELECT build_sha,document_count,completed_at FROM con4_vector_build WHERE source_id=? AND contract_version=? AND decision_id=? AND source_epoch=? AND field_name='TITLE' AND model_version=? ORDER BY completed_at,build_sha",
                    (rs,n)->new ProductHistoricalResultIsolation.Build(rs.getString(1),rs.getInt(2),rs.getTimestamp(3).toInstant()),source.sourceId(),source.contractVersion(),source.decisionId(),source.epoch(),model);
            if(ProductHistoricalResultIsolation.missing(builds,now)!=null)throw new IllegalStateException("EXACT_TITLE_BUILD_REQUIRED");
            var build=builds.stream().filter(b->!b.completed().isAfter(now)).findFirst().orElseThrow();
            result.add(new Witness(source.sourceId(),source.contractVersion(),source.decisionId(),source.epoch(),model,build.sha(),build.count(),build.completed()));
        }
        return List.copyOf(result);
    }
    static void store(JdbcTemplate db,String resultId,List<Witness> witnesses) {
        for(var w:witnesses)db.update("INSERT INTO con4_result_title_build(result_set_id,source_id,contract_version,decision_id,source_epoch,model_version,build_sha,document_count,completed_at,record_sha) VALUES(?,?,?,?,?,?,?,?,?,?)",
                resultId,w.source(),w.contract(),w.decision(),w.epoch(),w.model(),w.buildSha(),w.count(),Timestamp.from(w.completed()),sha(resultId,w));
    }
    static Witness read(JdbcTemplate db,String resultId,ProductPatentSearchSourceBatch.Outcome source) {
        if(!new ProductObservedTables(db).present("CON4_RESULT_TITLE_BUILD"))return null;
        var rows=db.query("SELECT contract_version,decision_id,source_epoch,model_version,build_sha,document_count,completed_at,record_sha FROM con4_result_title_build WHERE result_set_id=? AND source_id=?",(rs,n)->{
            var w=new Witness(source.sourceId(),rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getTimestamp(7).toInstant());
            if(!source.contractVersion().equals(w.contract())||!source.decisionId().equals(w.decision())||source.sourceEpoch()!=w.epoch()
                    ||!sha(resultId,w).equals(rs.getString(8))||w.model()==null||w.model().isBlank())throw new IllegalStateException("Recorded TITLE build binding differs");
            return w;
        },resultId,source.sourceId());
        if(rows.size()>1)throw new IllegalStateException("Recorded TITLE build is ambiguous");return rows.isEmpty()?null:rows.getFirst();
    }
    static String sha(String id,Witness w) {
        return ProductPatentSearchEvidenceCodec.digestValues(id,w.source(),w.contract(),w.decision(),Long.toString(w.epoch()),"TITLE",w.model(),w.buildSha(),Integer.toString(w.count()),w.completed().toString());
    }
}
