package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Immutable capture identity, separate from a new product-use contract; caller owns the transaction. */
final class ProductSourceCaptureIdentity {
    static void lock(JdbcTemplate db,ObjectMapper mapper,ProductSourceCandidates.Declaration input,String json) {
        var rows=locked(db,input);
        if(rows.isEmpty()) {
            try {db.update("INSERT INTO con4_source_capture_identity(source_id,source_version,declaration_json) VALUES(?,?,?)",
                    input.sourceId(),input.sourceVersion(),json);}
            catch(DataIntegrityViolationException collision) {
                if(locked(db,input).isEmpty())throw collision;
            }
            rows=locked(db,input);
        }
        if(rows.size()!=1 || !normalized(mapper,rows.getFirst()).equals(normalized(mapper,json)))throw conflict();
        var previous=db.query("SELECT status,declaration_json FROM con4_source_contract WHERE source_id=? AND source_version=?",
                (rs,row)->List.of(rs.getString(1),rs.getString(2)),input.sourceId(),input.sourceVersion());
        if(!previous.isEmpty() && (!input.sourceVersion().matches("CAPTURE_SHA256:[0-9a-f]{64}")
                || previous.stream().anyMatch(row->!"WITHDRAWN".equals(row.getFirst())
                    || !normalized(mapper,row.get(1)).equals(normalized(mapper,json)))))throw conflict();
    }
    private static List<String> locked(JdbcTemplate db,ProductSourceCandidates.Declaration input) {
        return db.query("SELECT declaration_json FROM con4_source_capture_identity WHERE source_id=? AND source_version=? FOR UPDATE",
                (rs,row)->rs.getString(1),input.sourceId(),input.sourceVersion());
    }
    static ObjectNode normalized(ObjectMapper mapper,String json) {
        try {
            var value=mapper.readTree(json);
            if(!(value instanceof ObjectNode object))throw new IllegalStateException("Capture declaration is not an object");
            // Requested uses are a versioned application, not captured patent bytes or a grant.
            // Actual uses still require current admission/field-use decisions and watermarks.
            object.remove(List.of("contractVersion","validUntil","requestedUses"));return object;
        } catch(java.io.IOException corrupt) {throw new IllegalStateException("Capture declaration is invalid",corrupt);}
    }
    static void verify(JdbcTemplate db,ObjectMapper mapper) {
        db.query("SELECT c.source_id,c.source_version,c.declaration_json,i.declaration_json FROM con4_source_contract c "
                + "LEFT JOIN con4_source_capture_identity i ON i.source_id=c.source_id AND i.source_version=c.source_version",
                (org.springframework.jdbc.core.RowCallbackHandler) row->{
                    if(row.getString(4)==null)throw new IllegalStateException("Source capture identity missing");
                    var contract=normalized(mapper,row.getString(3));var anchor=normalized(mapper,row.getString(4));
                    if(!row.getString(1).equals(anchor.path("sourceId").asText())
                            || !row.getString(2).equals(anchor.path("sourceVersion").asText()) || !contract.equals(anchor))
                        throw new IllegalStateException("Source capture identity differs");
                });
        if(db.queryForObject("SELECT COUNT(*) FROM con4_source_capture_identity i WHERE NOT EXISTS (SELECT 1 "
                + "FROM con4_source_contract c WHERE c.source_id=i.source_id AND c.source_version=i.source_version)",Long.class)!=0)
            throw new IllegalStateException("Source capture identity orphaned");
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,"Source capture identity or prior contract conflict");
    }
}
