package cn.xinji.agentplatform.persistence.authority;

import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated in-memory H2 component evidence only, not DM8 deployment/recovery evidence. */
class AuthorityDataSourceFactoryTest {
    @Test void independentPoolKeepsLegacySameUserAndSchemaAndClosesOnlyAuthority() throws Exception {
        try (var primary = new MemoryDatabase(); var authority = new MemoryDatabase();
             var factory = new AuthorityDataSourceFactory()) {
            var settings = independent(primary.url, authority.url, "PUBLIC");
            var pool = factory.open(settings);
            assertEquals(10, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            assertEquals("product-independent-authority", pool.getPoolName());
            try (var connection = pool.getConnection()) {
                assertEquals("PUBLIC", connection.getSchema());
                connection.createStatement().execute("CREATE TABLE authority_only(id INT)");
            }
            factory.close();
            factory.close();
            assertTrue(pool.isClosed());
            assertFalse(primary.admin.isClosed());
            assertEquals(1, scalar(primary.admin, "SELECT 1"));
            assertEquals(0, scalar(primary.admin,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='AUTHORITY_ONLY'"));
            assertEquals(1, scalar(authority.admin,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='AUTHORITY_ONLY'"));
        }
    }

    @Test void sharedInstanceUsesDedicatedAccountAndRestoresSchemaOnEveryBorrow() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            database.prepareSharedSchemas();
            try (var primary = DriverManager.getConnection(database.url, "BIZ_USER", "test-only")) {
                primary.setSchema("BUSINESS");
                var pool = factory.open(shared(database.url, ""));
                assertEquals("product-shared-instance-authority", pool.getPoolName());
                try (var first = pool.getConnection(); var second = pool.getConnection();
                     var third = pool.getConnection()) {
                    for (Connection connection : new Connection[] {first, second, third}) {
                        assertEquals("AUTHORITY", connection.getSchema());
                        assertEquals("AUTH_USER", connection.getMetaData().getUserName());
                        assertEquals(2, scalar(connection, "SELECT id FROM marker"));
                        assertThrows(SQLException.class,
                                () -> scalar(connection, "SELECT id FROM BUSINESS.marker"));
                    }
                    // A consumer's schema change must not contaminate the next borrower.
                    first.setSchema("PUBLIC");
                    second.setSchema("PUBLIC");
                    third.setSchema("PUBLIC");
                }
                try (var next = pool.getConnection()) {
                    assertEquals("AUTHORITY", next.getSchema());
                    assertEquals(2, scalar(next, "SELECT id FROM marker"));
                }
                assertEquals(1, scalar(primary, "SELECT id FROM marker"));
                assertThrows(SQLException.class, () -> scalar(primary, "SELECT id FROM AUTHORITY.marker"));
                factory.close();
                assertTrue(pool.isClosed());
                assertFalse(primary.isClosed());
                assertEquals(1, scalar(primary, "SELECT id FROM marker"));
            }
        }
    }

    @Test void explicitSharedUrlPublishesStartupResultAndRetainsPoolOwnership() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            database.prepareSharedSchemas();
            var captured = new AtomicReference<HikariDataSource>();
            int result = factory.open(shared(database.url, database.url), pool -> {
                captured.set(pool);
                try (var connection = pool.getConnection()) {
                    return scalar(connection, "SELECT id FROM marker");
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertEquals(2, result);
            assertFalse(captured.get().isClosed());
            factory.close();
            assertTrue(captured.get().isClosed());
        }
    }

    @Test void failingStartupCheckClosesAllAuthoritySessionsAndRedactsException() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            database.prepareSharedSchemas();
            var captured = new AtomicReference<HikariDataSource>();
            var failure = assertThrows(IllegalStateException.class,
                    () -> factory.open(shared(database.url, null), pool -> {
                        captured.set(pool);
                        throw new IllegalStateException("url-secret password-secret " + database.url);
                    }));
            assertTrue(captured.get().isClosed());
            assertEquals(0, scalar(database.admin,
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE USER_NAME='AUTH_USER'"));
            assertRedacted(failure, database.url, "url-secret", "password-secret");
            assertEquals(1, scalar(database.admin, "SELECT 1"));
            // A failed candidate is not retained and a new checked attempt can succeed.
            assertFalse(factory.open(shared(database.url, null)).isClosed());
        }
    }

    @Test void missingSchemaFailsBeforeStartupCallbackAndDoesNotLeakSession() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            var called = new AtomicBoolean();
            var failure = assertThrows(IllegalStateException.class,
                    () -> factory.open(independent("jdbc:other-primary", database.url, "MISSING_SCHEMA"), pool -> {
                        called.set(true);
                        return pool;
                    }));
            assertFalse(called.get());
            assertRedacted(failure, database.url);
            // Only the fixture's admin session survives failed pool startup.
            assertEquals(1, scalar(database.admin, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"));
            assertEquals(1, scalar(database.admin, "SELECT 1"));
        }
    }

    @Test void nullSettingsAndNullCheckAreRejectedBeforeAnyPoolIsOpened() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            var called = new AtomicBoolean();
            assertThrows(NullPointerException.class, () -> factory.open(null, pool -> {
                called.set(true);
                return pool;
            }));
            assertThrows(NullPointerException.class,
                    () -> factory.open(independent("jdbc:other-primary", database.url, "PUBLIC"), null));
            assertFalse(called.get());
            assertEquals(1, scalar(database.admin, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"));
            assertFalse(factory.open(independent("jdbc:other-primary", database.url, "PUBLIC")).isClosed());
        }
    }

    @Test void successfulOwnerCannotBeReopenedOrResurrectedAfterClose() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            var settings = independent("jdbc:other-primary", database.url, "PUBLIC");
            var pool = factory.open(settings);
            var called = new AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> factory.open(settings, source -> {
                called.set(true);
                return source;
            }));
            assertFalse(called.get());
            assertFalse(pool.isClosed());
            factory.close();
            assertTrue(pool.isClosed());
            assertThrows(IllegalStateException.class, () -> factory.open(settings));
            assertEquals(1, scalar(database.admin, "SELECT 1"));
        }
    }

    @Test void closingOneOwnerDoesNotCloseAnotherOwnersPool() throws Exception {
        try (var database = new MemoryDatabase(); var first = new AuthorityDataSourceFactory();
             var second = new AuthorityDataSourceFactory()) {
            var settings = independent("jdbc:other-primary", database.url, "PUBLIC");
            var firstPool = first.open(settings);
            var secondPool = second.open(settings);
            first.close();
            assertTrue(firstPool.isClosed());
            assertFalse(secondPool.isClosed());
            try (var connection = secondPool.getConnection()) {
                assertEquals(1, scalar(connection, "SELECT 1"));
            }
        }
    }

    @Test void closingOwnerDuringStartupCannotPublishOrLeakCandidate() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            var captured = new AtomicReference<HikariDataSource>();
            assertThrows(IllegalStateException.class,
                    () -> factory.open(independent("jdbc:other-primary", database.url, "PUBLIC"), pool -> {
                        captured.set(pool);
                        factory.close();
                        return pool;
                    }));
            assertTrue(captured.get().isClosed());
            assertEquals(1, scalar(database.admin, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"));
        }
    }

    @Test void startupErrorAlsoClosesCandidate() throws Exception {
        try (var database = new MemoryDatabase(); var factory = new AuthorityDataSourceFactory()) {
            var captured = new AtomicReference<HikariDataSource>();
            assertThrows(AssertionError.class,
                    () -> factory.open(independent("jdbc:other-primary", database.url, "PUBLIC"), pool -> {
                        captured.set(pool);
                        throw new AssertionError("test-only startup error");
                    }));
            assertTrue(captured.get().isClosed());
            assertEquals(1, scalar(database.admin, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS"));
        }
    }

    private static AuthorityDataSourceSettings independent(String primaryUrl, String authorityUrl, String schema) {
        return AuthorityDataSourceSettings.resolve(null, primaryUrl, "PUBLIC", "sa", authorityUrl, schema, "sa", "");
    }

    private static AuthorityDataSourceSettings shared(String primaryUrl, String authorityUrl) {
        return AuthorityDataSourceSettings.resolve("shared-instance", primaryUrl, "BUSINESS", "BIZ_USER",
                authorityUrl, "authority", "AUTH_USER", "test-only");
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void assertRedacted(Throwable failure, String... secrets) {
        assertEquals("Authority datasource initialization failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        var output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        for (String secret : secrets) {
            assertFalse(output.toString().contains(secret));
        }
    }

    private static final class MemoryDatabase implements AutoCloseable {
        private final String url = "jdbc:h2:mem:authority-adapter-" + UUID.randomUUID();
        private final Connection admin = DriverManager.getConnection(url, "sa", "");

        private MemoryDatabase() throws SQLException { }

        private void prepareSharedSchemas() throws SQLException {
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE SCHEMA BUSINESS");
                statement.execute("CREATE SCHEMA AUTHORITY");
                statement.execute("CREATE USER BIZ_USER PASSWORD 'test-only'");
                statement.execute("CREATE USER AUTH_USER PASSWORD 'test-only'");
                statement.execute("GRANT ALL ON SCHEMA BUSINESS TO BIZ_USER");
                statement.execute("GRANT ALL ON SCHEMA AUTHORITY TO AUTH_USER");
                statement.execute("CREATE TABLE BUSINESS.marker(id INT)");
                statement.execute("INSERT INTO BUSINESS.marker VALUES(1)");
                statement.execute("CREATE TABLE AUTHORITY.marker(id INT)");
                statement.execute("INSERT INTO AUTHORITY.marker VALUES(2)");
            }
        }

        @Override public void close() throws SQLException { admin.close(); }
    }
}
