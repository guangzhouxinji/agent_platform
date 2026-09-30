package cn.xinji.agentplatform.consultation.fixture;

import cn.xinji.agentplatform.consultation.patenttask.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Local interaction fixture, never a production consultation or patent source endpoint. */
@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "patent.fixture.enabled", havingValue = "true")
@RequestMapping("/api/v0/consultations/patent-fixture")
public class PatentFixtureFlowController {
    private final PatentConsultationFixtureFlow flow;
    private final Map<String, Receipt> receipts = new HashMap<>();

    public PatentFixtureFlowController(PatentConsultationFixtureFlow flow) { this.flow = flow; }

    @PostMapping
    public PatentConsultationFixtureFlow.View start(@RequestBody Start body,
                                                    @RequestHeader("Idempotency-Key") String key,
                                                    HttpServletRequest request) {
        local(request);
        if (body == null) throw new IllegalArgumentException("input is required");
        synchronized (flow) {
            return once("create:" + key, body, () -> flow.start(body.description(), body.scope(), body.features()));
        }
    }

    @GetMapping("/{id}")
    public PatentConsultationFixtureFlow.View read(@PathVariable String id, HttpServletRequest request) {
        local(request);
        return flow.view(id);
    }

    @PostMapping("/{id}/features")
    public PatentConsultationFixtureFlow.View supplement(@PathVariable String id, @RequestBody Features body,
                                                          @RequestHeader("Idempotency-Key") String key,
                                                          HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "features", body, request,
                () -> flow.supplementFeatures(id, body.features()));
    }

    @PostMapping("/{id}/searches")
    public PatentConsultationFixtureFlow.View search(@PathVariable String id, @RequestBody Search body,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "search", body, request,
                () -> flow.search(id, body.query()));
    }

    @PostMapping("/{id}/selections")
    public PatentConsultationFixtureFlow.View select(@PathVariable String id, @RequestBody Select body,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "select", body, request,
                () -> flow.select(id, body.resultVersion(), body.ordinal()));
    }

    @PostMapping("/{id}/comparisons")
    public PatentConsultationFixtureFlow.View compare(@PathVariable String id, @RequestBody Version body,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "compare", body, request, () -> flow.compare(id));
    }

    @PostMapping("/{id}/corrections/features/{featureId}")
    public PatentConsultationFixtureFlow.View correct(@PathVariable String id, @PathVariable String featureId,
                                                       @RequestBody Correction body,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "correct:" + featureId, body, request,
                () -> flow.correctFeature(id, featureId, body.replacement()));
    }

    @PostMapping("/{id}/corrections/scope")
    public PatentConsultationFixtureFlow.View scope(@PathVariable String id, @RequestBody Correction body,
                                                     @RequestHeader("Idempotency-Key") String key,
                                                     HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "scope", body, request,
                () -> flow.changeScope(id, body.replacement()));
    }

    @PostMapping("/{id}/end-without-evidence")
    public PatentConsultationFixtureFlow.View end(@PathVariable String id, @RequestBody Version body,
                                                   @RequestHeader("Idempotency-Key") String key,
                                                   HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "end", body, request,
                () -> flow.endWithoutEvidence(id));
    }

    @PostMapping("/{id}/human-handoffs")
    public PatentConsultationFixtureFlow.View handoff(@PathVariable String id, @RequestBody Version body,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "handoff", body, request,
                () -> flow.prepareHandoff(id));
    }

    @PostMapping("/{id}/cancel")
    public PatentConsultationFixtureFlow.View cancel(@PathVariable String id, @RequestBody Version body,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "cancel", body, request, () -> flow.cancel(id));
    }

    @PostMapping("/{id}/resume")
    public PatentConsultationFixtureFlow.View resume(@PathVariable String id, @RequestBody Version body,
                                                      @RequestHeader("Idempotency-Key") String key,
                                                      HttpServletRequest request) {
        return mutate(id, body.expectedTaskVersion(), key, "resume", body, request, () -> flow.resume(id));
    }

    @GetMapping("/{id}/source-links/{candidateId}")
    public PatentFixtureDocument source(@PathVariable String id, @PathVariable String candidateId,
                                        @RequestParam String version, HttpServletRequest request) {
        local(request);
        return flow.openSource(id, candidateId, version);
    }

    private PatentConsultationFixtureFlow.View mutate(String id, long expected, String key, String operation,
                                                       Object payload, HttpServletRequest request,
                                                       Supplier<PatentConsultationFixtureFlow.View> action) {
        local(request);
        synchronized (flow) {
            return once(id + ":" + key, List.of(operation, payload), () -> {
                long actual = flow.view(id).taskVersion();
                if (expected != actual) throw new TaskCommandException(TaskCommandException.Code.VERSION_CONFLICT,
                        "task changed; refresh before continuing", actual);
                return action.get();
            });
        }
    }

    private PatentConsultationFixtureFlow.View once(String key, Object payload,
                                                    Supplier<PatentConsultationFixtureFlow.View> action) {
        if (!key.matches("[A-Za-z0-9._:-]{1,180}")) {
            throw new IllegalArgumentException("valid idempotency key is required");
        }
        Receipt prior = receipts.get(key);
        if (prior != null) {
            if (!Objects.equals(prior.payload(), payload)) {
                throw new TaskCommandException(TaskCommandException.Code.PAYLOAD_CONFLICT,
                        "idempotency key has a different payload", null);
            }
            flow.view(prior.result().taskId());
            return prior.result();
        }
        PatentConsultationFixtureFlow.View result = action.get();
        receipts.put(key, new Receipt(payload, result));
        return result;
    }

    private void local(HttpServletRequest request) {
        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return;
        } catch (Exception ignored) {
            // Unknown addresses are denied, never resolved from forwarded headers.
        }
        throw new LocalOnlyException();
    }

    @ExceptionHandler(LocalOnlyException.class)
    ResponseEntity<Map<String, Object>> remoteDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "LOCAL_ONLY",
                "message", "Development fixture is available on this computer only"));
    }

    @ExceptionHandler(TaskCommandException.class)
    ResponseEntity<Map<String, Object>> taskError(TaskCommandException error) {
        HttpStatus status = switch (error.code()) {
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_COMMAND -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code().name());
        body.put("message", error.getMessage());
        if (error.currentVersion() != null) body.put("currentVersion", error.currentVersion());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", error.getMessage()));
    }

    public record Start(String description, String scope, List<String> features) {}
    public record Version(long expectedTaskVersion) {}
    public record Features(long expectedTaskVersion, List<String> features) {}
    public record Search(long expectedTaskVersion, String query) {}
    public record Select(long expectedTaskVersion, long resultVersion, int ordinal) {}
    public record Correction(long expectedTaskVersion, String replacement) {}
    private record Receipt(Object payload, PatentConsultationFixtureFlow.View result) {}
    private static final class LocalOnlyException extends RuntimeException {}
}
