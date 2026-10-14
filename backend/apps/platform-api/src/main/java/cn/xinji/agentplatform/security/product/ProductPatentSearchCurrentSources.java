package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every selected field is checked against present Source authority, independently of historical result facts. */
final class ProductPatentSearchCurrentSources {
    static Map<String,ProductSourceAvailability.Active> require(JdbcTemplate db,ObjectMapper mapper,
            ProductPatentSearchEvidenceCodec codec,ProductSourceAvailability availability,
            ProductSourceWatermark watermark,String attemptId) {
        List<String[]> roots=db.query("SELECT a.filters_json,r.digest_version FROM con4_search_attempt a "
                + "JOIN con4_result_set r ON r.attempt_id=a.attempt_id WHERE a.attempt_id=?",
                (rs,row)->new String[]{rs.getString(1),rs.getString(2)},attemptId);
        if(roots.size()!=1) throw new IllegalStateException("Persisted search filter evidence missing");
        var filters=ProductPatentSearchFilters.parse(mapper,roots.getFirst()[0]);
        Set<String> fields=Set.of("TITLE");
        if(ProductPatentFieldResultEvidence.DIGEST_VERSION.equals(roots.getFirst()[1])) {
            var scopes=db.query("SELECT text_fields_json FROM con4_search_field_scope WHERE attempt_id=? "
                    + "AND scope_version='SEARCH_FIELD_SCOPE/v2' AND digest_version=?",
                    (rs,row)->rs.getString(1),attemptId,ProductPatentFieldResultEvidence.DIGEST_VERSION);
            if(scopes.size()!=1) throw new IllegalStateException("Persisted field scope missing");
            var selected=codec.nonEmptyStringList(scopes.getFirst());
            if(new HashSet<>(selected).size()!=selected.size()) throw new IllegalStateException("Persisted field scope duplicated");
            fields=Set.copyOf(selected); ProductCapturedPatentFields.validate(fields);
        }
        List<Object[]> rows=db.query("SELECT source_id,contract_version,decision_id,source_epoch "
                + "FROM con4_search_source WHERE attempt_id=? AND status='SUCCEEDED'",
                (rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)},attemptId);
        if(rows.isEmpty()) throw new IllegalStateException("Persisted search has no successful source");
        Map<String,ProductSourceAvailability.Active> sources=new HashMap<>();
        for(Object[] row:rows) {
            var active = fields.size() == 1 ? availability.require((String)row[0], fields.iterator().next(), "QUERY")
                    : availability.requireFields((String)row[0], fields, "QUERY");
            if (active == null || !row[0].equals(active.sourceId()))
                throw new IllegalStateException("Persisted field source snapshots differ");
            filters.requireAuthorized(availability,active);
            if(!active.contractVersion().equals(row[1]) || !active.decisionId().equals(row[2]) || active.epoch()!=(long)row[3])
                throw new IllegalStateException("Persisted search source is stale");
            watermark.assertUnchanged(active.sourceId(),active.epoch());
            if(sources.put(active.sourceId(),active)!=null) throw new IllegalStateException("Persisted search source is duplicated");
        }
        return Map.copyOf(sources);
    }
    private ProductPatentSearchCurrentSources() { }
}
