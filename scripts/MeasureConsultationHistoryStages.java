import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Read-only timing of sealed product history. No business request, grant mutation or SLO claim. */
public final class MeasureConsultationHistoryStages {
    static final ThreadLocal<Timing> CURRENT = new ThreadLocal<>();
    static final class Timing {
        long acquire, prepare, execute, callback, next, query;
        int rows, acquisitions, executions, nextCalls, statementFetchSize;
    }
    record Sample(int ordinal, double totalMs, double acquireMs, double prepareMs,
                  double executeMs, double callbackMs, double resultSetNextMs, double fetchAndFrameworkMs,
                  int rows, int acquisitions, int executions, int nextCalls, int statementFetchSize) { }

    static final class TimedJdbc extends JdbcTemplate {
        TimedJdbc(HikariDataSource pool) {
            super(new AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    long start = System.nanoTime();
                    Connection connection = pool.getConnection();
                    Timing timing = CURRENT.get();
                    if (timing == null) return connection;
                    timing.acquire += System.nanoTime() - start;
                    timing.acquisitions++;
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("prepareStatement")) return MeasureConsultationHistoryStages.call(method, connection, args);
                        long before = System.nanoTime();
                        PreparedStatement statement = (PreparedStatement) MeasureConsultationHistoryStages.call(method, connection, args);
                        timing.prepare += System.nanoTime() - before;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (statementProxy, operation, values) -> {
                            if (!operation.getName().equals("executeQuery")) return MeasureConsultationHistoryStages.call(operation, statement, values);
                            timing.statementFetchSize = statement.getFetchSize();
                            long execution = System.nanoTime();
                            ResultSet result;
                            try { result = (ResultSet) MeasureConsultationHistoryStages.call(operation, statement, values); }
                            finally {
                                timing.execute += System.nanoTime() - execution;
                                timing.executions++;
                            }
                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                    new Class<?>[]{ResultSet.class}, (rowProxy, rowOperation, rowValues) -> {
                                if (!rowOperation.getName().equals("next"))
                                    return MeasureConsultationHistoryStages.call(rowOperation, result, rowValues);
                                long fetch = System.nanoTime();
                                try { return MeasureConsultationHistoryStages.call(rowOperation, result, rowValues); }
                                finally { timing.next += System.nanoTime() - fetch; timing.nextCalls++; }
                            });
                        });
                    });
                }
                @Override public Connection getConnection(String user, String password) {
                    throw new UnsupportedOperationException("Only managed pooled connection is permitted");
                }
            });
            setFetchSize(256);
        }
        @Override public void query(String sql, RowCallbackHandler handler, Object... args) {
            Timing timing = CURRENT.get();
            if (timing == null) { super.query(sql, handler, args); return; }
            long start = System.nanoTime();
            try {
                super.query(sql, rows -> {
                    long before = System.nanoTime();
                    try { handler.processRow(rows); }
                    finally { timing.callback += System.nanoTime() - before; timing.rows++; }
                }, args);
            } finally { timing.query += System.nanoTime() - start; }
        }
    }
    static Object call(Method method, Object receiver, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Sealed product class root required");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:dm://127.0.0.1:5301"); config.setUsername("CONPROD");
        config.setPassword(MeasureConsultationFramingCandidate.required("FRAME_PRIMARY_PASSWORD"));
        config.setSchema("CONPROD"); config.setReadOnly(true);
        config.setMaximumPoolSize(10); config.setMinimumIdle(10);
        config.setPoolName("consultation-history-stages-readonly");
        try (var loader = new MeasureConsultationFramingCandidate.ProductLoader(
                    new java.net.URL[]{Path.of(args[0]).toUri().toURL()});
             var pool = new HikariDataSource(config);
             var authority = DriverManager.getConnection("jdbc:dm://127.0.0.1:5302", "CONAUTH_SVC",
                    MeasureConsultationFramingCandidate.required("FRAME_AUTHORITY_PASSWORD"));
             var jobs = Executors.newFixedThreadPool(10)) {
            authority.setSchema("CONAUTH"); authority.setReadOnly(true);
            var expected = MeasureConsultationFramingCandidate.state(authority);
            if (!"READY".equals(expected.state()) || expected.intent() != null)
                throw new IllegalStateException("Independent authority pending");
            Method digest = MeasureConsultationFramingCandidate.digestMethod(loader);
            var plain = new JdbcTemplate(pool); plain.setFetchSize(256);
            if (!expected.sha().equals(MeasureConsultationFramingCandidate.invoke(digest, plain)))
                throw new IllegalStateException("Sealed product digest differs from independent authority");
            var measured = new TimedJdbc(pool);
            var mapper = new ObjectMapper();
            for (int phase = 0; phase < 2; phase++) {
                if (!expected.equals(MeasureConsultationFramingCandidate.state(authority)))
                    throw new IllegalStateException("Authority changed before phase");
                List<Callable<Sample>> calls = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    int ordinal = i;
                    calls.add(() -> {
                        Timing timing = new Timing(); CURRENT.set(timing);
                        try {
                            long start = System.nanoTime();
                            String actual = MeasureConsultationFramingCandidate.invoke(digest, measured);
                            long total = System.nanoTime() - start;
                            if (!expected.sha().equals(actual)) throw new IllegalStateException("History SHA mismatch");
                            if (timing.acquisitions != 1 || timing.executions != 1 || timing.rows == 0
                                    || timing.nextCalls != timing.rows + 1 || timing.statementFetchSize != 256)
                                throw new IllegalStateException("Incomplete timing instrumentation");
                            long residual = timing.query - timing.acquire - timing.prepare - timing.execute
                                    - timing.callback - timing.next;
                            if (residual < 0) throw new IllegalStateException("Overlapping timing partitions");
                            return new Sample(ordinal, total / 1e6, timing.acquire / 1e6, timing.prepare / 1e6,
                                    timing.execute / 1e6, timing.callback / 1e6, timing.next / 1e6, residual / 1e6,
                                    timing.rows, timing.acquisitions, timing.executions,
                                    timing.nextCalls, timing.statementFetchSize);
                        } finally { CURRENT.remove(); }
                    });
                }
                List<Sample> samples = new ArrayList<>();
                for (var result : jobs.invokeAll(calls)) samples.add(result.get());
                if (!expected.equals(MeasureConsultationFramingCandidate.state(authority)))
                    throw new IllegalStateException("Authority changed after phase");
                System.out.println(mapper.writeValueAsString(Map.of("phase", phase, "runs", 100,
                        "concurrency", 10, "samples", samples, "historySha", expected.sha(),
                        "generation", expected.generation(), "allDigestsMatch", true,
                        "scope", "INSTRUMENTED_READ_ONLY_SEALED_HISTORY_NOT_HTTP_SLO",
                        "limitations", List.of("executeQuery is driver wall time, not server-only SQL",
                            "callback includes getString conversion and canonical framing, not CPU-only",
                            "resultSetNext is driver wall time including buffered iteration or network fetch, not server-only SQL",
                            "fetchAndFramework excludes measured next calls but includes binding, cleanup and instrumentation overhead",
                            "ResultSet reflection proxy adds diagnostic overhead; not comparable to uninstrumented SLO",
                            "No product HTTP cold/hot SLO or production load equivalence"))));
            }
        }
    }
}
