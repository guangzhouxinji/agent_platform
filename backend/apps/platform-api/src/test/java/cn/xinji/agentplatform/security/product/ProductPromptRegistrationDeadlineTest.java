package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProductPromptRegistrationDeadlineTest {
    private final Instant now=Instant.parse("2026-10-14T00:00:00Z");
    private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);

    @Test void monotonicBudgetCannotBeExtendedByLongBusinessDeadline() {
        var nanos=new AtomicLong(100);
        var deadline=new ProductPromptRegistrationDeadline(clock,now.plusSeconds(3600),nanos::get);
        assertEquals(120000,deadline.remaining(now.plusSeconds(7200)));
        nanos.addAndGet(119_500_000_000L);
        assertEquals(500,deadline.remaining(now.plusSeconds(7200)));
        assertThrows(IllegalStateException.class,()->deadline.querySeconds(now.plusSeconds(7200)));
        nanos.addAndGet(500_000_000L);
        assertThrows(IllegalStateException.class,()->deadline.remaining(now.plusSeconds(7200)));
    }
    @Test void everyOperationUsesTheEarlierCutoffAndNeverZeroQueryTimeout() {
        var deadline=new ProductPromptRegistrationDeadline(clock,now.plusSeconds(90),()->100);
        assertEquals(5,deadline.querySeconds(now.plusSeconds(10)));
        assertEquals(1,deadline.querySeconds(now.plusMillis(1999)));
        assertEquals(400,deadline.networkMillis(now.plusMillis(400)));
        assertThrows(IllegalStateException.class,()->deadline.querySeconds(now.plusMillis(999)));
        assertThrows(IllegalStateException.class,()->deadline.remaining(now));
        assertEquals(30,deadline.transactionSeconds(now.plusSeconds(90)));
        assertEquals(2,deadline.transactionSeconds(now.plusSeconds(2)));
    }
    @Test void monotonicCounterWrapKeepsAValidShortElapsedInterval() {
        var nanos=new AtomicLong(Long.MAX_VALUE-10);
        var deadline=new ProductPromptRegistrationDeadline(clock,now.plusSeconds(3600),nanos::get);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(119000,deadline.remaining(now.plusSeconds(3600)));
    }
}
