package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
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
@ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
final class DevHumanRequestController {
    private final DevHumanRequestStore store;
    private final DevTaskCoreController core;
    private final DevHumanGapPolicy inputs;

    DevHumanRequestController(DevHumanRequestStore store, DevTaskCoreController core, DevHumanGapPolicy inputs) {
        this.store = store;
        this.core = core;
        this.inputs = inputs;
    }

    @PostMapping("/v2/consultation/tasks/{id}/human-requests")
    DevHumanRequestStore.Request create(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 3
                || !body.has("expectedTaskRevision") || !body.has("expectedRequestSequence")
                || !body.has("requestInputId")) throw DevTaskCoreFailure.invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("expectedTaskRevision", "expectedRequestSequence", "requestInputId").contains(name)) {
                throw DevTaskCoreFailure.invalid();
            }
        });
        if (!body.path("expectedTaskRevision").isIntegralNumber()
                || !body.path("expectedTaskRevision").canConvertToLong()
                || !body.path("expectedRequestSequence").isIntegralNumber()
                || !body.path("expectedRequestSequence").canConvertToLong()
                || !body.path("requestInputId").isTextual()) throw DevTaskCoreFailure.invalid();
        return store.create(core.actor(request), id, key,
                body.path("expectedTaskRevision").longValue(),
                body.path("expectedRequestSequence").longValue(), body.path("requestInputId").asText(),
                core.guard(request), source(request));
    }

    @GetMapping("/v2/consultation/tasks/{id}/human-requests/current")
    DevHumanRequestStore.Current current(HttpServletRequest request, @PathVariable String id) {
        return store.current(core.actor(request), id, core.guard(request), source(request));
    }

    @GetMapping("/v2/consultation/tasks/{id}/human-requests/{sequence}")
    DevHumanRequestStore.Request read(HttpServletRequest request, @PathVariable String id,
                                      @PathVariable long sequence) {
        return store.read(core.actor(request), id, sequence, core.guard(request), source(request));
    }

    private DevHumanRequestStore.SourceGuard source(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return (human, save, display, now) -> inputs.permitted(subject, human, save, display, now);
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
