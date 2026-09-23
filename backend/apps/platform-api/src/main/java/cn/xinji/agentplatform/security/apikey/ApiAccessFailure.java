package cn.xinji.agentplatform.security.apikey;

final class ApiAccessFailure extends RuntimeException {
    private final String code;
    private final int status;
    private final String keyId;
    private final String clientId;

    ApiAccessFailure(String code, int status) { this(code, status, "-", "-"); }
    ApiAccessFailure(String code, int status, String keyId, String clientId) {
        super(code);
        this.code = code;
        this.status = status;
        this.keyId = keyId;
        this.clientId = clientId;
    }
    String code() { return code; }
    int status() { return status; }
    String keyId() { return keyId; }
    String clientId() { return clientId; }
}
