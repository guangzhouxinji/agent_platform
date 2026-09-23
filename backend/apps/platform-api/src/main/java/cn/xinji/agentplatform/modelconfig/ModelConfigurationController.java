package cn.xinji.agentplatform.modelconfig;

import java.util.List;
import java.util.Map;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService.ModelView;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService.ReorderModels;
import cn.xinji.agentplatform.modelconfig.ManagedModelConfigurationService.SaveModel;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v0/model-configurations")
public final class ModelConfigurationController {
    private final ManagedModelConfigurationService models;
    private final ConsultationAgentDraftService drafts;

    public ModelConfigurationController(ManagedModelConfigurationService models, ConsultationAgentDraftService drafts) {
        this.models = models;
        this.drafts = drafts;
    }

    @GetMapping
    List<ModelView> list(@RequestHeader("X-V0-Requester") String requester) {
        models.authorize(requester);
        return models.list();
    }

    @PutMapping("/{modelId}")
    ModelView save(@RequestHeader("X-V0-Requester") String requester, @PathVariable String modelId, @RequestBody SaveModel input) {
        models.authorize(requester);
        return models.save(modelId, input, requester);
    }

    @PutMapping("/order")
    List<ModelView> reorder(@RequestHeader("X-V0-Requester") String requester, @RequestBody ReorderModels input) {
        models.authorize(requester);
        return models.reorder(input.modelIds(), input.changeNote(), requester);
    }

    @PostMapping("/apply-consultation-agent-draft")
    List<ModelView> applyConsultationAgentDraft(@RequestHeader("X-V0-Requester") String requester) {
        models.authorize(requester);
        var draft = drafts.get(requester);
        return models.applyDraftOrder(draft.modelChain(), draft.version(), requester);
    }

    @PostMapping("/{modelId}/test")
    ModelView test(@RequestHeader("X-V0-Requester") String requester, @PathVariable String modelId) {
        models.authorize(requester);
        return models.test(modelId, requester);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> modelConfigurationError(ResponseStatusException error) {
        String message = error.getReason() == null || error.getReason().isBlank() ? "模型配置请求失败" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
    }
}
