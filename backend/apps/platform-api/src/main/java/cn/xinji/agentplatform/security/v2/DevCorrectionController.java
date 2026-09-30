package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevCorrectionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.correction-enabled", havingValue = "true")
final class DevCorrectionController {
    private final DevCorrectionStore store;
    private final DevTaskCoreController core;
    private final DevCorrectionInputPolicy inputs;

    DevCorrectionController(DevCorrectionStore store, DevTaskCoreController core, DevCorrectionInputPolicy inputs) {
        this.store = store;
        this.core = core;
        this.inputs = inputs;
    }

    @PostMapping("/v2/consultation/tasks/{id}/corrections")
    CommandResult correct(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 4) throw DevTaskCoreFailure.invalid();
        Set<String> expectedFields = Set.of("expectedVersion", "sourceRevision", "userMessage", "newText");
        body.fieldNames().forEachRemaining(field -> {
            if (!expectedFields.contains(field)) throw DevTaskCoreFailure.invalid();
        });
        JsonNode expected = body.path("expectedVersion"), sourceRevision = body.path("sourceRevision");
        if (!expected.isIntegralNumber() || !expected.canConvertToLong()
                || !sourceRevision.isIntegralNumber() || !sourceRevision.canConvertToLong()
                || !body.path("userMessage").isTextual() || !body.path("newText").isTextual()) {
            throw DevTaskCoreFailure.invalid();
        }
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        var signed = inputs.permitted(subject, Instant.now());
        if (signed == null) throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        return store.correct(core.actor(request), id, key, expected.longValue(), sourceRevision.longValue(),
                body.path("newText").asText(), body.path("userMessage").asText(),
                new DevCorrectionStore.Source(signed.manifestId(), signed.manifestSha(), signed.oldSha(),
                        signed.newSha(), signed.utteranceSha(), signed.oldText(), signed.newText(),
                signed.utterance()), core.guard(request));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
