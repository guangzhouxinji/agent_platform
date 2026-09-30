package cn.xinji.agentplatform.security.v2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.lang.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class V2ConsultationBoundaryFilter extends OncePerRequestFilter {
    public static final String SUBJECT_ATTRIBUTE = "agentPlatform.v2.trustedSubject";
    private static final String ROOT = "/v2/consultation";
    private static final String PROBE = ROOT + "/_boundary/identity";
    private static final String UUID_PATH = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private static final Pattern TASK = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH);
    private static final Pattern TASK_ACTION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH + "/(messages|cancel)");
    private static final Pattern CORRECTION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH + "/corrections");
    private static final Pattern TEXT_SEARCHES = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH + "/text-searches");
    private static final Pattern TEXT_SET = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/text-searches/" + UUID_PATH);
    private static final Pattern EVIDENCE_READ = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/text-searches/" + UUID_PATH + "/members/[1-9][0-9]*/evidence-preparation");
    private static final Pattern FOCUS = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH + "/focus");
    private static final Pattern FEATURE_CONFIRM = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/feature-confirmations");
    private static final Pattern FEATURE_REVISION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/feature-confirmations/[1-9][0-9]*");
    private static final Pattern INPUT_PREVIEW = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/feature-confirmations/[1-9][0-9]*/focus/[1-9][0-9]*/input-preview");
    private static final Pattern PURPOSE_SELECT = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/analysis-purposes");
    private static final Pattern PURPOSE_CURRENT = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/analysis-purposes/current");
    private static final Pattern PURPOSE_REVISION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/analysis-purposes/[1-9][0-9]*");
    private static final Pattern WORK_STATE = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/current-work-state");
    private static final Pattern HUMAN_REQUEST = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests");
    private static final Pattern HUMAN_REQUEST_CURRENT = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/current");
    private static final Pattern HUMAN_REQUEST_REVISION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/[1-9][0-9]*");
    private static final Pattern HUMAN_ACCEPT = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/[1-9][0-9]*/accept");
    private static final Pattern HUMAN_OPINION = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/[1-9][0-9]*/opinion");
    private static final Pattern HUMAN_RETURN = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/[1-9][0-9]*/return");
    private static final Pattern HUMAN_CONTEXT = Pattern.compile("/v2/consultation/tasks/" + UUID_PATH
            + "/human-requests/[1-9][0-9]*/context");
    private static final Pattern ATTEMPT = Pattern.compile("/v2/internal/consultation/attempts/" + UUID_PATH + "/(lease|checkpoint)");
    private final DevelopmentBoundaryProperties properties;
    private final DevelopmentSubjectPolicy subjects;
    private final Clock clock;
    private final boolean testProfile;
    private final boolean developmentProfile;
    private final boolean productIdentityEnabled;

    V2ConsultationBoundaryFilter(DevelopmentBoundaryProperties properties,
                                 @Nullable DevelopmentSubjectPolicy subjects,
                                 Environment environment, Clock clock) {
        this.properties = properties;
        this.subjects = subjects;
        this.clock = clock;
        this.testProfile = environment.acceptsProfiles(Profiles.of("test-synthetic"));
        this.developmentProfile = environment.acceptsProfiles(Profiles.of("test-synthetic"));
        this.productIdentityEnabled = environment.getProperty("product.identity.enabled", Boolean.class, false);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().toLowerCase(Locale.ROOT);
        if (productIdentityEnabled && (path.equals("/v2/identity") || path.startsWith("/v2/identity/")))
            return true;
        if (productIdentityEnabled && "GET".equals(request.getMethod()) && TASK.matcher(request.getRequestURI()).matches())
            return true;
        if (productIdentityEnabled && "POST".equals(request.getMethod())
                && request.getRequestURI().matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/goals/confirm"))
            return true;
        if (productIdentityEnabled && "GET".equals(request.getMethod())
                && request.getRequestURI().matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/goals/current"))
            return true;
        if (productIdentityEnabled && "POST".equals(request.getMethod())
                && request.getRequestURI().matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/cancel"))
            return true;
        if (productIdentityEnabled && "POST".equals(request.getMethod())
                && request.getRequestURI().matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/sessions"))
            return true;
        if (productIdentityEnabled && "POST".equals(request.getMethod())
                && request.getRequestURI().matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/executions"))
            return true;
        return !path.equals("/v2") && !path.startsWith("/v2/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!properties.isEnabled() || subjects == null || !developmentProfile
                || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        DevelopmentBoundaryProperties.Kind kind = routeKind(request.getMethod(), path);
        if (kind == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        var subject = subjects.authenticate(request.getHeader("Authorization"), clock.instant());
        if (subject == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!subjects.permits(subject, kind,
                subject.stableSubjectId(), clock.instant())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        request.setAttribute(SUBJECT_ATTRIBUTE, subject);
        chain.doFilter(request, response);
    }

    private DevelopmentBoundaryProperties.Kind routeKind(String method, String path) {
        if (testProfile && "GET".equals(method) && PROBE.equals(path)) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (!properties.isTaskCoreEnabled()) return null;
        if (properties.isCorrectionEnabled() && "POST".equals(method)
                && CORRECTION.matcher(path).matches()) return DevelopmentBoundaryProperties.Kind.HUMAN;
        if (properties.isHumanContextEnabled() && properties.isHumanRequestEnabled()
                && "GET".equals(method) && HUMAN_CONTEXT.matcher(path).matches()) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isHumanRequestEnabled() && ("GET".equals(method)
                || (properties.isHumanReturnEnabled() && "POST".equals(method)))
                && HUMAN_RETURN.matcher(path).matches()) return DevelopmentBoundaryProperties.Kind.HUMAN;
        if (properties.isHumanOpinionEnabled() && properties.isHumanRequestEnabled()
                && "GET".equals(method) && "/v2/consultation/human-requests/mine".equals(path)) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isHumanRequestEnabled() && (("GET".equals(method)
                || (properties.isHumanOpinionEnabled() && "POST".equals(method)))
                && HUMAN_OPINION.matcher(path).matches())) return DevelopmentBoundaryProperties.Kind.HUMAN;
        if (properties.isHumanRequestEnabled() && properties.isHumanAcceptEnabled()
                && (("GET".equals(method) && "/v2/consultation/human-requests/pending".equals(path))
                || ("POST".equals(method) && HUMAN_ACCEPT.matcher(path).matches()))) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isHumanRequestEnabled() && (("POST".equals(method)
                && HUMAN_REQUEST.matcher(path).matches()) || ("GET".equals(method)
                && (HUMAN_REQUEST_CURRENT.matcher(path).matches()
                || HUMAN_REQUEST_REVISION.matcher(path).matches())))) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isFeatureConfirmEnabled() && (("POST".equals(method)
                && FEATURE_CONFIRM.matcher(path).matches())
                || ("GET".equals(method) && FEATURE_REVISION.matcher(path).matches()))) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isInputPreviewEnabled() && properties.isFeatureConfirmEnabled()
                && properties.isComparisonReconfirmEnabled() && properties.isTextResultEnabled()
                && "GET".equals(method) && INPUT_PREVIEW.matcher(path).matches()) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isPurposeReadEnabled() && "GET".equals(method)
                && (PURPOSE_CURRENT.matcher(path).matches() || PURPOSE_REVISION.matcher(path).matches())) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isPurposeSelectEnabled() && properties.isPurposeReadEnabled()
                && "POST".equals(method) && PURPOSE_SELECT.matcher(path).matches()) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isWorkStateEnabled() && properties.isTextResultEnabled()
                && properties.isFeatureConfirmEnabled() && "GET".equals(method)
                && WORK_STATE.matcher(path).matches()) return DevelopmentBoundaryProperties.Kind.HUMAN;
        if (properties.isTextResultEnabled() && (("POST".equals(method)
                && (TEXT_SEARCHES.matcher(path).matches() || FOCUS.matcher(path).matches()))
                || ("GET".equals(method) && TEXT_SET.matcher(path).matches()))) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (properties.isTextResultEnabled() && properties.isEvidenceReadEnabled()
                && "GET".equals(method) && EVIDENCE_READ.matcher(path).matches()) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if (("POST".equals(method) && (path.equals(ROOT + "/tasks")
                || TASK_ACTION.matcher(path).matches()))
                || ("GET".equals(method) && TASK.matcher(path).matches())) {
            return DevelopmentBoundaryProperties.Kind.HUMAN;
        }
        if ("POST".equals(method) && ATTEMPT.matcher(path).matches()) {
            return DevelopmentBoundaryProperties.Kind.AGENT;
        }
        if ("POST".equals(method) && path.equals("/v2/internal/consultation/retention/reconcile")) {
            return DevelopmentBoundaryProperties.Kind.JOB;
        }
        return null;
    }
}
