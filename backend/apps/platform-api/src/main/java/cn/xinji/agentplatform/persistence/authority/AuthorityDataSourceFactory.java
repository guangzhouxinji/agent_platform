package cn.xinji.agentplatform.persistence.authority;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Owns one dedicated authority pool. It never borrows or closes the primary pool.
 * No business data, issuer, watermark protocol or recovery admission is changed here.
 */
public final class AuthorityDataSourceFactory implements AutoCloseable {
    private HikariDataSource ownedPool;
    private boolean closed;

    public HikariDataSource open(AuthorityDataSourceSettings settings) {
        return open(settings, Function.identity());
    }

    /**
     * Starts a dedicated pool and runs the caller's existing startup checks before
     * publishing it. A failed check closes the candidate pool; it never disables checks.
     * JDBC exceptions can contain credentials, so failure diagnostics omit their causes.
     */
    public synchronized <T> T open(AuthorityDataSourceSettings settings,
                                   Function<HikariDataSource, T> startupCheck) {
        Objects.requireNonNull(settings, "Authority settings are required");
        Objects.requireNonNull(startupCheck, "Authority startup check is required");
        if (closed || ownedPool != null) {
            throw new IllegalStateException("Authority datasource owner is closed or already initialized");
        }

        // Keep a reference before starting Hikari so even initialization failures can close it.
        var candidate = new HikariDataSource();
        try {
            candidate.setJdbcUrl(settings.jdbcUrl());
            candidate.setUsername(settings.username());
            candidate.setPassword(settings.password());
            candidate.setSchema(settings.schema());
            candidate.setMaximumPoolSize(10);
            candidate.setMinimumIdle(1);
            candidate.setPoolName(settings.topology() == AuthorityDataSourceSettings.Topology.INDEPENDENT
                    ? "product-independent-authority" : "product-shared-instance-authority");
            try (var connection = candidate.getConnection()) {
                // Starting the pool also applies schema on its actual borrowed connection.
            }
            T result = startupCheck.apply(candidate);
            if (closed || candidate.isClosed()) {
                throw new IllegalStateException("Authority datasource closed during initialization");
            }
            ownedPool = candidate;
            return result;
        } catch (SQLException | RuntimeException failure) {
            closeFailedCandidate(candidate);
            throw new IllegalStateException("Authority datasource initialization failed");
        } catch (Error failure) {
            closeFailedCandidate(candidate);
            throw failure;
        }
    }

    private static void closeFailedCandidate(HikariDataSource candidate) {
        try {
            candidate.close();
        } catch (RuntimeException ignored) {
            // A shutdown diagnostic must not expose connection parameters either.
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (ownedPool != null) {
            ownedPool.close();
            ownedPool = null;
        }
    }
}
