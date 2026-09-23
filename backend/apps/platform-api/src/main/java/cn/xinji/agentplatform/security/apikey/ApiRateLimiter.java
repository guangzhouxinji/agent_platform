package cn.xinji.agentplatform.security.apikey;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

final class ApiRateLimiter {
    private final ConcurrentHashMap<String, Usage> usages = new ConcurrentHashMap<>();

    boolean allow(ApiCredentialRegistry.Credential credential, Instant now) {
        return usages.computeIfAbsent(credential.keyId(), ignored -> new Usage())
                .allow(now, credential.requestsPerMinute(), credential.requestsPerDay());
    }

    private static final class Usage {
        private Instant minuteStart;
        private int minuteCount;
        private Instant dayStart;
        private int dayCount;

        synchronized boolean allow(Instant now, int minuteLimit, int dayLimit) {
            Instant currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
            Instant currentDay = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            if (!currentMinute.equals(minuteStart)) { minuteStart = currentMinute; minuteCount = 0; }
            if (!currentDay.equals(dayStart)) { dayStart = currentDay; dayCount = 0; }
            if (minuteCount >= minuteLimit || dayCount >= dayLimit) return false;
            minuteCount++;
            dayCount++;
            return true;
        }
    }
}
