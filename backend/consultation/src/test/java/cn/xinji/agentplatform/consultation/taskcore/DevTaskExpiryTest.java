package cn.xinji.agentplatform.consultation.taskcore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DevTaskExpiryTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test void monthEndAndLeapYearUseShanghaiCalendarAndRejectAtExactBoundary() {
        Instant first = local(2026, 8, 31);
        Instant leap = local(2027, 8, 31);
        assertThat(DevTaskExpiry.sixCalendarMonthsAfter(first)).isEqualTo(local(2027, 2, 28));
        assertThat(DevTaskExpiry.sixCalendarMonthsAfter(leap)).isEqualTo(local(2028, 2, 29));
        Instant boundary = DevTaskExpiry.sixCalendarMonthsAfter(first);
        assertThat(DevTaskExpiry.expired(boundary, boundary.minusNanos(1))).isFalse();
        assertThat(DevTaskExpiry.expired(boundary, boundary)).isTrue();
    }

    private static Instant local(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 10, 0).atZone(SHANGHAI).toInstant();
    }
}
