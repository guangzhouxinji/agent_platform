package cn.xinji.agentplatform.consultation.agentconfig.adapter.in.web;

import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.AgentDraft;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.Catalog;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.DraftException;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.SaveDraft;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentDraftService.ValidationResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/consultation-agent-draft")
public final class ConsultationAgentDraftController {
    private final ConsultationAgentDraftService service;
    public ConsultationAgentDraftController(ConsultationAgentDraftService service) { this.service = service; }

    @GetMapping("/catalog") Catalog catalog(@RequestHeader("X-V0-Requester") String requester) { return service.catalog(requester); }
    @GetMapping AgentDraft get(@RequestHeader("X-V0-Requester") String requester) { return service.get(requester); }
    @PutMapping AgentDraft save(@RequestHeader("X-V0-Requester") String requester, @RequestBody SaveDraft command) { return service.save(requester, command); }
    @PostMapping("/validate") ValidationResult validate(@RequestHeader("X-V0-Requester") String requester) { return service.validate(requester); }

    @ExceptionHandler(DraftException.class)
    ResponseEntity<ErrorResponse> error(DraftException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage(), exception.issues()));
    }
    record ErrorResponse(String code, String message, List<ConsultationAgentDraftService.Issue> issues) {}
}
