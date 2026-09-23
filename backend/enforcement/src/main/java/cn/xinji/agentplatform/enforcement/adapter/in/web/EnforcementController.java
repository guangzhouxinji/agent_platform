package cn.xinji.agentplatform.enforcement.adapter.in.web;

import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService.EnforcementException;
import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v0/enforcement/work-items")
public class EnforcementController {
    private final EnforcementApplicationService service;

    public EnforcementController(EnforcementApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public Envelope create(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("X-V0-Organization") String organization,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody OpenWorkRequest request) {
        require(actor, "actor"); require(organization, "organization"); require(key, "idempotencyKey");
        if (request == null) throw invalid("request");
        require(request.clueId(), "clueId"); require(request.clueVersion(), "clueVersion");
        return ok(requestId, "WORK_ITEM_CREATED",
                service.openWork(actor, organization, request.clueId(), request.clueVersion(), key));
    }

    @PostMapping("/{workItemId}/process")
    public Envelope process(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody ProcessRequest request) {
        require(actor, "actor"); require(key, "idempotencyKey"); require(workItemId, "workItemId");
        if (request == null || request.evidenceIds() == null) throw invalid("evidenceIds");
        request.evidenceIds().forEach(value -> require(value, "evidenceIds"));
        EnforcementSnapshot result = service.process(actor, workItemId, request.evidenceIds(), key);
        String type = result.auxiliaryAnalyses().get(result.auxiliaryAnalyses().size() - 1).auxiliaryOutcome();
        return ok(requestId, "MATERIALS_INSUFFICIENT".equals(type) ? type : "AUXILIARY_ANALYSIS_READY", result);
    }

    @PostMapping("/{workItemId}/clue-analyses/{analysisId}/actions")
    public Envelope reviseClue(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @PathVariable String analysisId,
            @RequestBody ActionRequest request) {
        validateActionRequest(request);
        if (!"REVISE".equals(request.action())) throw new EnforcementException("INVALID_ACTION");
        Object exact = service.readVersion(actor, workItemId, "clue-analyses", analysisId, request.expectedVersion());
        if (exact == null) throw new EnforcementException("NOT_FOUND");
        return ok(requestId, "CLUE_ANALYSIS_READY",
                service.reviseClue(actor, workItemId, request.expectedVersion(), request.reason(), key));
    }

    @PostMapping("/{workItemId}/auxiliary-analyses/{analysisId}/actions")
    public Envelope reviewAnalysis(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @PathVariable String analysisId,
            @RequestBody ActionRequest request) {
        validateActionRequest(request);
        service.readVersion(actor, workItemId, "auxiliary-analyses", analysisId, request.expectedVersion());
        return ok(requestId, "REVIEW_RECORDED", service.reviewLatestAnalysis(
                actor, workItemId, request.expectedVersion(), request.action(), request.reason(), key));
    }

    @GetMapping("/{workItemId}")
    public Envelope read(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId) {
        require(actor, "actor");
        return ok(requestId, "WORK_ITEM_VIEW", service.read(actor, workItemId));
    }

    @GetMapping("/{workItemId}/{resource}/{resourceId}/versions/{version}")
    public Envelope readVersion(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @PathVariable String resource,
            @PathVariable String resourceId,
            @PathVariable int version) {
        require(actor, "actor");
        if (version < 1) throw invalid("version");
        return ok(requestId, "EXACT_VERSION", service.readVersion(actor, workItemId, resource, resourceId, version));
    }

    @GetMapping("/{workItemId}/source-links/{sourceLinkId}")
    public Envelope readSource(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @PathVariable String sourceLinkId) {
        require(actor, "actor");
        SourceLocator source = service.readSourceLink(actor, workItemId, sourceLinkId);
        return ok(requestId, "SOURCE_LINK", new SourceResponse(
                source.sourceSystem(), source.sourceId(), source.version(), source.location()));
    }

    @ExceptionHandler(EnforcementException.class)
    ResponseEntity<ErrorEnvelope> enforcementError(
            EnforcementException error,
            HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "INVALID_INPUT", "INVALID_ACTION", "REASON_REQUIRED" -> HttpStatus.BAD_REQUEST;
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "UNAUTHORIZED" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND", "SOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "SOURCE_UNAVAILABLE", "UPSTREAM_FAILURE", "ANALYSIS_FAILURE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), safeMessage(error.code()), status.is5xxServerError(), null, "local-trace")));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    ResponseEntity<ErrorEnvelope> missingHeader(
            org.springframework.web.bind.MissingRequestHeaderException error,
            HttpServletRequest request) {
        boolean identityMissing = "X-V0-Actor".equalsIgnoreCase(error.getHeaderName());
        HttpStatus status = identityMissing ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        String code = identityMissing ? "UNAUTHENTICATED" : "INVALID_INPUT";
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(code, "缺少必要请求头", false, null, "local-trace")));
    }

    private static void validateActionRequest(ActionRequest request) {
        if (request == null) throw invalid("request");
        require(request.action(), "action");
        if (request.expectedVersion() < 1) throw invalid("expectedVersion");
    }

    private Envelope ok(String requestId, String resultType, Object data) {
        return new Envelope(id(requestId), resultType, data, service.limitations());
    }

    private static String id(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field);
    }

    private static EnforcementException invalid(String field) {
        return new EnforcementException("INVALID_INPUT");
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "UNAUTHORIZED" -> "当前身份无权执行该操作";
            case "NOT_FOUND", "SOURCE_NOT_FOUND" -> "资源不存在或不可访问";
            case "VERSION_CONFLICT" -> "目标版本已变化";
            case "IDEMPOTENCY_CONFLICT" -> "幂等键已绑定其他输入";
            case "SOURCE_UNAVAILABLE" -> "当前来源不可用";
            default -> "请求未完成";
        };
    }

    public record OpenWorkRequest(String clueId, String clueVersion) {}
    public record ProcessRequest(List<String> evidenceIds) {
        public ProcessRequest { evidenceIds = evidenceIds == null ? null : List.copyOf(evidenceIds); }
    }
    public record ActionRequest(String action, int expectedVersion, String reason) {}
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record SourceResponse(String sourceSystem, String sourceId, String version, String location) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
