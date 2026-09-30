package cn.xinji.agentplatform.consultation.taskcore;

import java.time.Instant;
import java.time.ZoneId;

public final class DevTaskExpiry {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private DevTaskExpiry() { }

    public static Instant sixCalendarMonthsAfter(Instant committedChange) {
        if (committedChange == null) throw new IllegalArgumentException("Missing committed change time");
        return committedChange.atZone(BUSINESS_ZONE).plusMonths(6).toInstant();
    }

    public static boolean expired(Instant expiresAt, Instant databaseNow) {
        if (expiresAt == null || databaseNow == null) throw new IllegalArgumentException("Missing database time");
        return !databaseNow.isBefore(expiresAt);
    }
}
