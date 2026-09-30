package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductConsultationTaskController {
    public record TaskView(String taskId, String organizationId, String ownerSubject, String state,
                           long version, String dataScopeId, long scopeVersion, Instant expiresAt) { }

    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final ProductTaskGoalService goals;
    private final ProductTaskGoalReader goalReader;
    private final ProductTaskCancelService cancellation;
    private final ProductTaskSessionService sessions;
    private final ProductTaskCreationService creation;
    private final Clock clock;

    public ProductConsultationTaskController(ProductTaskDirectory directory, ProductConsultationAccess access,
            ProductTaskGoalService goals, ProductTaskGoalReader goalReader,
            ProductTaskCancelService cancellation, ProductTaskSessionService sessions,
            ProductTaskCreationService creation) {
        this.directory = directory;
        this.access = access;
        this.goals = goals;
        this.goalReader = goalReader;
        this.cancellation = cancellation;
        this.sessions = sessions;
        this.creation = creation;
        this.clock = Clock.systemUTC();
    }

    @PostMapping
    public ProductTaskCreationService.Result create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody ProductTaskCreationService.Input input) {
        return creation.create(jwt, requestId, input);
    }

    @GetMapping("/{id}")
    public TaskView read(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        ProductTaskDirectory.Task task = directory.find(id).orElseThrow(ProductConsultationTaskController::notFound);
        if (!task.expiresAt().isAfter(clock.instant()))
            throw notFound();
        var resource = new ProductConsultationAccess.ResourceFacts(task.organization(), task.id(),
                task.dataScope(), "TASK", task.id(), task.authorizationVersion());
        if (!access.permits(jwt, resource, ProductAccessPolicy.Action.READ)) throw notFound();
        ProductTaskDirectory.Task current = directory.find(id).orElseThrow(ProductConsultationTaskController::notFound);
        if (!task.equals(current) || !current.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt, resource, ProductAccessPolicy.Action.READ)) throw notFound();
        return new TaskView(task.id(), task.organization(), task.ownerSubject(), task.state(),
                task.version(), task.scopeId(), task.scopeVersion(), task.expiresAt());
    }

    @PostMapping("/{id}/goals/confirm")
    public ProductTaskGoalService.Result confirm(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId,
            @RequestBody ProductTaskGoalService.Input input) {
        return goals.confirm(id, jwt, requestId, input);
    }

    @GetMapping("/{id}/goals/current")
    public ProductTaskGoalReader.GoalView currentGoal(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return goalReader.current(id, jwt);
    }

    public record CancelRequest(long expectedTaskVersion) { }

    @PostMapping("/{id}/cancel")
    public ProductTaskCancelService.Result cancel(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId, @RequestBody CancelRequest input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return cancellation.cancel(id, jwt, requestId, input.expectedTaskVersion());
    }

    @PostMapping("/{id}/sessions")
    public ProductTaskSessionService.Result resume(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId, @RequestBody CancelRequest input) {
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return sessions.resume(id, jwt, requestId, input.expectedTaskVersion());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableAuthority() { }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableStorage() { }
}
