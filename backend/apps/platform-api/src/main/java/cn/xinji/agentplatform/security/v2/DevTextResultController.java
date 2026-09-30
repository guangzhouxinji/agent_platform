package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
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
@ConditionalOnProperty(name = "consultation-v2.development.text-result-enabled", havingValue = "true")
final class DevTextResultController {
    private final DevTextResultStore store;
    private final DevTaskCoreController core;
    private final SyntheticUsePolicy uses;

    DevTextResultController(DevTextResultStore store, DevTaskCoreController core, SyntheticUsePolicy uses) {
        this.store = store;
        this.core = core;
        this.uses = uses;
    }

    @PostMapping("/v2/consultation/tasks/{id}/text-searches")
    DevTextResultStore.ResultSet search(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        fields(body, "expectedTaskRevision", "expectedResultSequence");
        Actor human = core.actor(request);
        return store.search(human, id, key, number(body, "expectedTaskRevision"),
                number(body, "expectedResultSequence"), core.guard(request), sources(request));
    }

    @GetMapping("/v2/consultation/tasks/{id}/text-searches/{setId}")
    DevTextResultStore.ResultSet read(HttpServletRequest request, @PathVariable String id,
                                      @PathVariable String setId) {
        return store.read(core.actor(request), id, setId, core.guard(request), sources(request));
    }

    @PostMapping("/v2/consultation/tasks/{id}/focus")
    DevTextResultStore.Focus focus(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        fields(body, "setId", "ordinal", "expectedTaskRevision", "expectedFocusSequence");
        JsonNode setId = body.path("setId");
        if (!setId.isTextual()) throw DevTaskCoreFailure.invalid();
        long ordinal = number(body, "ordinal");
        if (ordinal < 1 || ordinal > Integer.MAX_VALUE) throw DevTaskCoreFailure.invalid();
        return store.focus(core.actor(request), id, key, setId.asText(), (int) ordinal,
                number(body, "expectedTaskRevision"), number(body, "expectedFocusSequence"),
                core.guard(request), sources(request));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }

    private DevTextResultStore.SourceGuard sources(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return (actor, record, version, digest, query, save, display, now) ->
                subject != null && actor.principal().equals(subject.stableSubjectId())
                        && actor.organization().equals(subject.organizationRef())
                        ? uses.claim(subject, record, version, digest, query, save, display, now) : null;
    }

    private static long number(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw DevTaskCoreFailure.invalid();
        return value.longValue();
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject() || body.size() != names.length) throw DevTaskCoreFailure.invalid();
        Set<String> allowed = Set.of(names);
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw DevTaskCoreFailure.invalid();
        });
    }
}
