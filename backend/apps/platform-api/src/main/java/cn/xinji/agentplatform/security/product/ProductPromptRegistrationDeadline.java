package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.unavailable;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One registration attempt's engineering budget; never a persisted business deadline. */
final class ProductPromptRegistrationDeadline {
    static final int CLEANUP_MILLIS = 5000;
    private static final long TOTAL_NANOS = Duration.ofSeconds(120).toNanos();
    private final Clock clock;
    private final LongSupplier nanos;
    private final long started;
    private final Instant until;

    ProductPromptRegistrationDeadline(Clock clock, Instant until) {
        this(clock, until, System::nanoTime);
    }
    ProductPromptRegistrationDeadline(Clock clock, Instant until, LongSupplier nanos) {
        this.clock=Objects.requireNonNull(clock);this.until=Objects.requireNonNull(until);
        this.nanos=Objects.requireNonNull(nanos);started=nanos.getAsLong();
        remaining(until);
    }
    Instant until() { return until; }
    long remaining(Instant cutoff) {
        Objects.requireNonNull(cutoff);
        long elapsed=nanos.getAsLong()-started;
        if(elapsed<0 || elapsed>=TOTAL_NANOS)throw unavailable();
        Instant effective=cutoff.isBefore(until)?cutoff:until;
        long wall=Duration.between(clock.instant(),effective).toMillis();
        long monotonic=(TOTAL_NANOS-elapsed)/1_000_000;
        long result=Math.min(wall,monotonic);
        if(result<=0)throw unavailable();
        return result;
    }
    void beforeBorrow(HikariDataSource source,Instant cutoff) {
        validate(source,1);
        long required=source.getConnectionTimeout()+source.getValidationTimeout()+CLEANUP_MILLIS;
        if(remaining(cutoff)<=required)throw unavailable();
    }
    int networkMillis(Instant cutoff) { return (int)Math.min(5000,remaining(cutoff)); }
    int querySeconds(Instant cutoff) {
        long seconds=Math.min(5,remaining(cutoff)/1000);
        if(seconds<1)throw unavailable();
        return (int)seconds;
    }
    int transactionSeconds(Instant cutoff) {
        long seconds=Math.min(30,remaining(cutoff)/1000);
        if(seconds<1)throw unavailable();
        return (int)seconds;
    }
    static void validate(HikariDataSource source,int capacity) {
        if(source==null || source.isClosed() || source.getHikariPoolMXBean()==null
                || source.isAllowPoolSuspension() || source.getMaximumPoolSize()<capacity
                || source.getConnectionTimeout()<=0 || source.getConnectionTimeout()>30000
                || source.getValidationTimeout()<=0
                || source.getValidationTimeout()>source.getConnectionTimeout())throw unavailable();
    }
}
