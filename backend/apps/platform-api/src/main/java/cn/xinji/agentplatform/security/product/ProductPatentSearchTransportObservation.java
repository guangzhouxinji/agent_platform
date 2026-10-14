package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/** Request-local timing only; no credentials, query, identity facts or authorization cache. */
final class ProductPatentSearchTransportObservation implements AutoCloseable {
    enum Detail { SOURCE_INVENTORY, SOURCE_PROJECTION, CITATION_ORIGINALS, GRANT_HISTORY_SQL_AND_FETCH }
    private static final ThreadLocal<ProductPatentSearchTransportObservation> CURRENT = new ThreadLocal<>();
    private final java.util.Map<Detail,Long> details = new java.util.EnumMap<>(Detail.class);
    private final String timingId = java.util.UUID.randomUUID().toString();
    private final ProductPatentSearchTransportObservation previous;
    static String currentTimingId() { var scope=CURRENT.get(); return scope==null ? null : scope.timingId; }
    static <T> T measure(Detail detail, java.util.function.Supplier<T> operation) {
        var scope=CURRENT.get();
        if(scope==null) return operation.get();
        long started=scope.timer.getAsLong();
        try { return operation.get(); }
        finally { scope.details.merge(detail,Math.max(0,scope.timer.getAsLong()-started),Long::sum); }
    }
    private static final Pattern ROUTE = Pattern.compile("/v2/consultation/tasks/([0-9a-fA-F-]{36})"
            + "/patent-searches/([0-9a-fA-F-]{36})/results");
    private final String taskId;
    private final String resultId;
    private final LongSupplier timer;
    private final IntSupplier status;
    private final Consumer<String> output;
    private final long started;
    private long authorityFinished;
    private boolean authorityChecked;
    private boolean downstreamReturned;
    private boolean closed;

    static ProductPatentSearchTransportObservation start(HttpServletRequest request,
            HttpServletResponse response) {
        var match = ROUTE.matcher(request.getRequestURI());
        if (!"GET".equals(request.getMethod()) || !match.matches()) return null;
        return new ProductPatentSearchTransportObservation(match.group(1), match.group(2),
                System::nanoTime, response::getStatus, value -> LoggerFactory
                        .getLogger(ProductPatentSearchTransportObservation.class).info("{}", value));
    }

    ProductPatentSearchTransportObservation(String taskId, String resultId, LongSupplier timer,
            IntSupplier status, Consumer<String> output) {
        if (!taskId.matches("[0-9a-fA-F-]{36}") || !resultId.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("Exact route identifiers required");
        this.taskId=taskId; this.resultId=resultId; this.timer=timer;
        this.status=status; this.output=output; started=timer.getAsLong();
        previous=CURRENT.get(); CURRENT.set(this);
    }

    void authorityChecked() {
        authorityFinished=timer.getAsLong(); authorityChecked=true;
    }

    void downstreamReturned() { downstreamReturned=true; }

    @Override public void close() {
        if (closed) return;
        closed=true;
        try {
            long finished=timer.getAsLong();
            long authorityNanos=Math.max(0,(authorityChecked ? authorityFinished : finished)-started);
            long downstreamNanos=authorityChecked ? Math.max(0,finished-authorityFinished) : 0;
            var detailJson=new java.util.StringJoiner(",","{","}");
            details.forEach((key,value)->detailJson.add("\""+key.name()+"\":"+value));
            output.accept("{\"event\":\"PATENT_SEARCH_TRANSPORT/v1\",\"taskId\":\""+taskId
                    +"\",\"timingId\":\""+timingId
                    +"\",\"resultSetId\":\""+resultId+"\",\"status\":"+status.getAsInt()
                    +",\"authorityChecked\":"+authorityChecked
                    +",\"downstreamReturned\":"+downstreamReturned
                    +",\"authorityNanos\":"+authorityNanos+",\"downstreamNanos\":"+downstreamNanos
                    +",\"durationNanos\":"+Math.max(0,finished-started)+",\"detailNanos\":"+detailJson+"}");
        } catch (RuntimeException ignored) {
            // Observability must not replace the original response or downstream exception.
        } finally {
            if(previous==null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
