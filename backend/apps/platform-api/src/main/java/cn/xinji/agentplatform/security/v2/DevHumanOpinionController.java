package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "consultation-v2.development.human-request-enabled", havingValue = "true")
final class DevHumanOpinionController {
    private final ObjectProvider<DevHumanOpinionStore> stores;
    private final DevTaskCoreController core;
    private final DevHumanGapPolicy inputs;

    DevHumanOpinionController(ObjectProvider<DevHumanOpinionStore> stores, DevTaskCoreController core,
            DevHumanGapPolicy inputs) {
        this.stores = stores;
        this.core = core;
        this.inputs = inputs;
    }

    @GetMapping("/v2/consultation/tasks/{id}/human-requests/{sequence}/opinion")
    DevHumanOpinionStore.Opinion read(HttpServletRequest request, @PathVariable String id,
                                      @PathVariable long sequence) {
        return store().read(core.actor(request), id, sequence, core.guard(request), source(request));
    }

    private DevHumanOpinionStore store() {
        DevHumanOpinionStore store = stores.getIfAvailable();
        if (store == null) throw new DevTaskCoreFailure(503, "OPINION_NOT_AVAILABLE");
        return store;
    }

    private DevHumanOpinionStore.SourceGuard source(HttpServletRequest request) {
        var subject = (DevelopmentSubjectPolicy.Subject) request.getAttribute(
                V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
        return (human, save, display, now) -> inputs.opinionPermitted(subject, human, save, display, now);
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
