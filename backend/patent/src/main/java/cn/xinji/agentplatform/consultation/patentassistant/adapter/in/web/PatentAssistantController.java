package cn.xinji.agentplatform.consultation.patentassistant.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantService;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentSourceException;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantResult;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentSearchResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pilot/patent-assistant")
public class PatentAssistantController {
    private final PatentAssistantService service;

    public PatentAssistantController(PatentAssistantService service) { this.service = service; }

    @GetMapping("/status")
    public PatentAssistantService.AssistantStatus status() { return service.status(); }

    @PostMapping("/analyze")
    public PatentAssistantResult analyze(@RequestHeader("X-V0-Requester") String requester,
                                         @RequestBody AnalyzeRequest request) {
        return service.analyze(requester, request == null ? null : request.question());
    }

    @PostMapping("/search")
    public PatentSearchResult search(@RequestHeader("X-V0-Requester") String requester,
                                     @RequestBody AnalyzeRequest request) {
        return service.search(requester, request == null ? null : request.question(),
                request == null ? null : request.page(), request == null ? null : request.pageSize(),
                request == null ? null : request.country());
    }

    @GetMapping("/sources/{publicationNumber}")
    public cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort.SourceDetail detail(
            @RequestHeader("X-V0-Requester") String requester,
            @PathVariable String publicationNumber) {
        return service.detail(requester, publicationNumber);
    }

    @ExceptionHandler(PatentAssistantService.InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(PatentAssistantService.InvalidRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(PatentAssistantService.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied() { return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前身份无权使用此能力"); }

    @ExceptionHandler(PatentAssistantService.NoCandidateException.class)
    ResponseEntity<ErrorResponse> noCandidate() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "NO_PUBLIC_CANDIDATE", "当前公开试点范围未召回候选，不等于不存在相关专利");
    }

    @ExceptionHandler(PatentAssistantService.ModelUnavailableException.class)
    ResponseEntity<ErrorResponse> modelUnavailable(PatentAssistantService.ModelUnavailableException exception) {
        String message = "MODEL_NOT_CONFIGURED".equals(exception.getMessage())
                ? "DeepSeek 尚未配置，请设置 DEEPSEEK_API_KEY" : "DeepSeek 当前不可用，请稍后重试";
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), message);
    }

    @ExceptionHandler(PatentAssistantService.InvalidModelOutputException.class)
    ResponseEntity<ErrorResponse> invalidModelOutput() {
        return error(HttpStatus.BAD_GATEWAY, "INVALID_MODEL_CITATION", "模型未按实际候选提供有效引用，本次结果已拒绝展示");
    }

    @ExceptionHandler(PatentAssistantService.ExternalModelUseNotAllowedException.class)
    ResponseEntity<ErrorResponse> externalModelUseNotAllowed() {
        return error(HttpStatus.CONFLICT, "EXTERNAL_MODEL_USE_NOT_ALLOWED",
                "当前动态数据源未获准发送给外部模型，请使用公开专利动态检索");
    }

    @ExceptionHandler(PatentSourceException.class)
    ResponseEntity<ErrorResponse> sourceFailure(PatentSourceException exception) {
        return switch (exception.code()) {
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "PATENT_SOURCE_TIMEOUT", "公开专利源响应超时，请稍后重试");
            case RATE_LIMITED -> error(HttpStatus.TOO_MANY_REQUESTS, "PATENT_SOURCE_RATE_LIMITED", "公开专利源当前限流，请稍后重试");
            case UPSTREAM_UNAUTHENTICATED -> error(HttpStatus.BAD_GATEWAY, "PATENT_SOURCE_AUTHENTICATION_FAILED", "公开专利源认证失败");
            case UPSTREAM_FORBIDDEN -> error(HttpStatus.BAD_GATEWAY, "PATENT_SOURCE_ACCESS_FORBIDDEN", "公开专利源拒绝访问");
            case UPSTREAM_FAILURE -> error(HttpStatus.BAD_GATEWAY, "PATENT_SOURCE_UNAVAILABLE", "公开专利源当前不可用");
            case MALFORMED_RESPONSE -> error(HttpStatus.BAD_GATEWAY, "PATENT_SOURCE_INVALID_RESPONSE", "公开专利源返回了无法识别的数据");
        };
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, UUID.randomUUID().toString()));
    }

    public record AnalyzeRequest(String question, Integer page, Integer pageSize, String country) {}
    public record ErrorResponse(String code, String message, String requestId) {}
}
