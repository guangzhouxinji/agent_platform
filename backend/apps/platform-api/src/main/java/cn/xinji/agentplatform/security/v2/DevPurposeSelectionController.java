package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore;
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
@ConditionalOnProperty(name = "consultation-v2.development.purpose-read-enabled", havingValue = "true")
final class DevPurposeSelectionController {
    private final DevPurposeSelectionStore store;
    private final DevComparisonInputPreviewStore preview;
    private final DevComparisonInputPreviewController previewController;
    private final DevTaskCoreController core;

    DevPurposeSelectionController(DevPurposeSelectionStore store, DevComparisonInputPreviewStore preview,
            DevComparisonInputPreviewController previewController, DevTaskCoreController core) {
        this.store = store;
        this.preview = preview;
        this.previewController = previewController;
        this.core = core;
    }

    @GetMapping("/v2/consultation/tasks/{id}/analysis-purposes/current")
    DevPurposeSelectionStore.Selection current(HttpServletRequest request, @PathVariable String id) {
        return store.current(core.actor(request), id, core.guard(request), check(request, id));
    }

    @GetMapping("/v2/consultation/tasks/{id}/analysis-purposes/{sequence}")
    DevPurposeSelectionStore.Selection read(HttpServletRequest request, @PathVariable String id,
                                            @PathVariable long sequence) {
        return store.read(core.actor(request), id, sequence, core.guard(request), check(request, id));
    }

    DevPurposeSelectionStore.PreviewCheck check(HttpServletRequest request, String id) {
        var human = core.actor(request);
        var guard = core.guard(request);
        var sources = previewController.sources(request);
        return (feature, focus) -> preview.read(human, id, feature, focus, guard, sources);
    }

    @ExceptionHandler(DevTaskCoreFailure.class)
    ResponseEntity<DevTaskCoreController.Error> failure(DevTaskCoreFailure failure) {
        return ResponseEntity.status(failure.status()).body(new DevTaskCoreController.Error(failure.code()));
    }
}
