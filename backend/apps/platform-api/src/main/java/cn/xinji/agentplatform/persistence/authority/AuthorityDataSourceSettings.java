package cn.xinji.agentplatform.persistence.authority;

import java.util.Locale;

/**
 * Validated authority connection settings, not authorization or recovery admission.
 * URL equality is intentionally exact: this adapter does not infer physical topology
 * from JDBC aliases. Callers remain responsible for deployment and recovery admission.
 */
public final class AuthorityDataSourceSettings {
    public enum Topology { INDEPENDENT, SHARED_INSTANCE }

    private final Topology topology;
    private final String jdbcUrl;
    private final String schema;
    private final String username;
    private final String password;

    private AuthorityDataSourceSettings(Topology topology, String jdbcUrl, String schema,
                                       String username, String password) {
        this.topology = topology;
        this.jdbcUrl = jdbcUrl;
        this.schema = schema;
        this.username = username;
        this.password = password;
    }

    /**
     * Resolves product.identity.authority.topology and the existing connection properties.
     * Independent mode retains the legacy primary URL comparison and authority username
     * rules; primary schema/username are required only for shared-instance isolation.
     * Passwords are opaque and may be empty, as in the existing connection contract.
     */
    public static AuthorityDataSourceSettings resolve(
            String topology, String primaryUrl, String primarySchema, String primaryUsername,
            String authorityUrl, String authoritySchema, String authorityUsername,
            String authorityPassword) {
        Topology resolvedTopology;
        if (topology == null || topology.isBlank() || "independent".equals(topology)) {
            resolvedTopology = Topology.INDEPENDENT;
        } else if ("shared-instance".equals(topology)) {
            resolvedTopology = Topology.SHARED_INSTANCE;
        } else {
            throw new IllegalArgumentException("Invalid authority topology");
        }

        String resolvedSchema = schema(authoritySchema);
        if (authorityUsername == null || authorityUsername.isBlank()) {
            throw new IllegalArgumentException("Authority username is required");
        }

        String resolvedUrl = authorityUrl;
        if (resolvedTopology == Topology.INDEPENDENT) {
            if (resolvedUrl == null || resolvedUrl.isBlank() || resolvedUrl.equals(primaryUrl)) {
                throw new IllegalArgumentException("Independent authority URL is required");
            }
        } else {
            if (primaryUrl == null || primaryUrl.isBlank()) {
                throw new IllegalArgumentException("Primary URL is required for shared-instance");
            }
            String resolvedPrimarySchema = schema(primarySchema);
            if (!identifier(primaryUsername) || !identifier(authorityUsername)) {
                throw new IllegalArgumentException("Valid primary and authority usernames are required");
            }
            if (resolvedPrimarySchema.equals(resolvedSchema)
                    || primaryUsername.equalsIgnoreCase(authorityUsername)) {
                throw new IllegalArgumentException("Distinct primary and authority schemas and usernames are required");
            }
            if (resolvedUrl == null || resolvedUrl.isBlank()) {
                resolvedUrl = primaryUrl;
            } else if (!resolvedUrl.equals(primaryUrl)) {
                throw new IllegalArgumentException("Shared-instance authority URL must equal primary URL");
            }
        }
        return new AuthorityDataSourceSettings(resolvedTopology, resolvedUrl, resolvedSchema,
                authorityUsername, authorityPassword);
    }

    private static String schema(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Invalid authority or primary schema");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]{0,63}");
    }

    public Topology topology() { return topology; }
    public String jdbcUrl() { return jdbcUrl; }
    public String schema() { return schema; }
    public String username() { return username; }
    public String password() { return password; }

    /** Never include JDBC URLs, identities or credentials in diagnostic rendering. */
    @Override public String toString() {
        return "AuthorityDataSourceSettings[topology=" + topology + "]";
    }
}
