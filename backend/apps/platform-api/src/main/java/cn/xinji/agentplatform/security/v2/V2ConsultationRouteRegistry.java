package cn.xinji.agentplatform.security.v2;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
class V2ConsultationRouteRegistry {
    @Bean
    @ConditionalOnProperty(name = "consultation-v2.development.task-core-enabled", havingValue = "true")
    SmartInitializingSingleton v2TaskRouteRegistry(RequestMappingHandlerMapping mappings, Environment environment,
                                                    DevelopmentBoundaryProperties properties) {
        return () -> {
            Set<String> expected = new HashSet<>(Set.of(
                    "POST /v2/consultation/tasks",
                    "POST /v2/consultation/tasks/{id}/messages",
                    "GET /v2/consultation/tasks/{id}",
                    "POST /v2/consultation/tasks/{id}/cancel",
                    "POST /v2/internal/consultation/attempts/{id}/lease",
                    "POST /v2/internal/consultation/attempts/{id}/checkpoint",
                    "POST /v2/internal/consultation/retention/reconcile"));
            if (environment.acceptsProfiles(Profiles.of("test-synthetic"))) {
                expected.add("GET /v2/consultation/_boundary/identity");
            }
            if (properties.isTextResultEnabled()) {
                expected.add("POST /v2/consultation/tasks/{id}/text-searches");
                expected.add("GET /v2/consultation/tasks/{id}/text-searches/{setId}");
                expected.add("POST /v2/consultation/tasks/{id}/focus");
            }
            if (properties.isEvidenceReadEnabled()) {
                if (!properties.isTextResultEnabled()) {
                    throw new IllegalStateException("V2 evidence read requires text results");
                }
                expected.add("GET /v2/consultation/tasks/{id}/text-searches/{setId}/members/{ordinal}/evidence-preparation");
            }
            if (properties.isFeatureConfirmEnabled()) {
                expected.add("POST /v2/consultation/tasks/{id}/feature-confirmations");
                expected.add("GET /v2/consultation/tasks/{id}/feature-confirmations/{featureRevision}");
            }
            if (properties.isInputPreviewEnabled()) {
                if (!properties.isTextResultEnabled() || !properties.isFeatureConfirmEnabled()
                        || !properties.isComparisonReconfirmEnabled()) {
                    throw new IllegalStateException("V2 input preview requires feature and focus routes");
                }
                expected.add("GET /v2/consultation/tasks/{id}/feature-confirmations/{featureRevision}/focus/{focusSequence}/input-preview");
            }
            if (properties.isPurposeReadEnabled()) {
                if (!properties.isInputPreviewEnabled()) {
                    throw new IllegalStateException("V2 purpose read requires input preview");
                }
                expected.add("GET /v2/consultation/tasks/{id}/analysis-purposes/current");
                expected.add("GET /v2/consultation/tasks/{id}/analysis-purposes/{sequence}");
            }
            if (properties.isPurposeSelectEnabled()) {
                if (!properties.isPurposeReadEnabled()) {
                    throw new IllegalStateException("V2 purpose selection requires read routes");
                }
                expected.add("POST /v2/consultation/tasks/{id}/analysis-purposes");
            }
            if (properties.isWorkStateEnabled()) {
                if (!properties.isTextResultEnabled() || !properties.isFeatureConfirmEnabled()) {
                    throw new IllegalStateException("V2 work state requires result and feature routes");
                }
                expected.add("GET /v2/consultation/tasks/{id}/current-work-state");
            }
            if (properties.isHumanRequestEnabled()) {
                expected.add("POST /v2/consultation/tasks/{id}/human-requests");
                expected.add("GET /v2/consultation/tasks/{id}/human-requests/current");
                expected.add("GET /v2/consultation/tasks/{id}/human-requests/{sequence}");
                expected.add("GET /v2/consultation/tasks/{id}/human-requests/{sequence}/opinion");
                expected.add("GET /v2/consultation/tasks/{id}/human-requests/{sequence}/return");
            }
            if (properties.isHumanReturnEnabled()) {
                if (!properties.isHumanRequestEnabled() || !properties.isHumanAcceptEnabled()
                        || !properties.isHumanOpinionEnabled()) {
                    throw new IllegalStateException("V2 human return requires request, acceptance and opinion");
                }
                expected.add("POST /v2/consultation/tasks/{id}/human-requests/{sequence}/return");
            }
            if (properties.isHumanContextEnabled()) {
                if (!properties.isHumanRequestEnabled() || !properties.isHumanAcceptEnabled()
                        || !properties.isHumanOpinionEnabled() || !properties.isHumanReturnEnabled()) {
                    throw new IllegalStateException("V2 human context requires request through return");
                }
                expected.add("GET /v2/consultation/tasks/{id}/human-requests/{sequence}/context");
            }
            if (properties.isCorrectionEnabled()) {
                expected.add("POST /v2/consultation/tasks/{id}/corrections");
            }
            if (properties.isHumanOpinionEnabled()) {
                if (!properties.isHumanRequestEnabled()) {
                    throw new IllegalStateException("V2 human opinion requires human requests");
                }
                expected.add("POST /v2/consultation/tasks/{id}/human-requests/{sequence}/opinion");
                expected.add("GET /v2/consultation/human-requests/mine");
            }
            if (properties.isHumanAcceptEnabled()) {
                if (!properties.isHumanRequestEnabled()) {
                    throw new IllegalStateException("V2 human acceptance requires human requests");
                }
                expected.add("GET /v2/consultation/human-requests/pending");
                expected.add("POST /v2/consultation/tasks/{id}/human-requests/{sequence}/accept");
            }
            Set<String> actual = new HashSet<>();
            mappings.getHandlerMethods().forEach((route, handler) -> route.getPatternValues().stream()
                    .filter(path -> path.equals("/v2") || path.startsWith("/v2/"))
                    .forEach(path -> route.getMethodsCondition().getMethods()
                            .forEach(method -> actual.add(method.name() + " " + path))));
            if (!expected.equals(actual)) throw new IllegalStateException("V2 controller route registry mismatch");
        };
    }
}
