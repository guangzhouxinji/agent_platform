package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.feature-confirm-enabled", havingValue = "true")
final class DevFeatureConfirmationController {
    private final DevFeatureConfirmationStore store;
    private final DevTaskCoreController core;
    private final DevFeatureCandidatePolicy candidates;
    private final DevComparisonInputPolicy comparison;
    private final DevelopmentBoundaryProperties properties;

    DevFeatureConfirmationController(DevFeatureConfirmationStore store, DevTaskCoreController core,
                                     DevFeatureCandidatePolicy candidates, DevComparisonInputPolicy comparison,
                                     DevelopmentBoundaryProperties properties) {
        this.store = store;
        this.core = core;
        this.candidates = candidates;
        this.comparison = comparison;
        this.properties = properties;
    }

    @PostMapping("/v2/consultation/tasks/{id}/feature-confirmations")
    DevFeatureConfirmationStore.Confirmation confirm(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        fields(body);
        JsonNode ids = body.path("featureIds"), manifest = body.path("candidateManifestId");
        if (!ids.isArray() || ids.size() != 3 || !manifest.isTextual()) throw DevTaskCoreFailure.invalid();
        List<String> featureIds = new ArrayList<>();
        for (JsonNode item : ids) {
            if (!item.isTextual()) throw DevTaskCoreFailure.invalid();
            featureIds.add(item.asText());
        }
        return store.confirm(core.actor(request), id, key, number(body, "expectedTaskRevision"),
                number(body, "expectedFeatureRevision"), manifest.asText(), featureIds,
                core.guard(request), sources(request));
    }

    @GetMapping("/v2/consultation/tasks/{id}/feature-confirmations/{featureRevision}")
    DevFeatureConfirmationStore.Confirmation read(HttpServletRequest request, @PathVariable String id,
                                                   @PathVariable long featureRevision) {
        return store.read(core.actor(request), id, featureRevision, core.guard(request), sources(request));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }

    DevFeatureConfirmationStore.SourceGuard sources(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return (human, manifestId, display, now) -> switch (manifestId) {
            case "DEV-CON-FEATURE-CANDIDATES-01" -> candidates.permitted(subject, human, display, now);
            case "DEV-CON-COMPARE-INPUT-01" -> properties.isComparisonReconfirmEnabled()
                    ? comparison.permittedFeatures(subject, human, display, now) : null;
            default -> null;
        };
    }

    private static long number(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw DevTaskCoreFailure.invalid();
        return value.longValue();
    }

    private static void fields(JsonNode body) {
        Set<String> names = Set.of("expectedTaskRevision", "expectedFeatureRevision",
                "candidateManifestId", "featureIds");
        if (body == null || !body.isObject() || body.size() != names.size()) throw DevTaskCoreFailure.invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw DevTaskCoreFailure.invalid();
        });
    }
}
