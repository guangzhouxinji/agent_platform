package cn.xinji.agentplatform.security.apikey;

import jakarta.servlet.http.HttpServletRequest;

final class ApiScopePolicy {
    private static final String CONSULTATION_ROOT = "/api/v0/consultations";
    private static final String ENFORCEMENT_ROOT = "/api/v0/enforcement/work-items";
    private static final String ENFORCEMENT_SOURCE_ROOT = "/api/v0/development-sources/enforcement";
    private static final String ENFORCEMENT_SOURCE_CONFIGURATION_ROOT = "/api/v0/enforcement/source-configurations";
    private static final String ENFORCEMENT_DISCOVERY_RULE_ROOT = "/api/v0/enforcement/discovery-rules";
    private static final String ENFORCEMENT_DISCOVERY_TASK_ROOT = "/api/v0/enforcement/discovery-tasks";
    private static final String ENFORCEMENT_LEAD_ROOT = "/api/v0/enforcement/leads";
    private static final String ENFORCEMENT_EVIDENCE_ROOT = "/api/v0/enforcement/evidence";

    boolean protects(HttpServletRequest request) {
        String path = request.getRequestURI();
        return within(path, CONSULTATION_ROOT) || within(path, ENFORCEMENT_ROOT)
                || within(path, ENFORCEMENT_SOURCE_ROOT) || within(path, ENFORCEMENT_SOURCE_CONFIGURATION_ROOT)
                || within(path, ENFORCEMENT_DISCOVERY_RULE_ROOT)
                || within(path, ENFORCEMENT_DISCOVERY_TASK_ROOT) || within(path, ENFORCEMENT_LEAD_ROOT)
                || within(path, ENFORCEMENT_EVIDENCE_ROOT);
    }

    String requiredScope(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (within(path, CONSULTATION_ROOT)) {
            if ("GET".equals(request.getMethod()) && path.contains("/source-links/")) return "source:read";
            return "GET".equals(request.getMethod()) ? "consultation:read" : "consultation:submit";
        }
        if (within(path, ENFORCEMENT_SOURCE_ROOT)) return "source:read";
        if (within(path, ENFORCEMENT_SOURCE_CONFIGURATION_ROOT)) {
            return "GET".equals(request.getMethod()) ? "enforcement:read" : "enforcement:work";
        }
        if (within(path, ENFORCEMENT_DISCOVERY_RULE_ROOT)) {
            if ("GET".equals(request.getMethod())) return "enforcement:read";
            if (path.endsWith("/publish") || path.endsWith("/disable")) return "enforcement:review";
            return "enforcement:work";
        }
        if (within(path, ENFORCEMENT_DISCOVERY_TASK_ROOT)) {
            return "GET".equals(request.getMethod()) ? "enforcement:read" : "enforcement:work";
        }
        if (within(path, ENFORCEMENT_LEAD_ROOT)) {
            if ("GET".equals(request.getMethod())) return "enforcement:read";
            if (path.endsWith("/start-verification") || path.endsWith("/duplicate-links")
                    || path.endsWith("/parse-tasks") || path.endsWith("/revisions")) {
                return "enforcement:work";
            }
            return "enforcement:review";
        }
        if (within(path, ENFORCEMENT_EVIDENCE_ROOT)) {
            if ("GET".equals(request.getMethod())) return "enforcement:read";
            if (path.endsWith("/status")) return "enforcement:review";
            return "enforcement:work";
        }
        if ("GET".equals(request.getMethod()) && path.contains("/source-links/")) return "source:read";
        if ("GET".equals(request.getMethod())) return "enforcement:read";
        if (path.contains("/auxiliary-analyses/") && path.endsWith("/actions")) return "enforcement:review";
        return "enforcement:work";
    }

    boolean requiresOrganization(HttpServletRequest request) {
        return ("POST".equals(request.getMethod()) && ENFORCEMENT_ROOT.equals(request.getRequestURI()))
                || within(request.getRequestURI(), ENFORCEMENT_SOURCE_CONFIGURATION_ROOT)
                || within(request.getRequestURI(), ENFORCEMENT_DISCOVERY_RULE_ROOT)
                || within(request.getRequestURI(), ENFORCEMENT_DISCOVERY_TASK_ROOT)
                || within(request.getRequestURI(), ENFORCEMENT_LEAD_ROOT)
                || within(request.getRequestURI(), ENFORCEMENT_EVIDENCE_ROOT);
    }

    private static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }
}
