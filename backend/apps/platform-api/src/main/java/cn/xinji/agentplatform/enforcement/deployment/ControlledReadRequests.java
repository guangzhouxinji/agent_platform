package cn.xinji.agentplatform.enforcement.deployment;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Set;

/** Exact allowlist. Does not inspect login passwords or retain OAuth credentials. */
final class ControlledReadRequests {
    private final ControlledReadPolicy policy;
    ControlledReadRequests(ControlledReadPolicy policy) { this.policy = policy; }

    String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!request.isSecure() || !safePath(path) || !request.getContextPath().isEmpty()
                || request.getHeader("X-HTTP-Method-Override") != null
                || request.getHeader("X-Method-Override") != null
                || request.getHeader("X-HTTP-Method") != null) return null;
        String method = request.getMethod();
        if ("GET".equals(method)) return get(request, path);
        if (!"POST".equals(method) || !emptyQuery(request)) return null;
        if ("/login".equals(path)) return "LOGIN_SUBMIT"; // Spring alone reads password/CSRF.
        if ("/v2/identity/logout".equals(path)) return "CURRENT_SESSION_LOGOUT";
        if ("/oauth2/token".equals(path) && form(request)
                && keys(request, Set.of("grant_type","client_id","redirect_uri","code","code_verifier"))
                && one(request,"grant_type","authorization_code")
                && one(request,"client_id",policy.clientId())
                && one(request,"redirect_uri",policy.callbackUri())
                && count(request,"code",1) && count(request,"code_verifier",1)) return "TOKEN_EXCHANGE";
        if ("/oauth2/authorize".equals(path) && policy.allowConsent() && form(request)
                && keys(request,Set.of("client_id","state","scope","_csrf"))
                && one(request,"client_id",policy.clientId()) && one(request,"scope","openid")
                && count(request,"state",1)) return "EXISTING_CLIENT_CONSENT";
        return null;
    }

    private String get(HttpServletRequest request, String path) {
        if (path.equals(policy.callbackPath()) && callbackQuery(request)) return "DOCUMENT_PAGE";
        if (policy.assets().contains(path) && emptyQuery(request)) return "FIXED_STATIC_ASSET";
        if ("/login".equals(path) && keys(request,Set.of("error"))) return "LOGIN_FORM";
        if ("/oauth2/authorize".equals(path) && authorization(request)) return "AUTHORIZE";
        if (!emptyQuery(request)) {
            if (!one(request,"revision",Long.toString(policy.documentRevision()))
                    || !keys(request,Set.of("revision"))) return null;
            if (path.equals(policy.documentPath())) return "DOCUMENT_VERSION";
            if (path.equals(policy.documentPath()+"/candidates")) return "EXISTING_CANDIDATES";
            if (path.equals(policy.documentPath()+"/reviews")) return "EXISTING_REVIEWS";
            return null;
        }
        if (path.equals(policy.documentPath()+"/history")) return "DOCUMENT_HISTORY";
        if (path.equals(policy.jobPackPath())) return "EXISTING_JOB_PACK";
        return switch (path) {
            case "/v2/identity/me" -> "CURRENT_IDENTITY";
            case "/.well-known/oauth-authorization-server", "/.well-known/openid-configuration" -> "DISCOVERY";
            case "/oauth2/jwks" -> "PUBLIC_KEYS";
            default -> null;
        };
    }

    private boolean authorization(HttpServletRequest request) {
        return keys(request,Set.of("response_type","client_id","redirect_uri","scope","state",
                "code_challenge","code_challenge_method"))
                && one(request,"response_type","code") && one(request,"client_id",policy.clientId())
                && one(request,"redirect_uri",policy.callbackUri()) && one(request,"scope","openid")
                && one(request,"code_challenge_method","S256") && count(request,"state",1)
                && count(request,"code_challenge",1)
                && request.getParameter("code_challenge").matches("[A-Za-z0-9_-]{43}");
    }
    private boolean callbackQuery(HttpServletRequest request) {
        return keys(request,Set.of("code","state","error","error_description"))
                && !(request.getParameterMap().containsKey("code") && request.getParameterMap().containsKey("error"));
    }
    private static boolean keys(HttpServletRequest request, Set<String> allowed) {
        return request.getParameterMap().entrySet().stream()
                .allMatch(e -> allowed.contains(e.getKey()) && e.getValue().length == 1);
    }
    private static boolean one(HttpServletRequest request, String key, String expected) {
        String[] values = request.getParameterValues(key);
        return values != null && values.length == 1 && expected.equals(values[0]);
    }
    private static boolean count(HttpServletRequest request, String key, int expected) {
        String[] values = request.getParameterValues(key);
        return values != null && values.length == expected;
    }
    private static boolean form(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded");
    }
    private static boolean emptyQuery(HttpServletRequest request) {
        return request.getQueryString() == null || request.getQueryString().isEmpty();
    }
    static boolean safePath(String path) {
        return path != null && path.startsWith("/") && !path.contains("%") && !path.contains("\\")
                && !path.contains(";") && !path.contains("//")
                && path.chars().noneMatch(c -> c < 32 || c == 127)
                && Arrays.stream(path.split("/",-1)).noneMatch(s -> s.equals(".") || s.equals(".."));
    }
}
