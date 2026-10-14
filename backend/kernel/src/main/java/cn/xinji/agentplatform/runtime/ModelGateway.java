package cn.xinji.agentplatform.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Model access is shared infrastructure; each domain retains its prompts, approval and output rules. */
public interface ModelGateway {
    record Configuration(String id, String version, String provider, String model, URI endpoint,
                         String protocol, Duration timeout, int maxInputBytes, int maxResponseBytes,
                         Set<String> purposes, Set<String> dataClasses) {
        public Configuration {
            for (String text : new String[]{id,version,provider,model,protocol})
                if (text==null || text.isBlank()) throw new IllegalArgumentException("MODEL_CONFIGURATION_REQUIRED");
            if (endpoint==null || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getFragment()!=null
                    || !("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || timeout==null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5))>0
                    || maxInputBytes<1 || maxInputBytes>1048576 || maxResponseBytes<1 || maxResponseBytes>1048576)
                throw new IllegalArgumentException("MODEL_CONFIGURATION_INVALID");
            purposes=Set.copyOf(purposes);dataClasses=Set.copyOf(dataClasses);
        }
    }
    record Call(String configurationId, String purpose, String dataClass, String promptVersion,
                String qualityVersion, String traceId, String exactRequest) {
        public Call {
            for (String text : new String[]{configurationId,purpose,dataClass,promptVersion,qualityVersion,traceId,exactRequest})
                if (text==null || text.isBlank()) throw new IllegalArgumentException("MODEL_CALL_REQUIRED");
            if (!traceId.matches("[A-Za-z0-9._:/-]{1,180}")) throw new IllegalArgumentException("MODEL_TRACE_INVALID");
        }
    }
    /** Unknown usage is null, not fabricated zero cost/tokens. Domain validators may require usage. */
    record Usage(Long inputTokens, Long outputTokens) { }
    record Result(String configurationVersion, int status, String rawResponse, Usage usage, long elapsedMillis) { }
    Result invoke(Call call);
    final class Failure extends RuntimeException {
        private final String code;
        private final Integer upstreamStatus;
        public Failure(String code) { this(code,null); }
        public Failure(String code,Integer upstreamStatus) {
            super(code);this.code=code;
            if(upstreamStatus!=null && (upstreamStatus<100 || upstreamStatus>599))throw new IllegalArgumentException("INVALID_UPSTREAM_STATUS");
            this.upstreamStatus=upstreamStatus;
        }
        public String code() { return code; }
        public Integer upstreamStatus() { return upstreamStatus; }
    }
}
