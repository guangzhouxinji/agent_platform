package cn.xinji.agentplatform.security.apikey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Slf4jApiAccessAuditSink implements ApiAccessAuditSink {
    private static final Logger AUDIT = LoggerFactory.getLogger("API_ACCESS_AUDIT");

    @Override public void record(ApiAccessAuditEvent event) {
        AUDIT.info("requestId={} clientId={} keyId={} method={} path={} outcome={} status={} durationMs={}",
                event.requestId(), event.clientId(), event.keyId(), event.method(), event.path(),
                event.outcome(), event.httpStatus(), event.durationMs());
    }
}
