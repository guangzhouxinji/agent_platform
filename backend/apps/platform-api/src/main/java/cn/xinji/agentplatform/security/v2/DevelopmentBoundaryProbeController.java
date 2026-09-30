package cn.xinji.agentplatform.security.v2;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.enabled", havingValue = "true")
final class DevelopmentBoundaryProbeController {
    private final SyntheticUsePolicy uses;
    private final Clock clock;

    DevelopmentBoundaryProbeController(SyntheticUsePolicy uses, Clock clock) {
        this.uses = uses;
        this.clock = clock;
    }

    @GetMapping("/v2/consultation/_boundary/identity")
    ResponseEntity<Map<String, String>> identity(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        boolean allowed = uses.allows(subject, "DEV-CON-SYN-01", "P1", "V1",
                "claimText", SyntheticUsePolicy.Use.QUERY, clock.instant());
        return ResponseEntity.ok(Map.of("code", allowed ? "ALLOW" : "DENY",
                "traceId", UUID.randomUUID().toString()));
    }
}
