package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.List;
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
@ConditionalOnProperty(name = "consultation-v2.development.human-opinion-enabled", havingValue = "true")
final class DevHumanOpinionSubmissionController {
    private final DevHumanOpinionStore store;
    private final DevTaskCoreController core;
    private final DevHumanGapPolicy inputs;

    DevHumanOpinionSubmissionController(DevHumanOpinionStore store, DevTaskCoreController core,
                                        DevHumanGapPolicy inputs) {
        this.store = store; this.core = core; this.inputs = inputs;
    }

    @GetMapping("/v2/consultation/human-requests/mine")
    List<DevHumanOpinionStore.Pending> mine(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return store.mine(core.actor(request), core.guard(request),
                (human, save, display, now) -> inputs.permitted(subject, human, save, display, now),
                (human, save, display, now) -> inputs.opinionPermitted(subject, human, save, display, now));
    }

    @PostMapping("/v2/consultation/tasks/{id}/human-requests/{sequence}/opinion")
    DevHumanOpinionStore.Opinion submit(HttpServletRequest request, @PathVariable String id,
            @PathVariable long sequence, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 4
                || !body.has("expectedTaskRevision") || !body.has("expectedRequestSequence")
                || !body.has("requestId") || !body.has("opinionInputId")) throw DevTaskCoreFailure.invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("expectedTaskRevision", "expectedRequestSequence", "requestId", "opinionInputId")
                    .contains(name)) throw DevTaskCoreFailure.invalid();
        });
        if (!body.path("expectedTaskRevision").isIntegralNumber()
                || !body.path("expectedTaskRevision").canConvertToLong()
                || !body.path("expectedRequestSequence").isIntegralNumber()
                || !body.path("expectedRequestSequence").canConvertToLong()
                || !body.path("requestId").isTextual() || !body.path("opinionInputId").isTextual()) {
            throw DevTaskCoreFailure.invalid();
        }
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return store.submit(core.actor(request), id, sequence, key,
                body.path("expectedTaskRevision").longValue(), body.path("expectedRequestSequence").longValue(),
                body.path("requestId").asText(), body.path("opinionInputId").asText(), core.guard(request),
                (human, save, display, now) -> inputs.opinionPermitted(subject, human, save, display, now));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
