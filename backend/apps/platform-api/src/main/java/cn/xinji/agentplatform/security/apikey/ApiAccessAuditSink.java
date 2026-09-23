package cn.xinji.agentplatform.security.apikey;

interface ApiAccessAuditSink {
    void record(ApiAccessAuditEvent event);

    record ApiAccessAuditEvent(String requestId, String clientId, String keyId, String method,
                               String path, String outcome, int httpStatus, long durationMs) { }
}
