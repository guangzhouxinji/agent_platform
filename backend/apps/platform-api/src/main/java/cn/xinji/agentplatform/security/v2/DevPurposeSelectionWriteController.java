package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
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
@ConditionalOnProperty(name = "consultation-v2.development.purpose-select-enabled", havingValue = "true")
final class DevPurposeSelectionWriteController {
    private static final Set<String> FIELDS = Set.of("expectedTaskRevision", "featureRevision",
            "focusSequence", "setId", "ordinal", "analysisPurpose");
    private final DevPurposeSelectionStore store;
    private final DevPurposeSelectionController reader;
    private final DevTaskCoreController core;

    DevPurposeSelectionWriteController(DevPurposeSelectionStore store, DevPurposeSelectionController reader,
                                       DevTaskCoreController core) {
        this.store = store;
        this.reader = reader;
        this.core = core;
    }

    @PostMapping("/v2/consultation/tasks/{id}/analysis-purposes")
    DevPurposeSelectionStore.Selection select(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw DevTaskCoreFailure.invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) throw DevTaskCoreFailure.invalid();
        });
        if (!body.path("setId").isTextual() || !body.path("analysisPurpose").isTextual()) {
            throw DevTaskCoreFailure.invalid();
        }
        long ordinal = number(body, "ordinal");
        if (ordinal < 1 || ordinal > Integer.MAX_VALUE) throw DevTaskCoreFailure.invalid();
        return store.select(core.actor(request), id, key, number(body, "expectedTaskRevision"),
                number(body, "featureRevision"), number(body, "focusSequence"), body.path("setId").asText(),
                (int) ordinal, body.path("analysisPurpose").asText(), core.guard(request),
                reader.check(request, id));
    }

    private static long number(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw DevTaskCoreFailure.invalid();
        return value.longValue();
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
