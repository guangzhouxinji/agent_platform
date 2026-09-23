package cn.xinji.agentplatform.consultation.patentassistant.application;

public final class PatentSourceException extends RuntimeException {
    private final Code code;

    public PatentSourceException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public PatentSourceException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }

    public enum Code {
        TIMEOUT,
        RATE_LIMITED,
        UPSTREAM_UNAUTHENTICATED,
        UPSTREAM_FORBIDDEN,
        UPSTREAM_FAILURE,
        MALFORMED_RESPONSE
    }
}
