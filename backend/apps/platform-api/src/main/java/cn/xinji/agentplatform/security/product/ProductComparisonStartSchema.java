package cn.xinji.agentplatform.security.product;

import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** V103 exact active CHECK is required before enabling explicit human handoff. No business rows are read. */
final class ProductComparisonStartSchema {
    static void verify(JdbcTemplate db, String schema) {
        if (schema == null || !schema.matches("[A-Za-z][A-Za-z0-9_]{2,63}")) throw unavailable();
        String owner = schema.toUpperCase(Locale.ROOT);
        String vendor = db.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        String query;
        if ("H2".equals(vendor)) {
            query = "SELECT c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t "
                    + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON c.CONSTRAINT_CATALOG=t.CONSTRAINT_CATALOG "
                    + "AND c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME "
                    + "WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME='CON5_START_COMMAND' "
                    + "AND t.CONSTRAINT_NAME='CK_CON5_START_FENCE_PRESENT' AND t.CONSTRAINT_TYPE='CHECK' AND t.ENFORCED='YES'";
        } else if (vendor != null && vendor.toUpperCase(Locale.ROOT).startsWith("DM")) {
            query = "SELECT SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME='CON5_START_COMMAND' "
                    + "AND CONSTRAINT_NAME='CK_CON5_START_FENCE_PRESENT' AND CONSTRAINT_TYPE='C' AND STATUS='ENABLED'";
        } else throw unavailable();
        var clauses = db.queryForList(query, String.class, owner);
        if (clauses.size() != 1 || !requiredClause(clauses.getFirst())) throw unavailable();
        // Verifies this runtime connection can access only the configured table, without reading history.
        db.queryForList("SELECT command_id FROM " + owner + ".con5_start_command WHERE 1=0", String.class);
    }
    static boolean requiredClause(String clause) {
        if (clause == null) return false;
        String compact = clause.toUpperCase(Locale.ROOT).replaceAll("[\\s\"()]", "");
        // Actual DM8 dictionary rewrites IS NOT NULL as NOT (IS NULL); neither form admits NULL fence.
        return compact.equals("ATTEMPT_IDISNULLORFENCING_TOKENISNOTNULL")
                || compact.equals("ATTEMPT_IDISNULLORNOTFENCING_TOKENISNULL");
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Required comparison start fence constraint unavailable");
    }
    private ProductComparisonStartSchema() { }
}
