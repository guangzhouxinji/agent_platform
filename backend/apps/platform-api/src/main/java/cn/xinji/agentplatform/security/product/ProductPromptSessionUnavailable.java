package cn.xinji.agentplatform.security.product;

/** Current session no longer qualifies; the HTTP client must reauthenticate. */
final class ProductPromptSessionUnavailable extends org.springframework.security.oauth2.jwt.JwtException {
    ProductPromptSessionUnavailable(){super("Prompt session unavailable");}
}
