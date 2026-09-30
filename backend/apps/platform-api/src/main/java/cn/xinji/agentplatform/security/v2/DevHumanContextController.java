package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanContextStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.human-context-enabled", havingValue = "true")
final class DevHumanContextController {
    private final DevHumanContextStore store;
    private final DevTaskCoreController core;
    private final DevHumanGapPolicy inputs;

    DevHumanContextController(DevHumanContextStore store, DevTaskCoreController core,
                              DevHumanGapPolicy inputs) {
        this.store = store;
        this.core = core;
        this.inputs = inputs;
    }

    @GetMapping("/v2/consultation/tasks/{id}/human-requests/{sequence}/context")
    DevHumanContextStore.Context read(HttpServletRequest request, @PathVariable String id,
                                      @PathVariable long sequence) {
        var human = core.actor(request);
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return store.read(human, id, sequence, core.guard(request),
                (actor, now) -> inputs.permitted(subject, actor, false, true, now));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
