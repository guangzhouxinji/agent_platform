package cn.xinji.agentplatform.security.product;

import java.util.Map;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Historical model policy references only; never changes expiry, authority or retained output. */
final class ProductModelTurnRetentionReferences {
    private ProductModelTurnRetentionReferences() { }

    static void collect(JdbcTemplate db, Map<String, String> references) {
        boolean invocations=present(db,"CON3_MODEL_INVOCATION");
        if (!present(db)) {
            if (invocations) throw new IllegalStateException("Invocation schema without model turn schema");
            return; // Pre-V089 historical schema, not permission to enable a model.
        }
        collectTable(db,references,"con3_model_turn");
        if (invocations) collectTable(db,references,"con3_model_invocation");
    }

    private static void collectTable(JdbcTemplate db, Map<String,String> references, String table) {
        db.query("SELECT retention_ref,retention_evidence_sha FROM " + table, (RowCallbackHandler) row -> {
            String ref = row.getString(1), sha = row.getString(2);
            if (ref == null || ref.isBlank() || sha == null || !sha.matches("[0-9a-fA-F]{64}"))
                throw new IllegalStateException("Invalid model retention reference");
            String previous = references.putIfAbsent(ref, sha);
            if (previous != null && !previous.equalsIgnoreCase(sha))
                throw new IllegalStateException("Conflicting model retention digest");
        });
    }

    static boolean present(JdbcTemplate db) { return present(db,"CON3_MODEL_TURN"); }

    static boolean present(JdbcTemplate db,String table) {
        Boolean present = db.execute((ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, connection.getSchema(),
                    table, new String[]{"TABLE"})) {
                return tables.next();
            }
        });
        if (present == null) throw new IllegalStateException("Model retention schema unavailable");
        return present;
    }
}
