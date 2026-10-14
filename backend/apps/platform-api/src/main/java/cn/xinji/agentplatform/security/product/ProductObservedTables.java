package cn.xinji.agentplatform.security.product;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** Positive schema discovery only. SQL must still read each known table on every check. */
final class ProductObservedTables {
    private final JdbcTemplate database;
    private final Set<String> observed = ConcurrentHashMap.newKeySet();
    ProductObservedTables(JdbcTemplate database) { this.database = database; }

    boolean present(String name) {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Exact physical table name required");
        if (observed.contains(name)) return true;
        try {
            boolean found = Boolean.TRUE.equals(database.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                        try (var tables = connection.getMetaData().getTables(null, connection.getSchema(),
                                name, new String[] {"TABLE"})) {
                            return tables.next();
                        }
                    }));
            if (found) observed.add(name);
            return found;
        } catch (Exception failure) {
            throw new IllegalStateException("Required physical schema cannot be inspected", failure);
        }
    }
}
