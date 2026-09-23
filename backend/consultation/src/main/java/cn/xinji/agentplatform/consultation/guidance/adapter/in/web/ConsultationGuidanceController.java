package cn.xinji.agentplatform.consultation.guidance.adapter.in.web;

import cn.xinji.agentplatform.consultation.guidance.application.ConsultationGuidanceService;
import cn.xinji.agentplatform.consultation.guidance.application.ConsultationGuidanceService.GuidanceException;
import cn.xinji.agentplatform.consultation.guidance.domain.ConsultationGuidanceResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/consultation-guides")
public final class ConsultationGuidanceController {
    private final ConsultationGuidanceService service;

    public ConsultationGuidanceController(ConsultationGuidanceService service) {
        this.service = service;
    }

    @GetMapping
    ConsultationGuidanceResult query(
            @RequestHeader("X-V0-Requester") String requester,
            @RequestParam(required = false) String scenarioId,
            @RequestParam(defaultValue = "zh-CN") String language,
            @RequestParam(defaultValue = "6") int limit) {
        return service.query(requester, scenarioId, language, limit);
    }

    @ExceptionHandler(GuidanceException.class)
    ResponseEntity<ErrorResponse> error(GuidanceException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {}
}
