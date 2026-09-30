package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevCurrentWorkStateStore;
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
@ConditionalOnProperty(name = "consultation-v2.development.work-state-enabled", havingValue = "true")
final class DevCurrentWorkStateController {
    private final DevCurrentWorkStateStore store;
    private final DevTaskCoreController core;

    DevCurrentWorkStateController(DevCurrentWorkStateStore store, DevTaskCoreController core) {
        this.store = store;
        this.core = core;
    }

    @GetMapping("/v2/consultation/tasks/{id}/current-work-state")
    DevCurrentWorkStateStore.State read(HttpServletRequest request, @PathVariable String id) {
        return store.read(core.actor(request), id, core.guard(request));
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
