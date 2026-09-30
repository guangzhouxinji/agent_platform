package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
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
@ConditionalOnProperty(name = "consultation-v2.development.task-core-enabled", havingValue = "true")
final class DevTaskCoreController {
    private final DevTaskCoreStore store;
    private final DevelopmentSubjectPolicy subjects;
    private final DevTaskInputPolicy inputs;
    private final DevComparisonInputPolicy comparisonInputs;
    private final DevCorrectionInputPolicy correctionInputs;

    DevTaskCoreController(DevTaskCoreStore store, DevelopmentSubjectPolicy subjects, DevTaskInputPolicy inputs,
                          DevComparisonInputPolicy comparisonInputs,
                          ObjectProvider<DevCorrectionInputPolicy> correctionInputs) {
        this.store = store;
        this.subjects = subjects;
        this.inputs = inputs;
        this.comparisonInputs = comparisonInputs;
        this.correctionInputs = correctionInputs.getIfAvailable();
    }

    @PostMapping("/v2/consultation/tasks")
    ResponseEntity<DevTaskCoreStore.CommandResult> create(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        fields(body, "goal");
        return ResponseEntity.status(HttpStatus.CREATED).body(store.create(actor(request), key,
                text(body, "goal"), guard(request)));
    }

    @PostMapping("/v2/consultation/tasks/{id}/messages")
    DevTaskCoreStore.CommandResult append(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        fields(body, "message", "expectedVersion");
        return store.append(actor(request), id, key, version(body), text(body, "message"), guard(request));
    }

    @GetMapping("/v2/consultation/tasks/{id}")
    DevTaskCoreStore.TaskView read(HttpServletRequest request, @PathVariable String id) {
        return store.read(actor(request), id, guard(request));
    }

    @PostMapping("/v2/consultation/tasks/{id}/cancel")
    DevTaskCoreStore.CommandResult cancel(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        fields(body, "expectedVersion");
        return store.cancel(actor(request), id, key, version(body), guard(request));
    }

    @PostMapping("/v2/internal/consultation/attempts/{id}/lease")
    DevTaskCoreStore.Lease lease(HttpServletRequest request, @PathVariable String id) {
        return store.lease(actor(request), id, guard(request));
    }

    @PostMapping("/v2/internal/consultation/attempts/{id}/checkpoint")
    DevTaskCoreStore.Checkpoint checkpoint(HttpServletRequest request, @PathVariable String id,
                                           @RequestBody JsonNode body) {
        fields(body, "token", "committedRef");
        JsonNode token = body.path("token");
        if (!token.isIntegralNumber() || !token.canConvertToLong()) throw DevTaskCoreFailure.invalid();
        return store.checkpoint(actor(request), id, token.longValue(), text(body, "committedRef"), guard(request));
    }

    @PostMapping("/v2/internal/consultation/retention/reconcile")
    DevTaskCoreStore.ReconcileResult reconcile(HttpServletRequest request) {
        return store.reconcile(actor(request), guard(request));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new Error(failure.code()));
    }

    record Error(String code) { }

    Actor actor(HttpServletRequest request) {
        var subject = subject(request);
        Instant until = subject.kind() == DevelopmentBoundaryProperties.Kind.HUMAN
                && inputs.validUntil().isBefore(subject.expiresAt()) ? inputs.validUntil() : subject.expiresAt();
        return new Actor(subject.stableSubjectId(), subject.organizationRef(), subject.credentialVersion(), until);
    }

    Guard guard(HttpServletRequest request) {
        var subject = subject(request);
        return new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return subject.kind() == DevelopmentBoundaryProperties.Kind.HUMAN
                        && actor.principal().equals(subject.stableSubjectId())
                        && actor.organization().equals(subject.organizationRef())
                        && actor.authorizationVersion() == subject.credentialVersion()
                        && subjects.current(subject, now);
            }

            @Override public boolean inputAllowed(Actor actor, String kind, String content, Instant now) {
                return humanCurrent(actor, now) && inputs.allows(subject, kind, content, now);
            }

            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String content, Instant now) {
                return humanCurrent(actor, now) && comparisonInputs.allowsDisplay(subject, kind, content, now);
            }

            @Override public boolean correctionAllowed(Actor actor, String field, String content, Instant now) {
                return humanCurrent(actor, now) && correctionInputs != null
                        && correctionInputs.allows(subject, field, content, now);
            }

            @Override public boolean delegationCurrent(String owner, String organization, long version,
                                                       Instant delegatedUntil, Instant now) {
                return now.isBefore(inputs.validUntil()) && subjects.currentDelegation(owner, organization,
                        version, delegatedUntil, now);
            }

            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                var kind = role == ServiceRole.AGENT ? DevelopmentBoundaryProperties.Kind.AGENT
                        : DevelopmentBoundaryProperties.Kind.JOB;
                return subject.kind() == kind && actor.principal().equals(subject.stableSubjectId())
                        && actor.organization().equals(subject.organizationRef())
                        && actor.authorizationVersion() == subject.credentialVersion()
                        && subjects.permits(subject, kind, actor.principal(), now);
            }
        };
    }

    private static DevelopmentSubjectPolicy.Subject subject(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        if (subject == null) throw DevTaskCoreFailure.denied();
        return subject;
    }

    private static void fields(JsonNode body, String... names) {
        if (body == null || !body.isObject()) throw DevTaskCoreFailure.invalid();
        Set<String> expected = Set.of(names);
        if (body.size() != expected.size()) throw DevTaskCoreFailure.invalid();
        body.fieldNames().forEachRemaining(name -> {
            if (!expected.contains(name)) throw DevTaskCoreFailure.invalid();
        });
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw DevTaskCoreFailure.invalid();
        return value.asText();
    }

    private static long version(JsonNode body) {
        JsonNode value = body.path("expectedVersion");
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw DevTaskCoreFailure.invalid();
        return value.longValue();
    }
}
