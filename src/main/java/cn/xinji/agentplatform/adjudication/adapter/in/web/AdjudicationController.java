package cn.xinji.agentplatform.adjudication.adapter.in.web;

import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AdjudicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v0/adjudication/work-items")
public class AdjudicationController {
    private static final List<String> LIMITATIONS = List.of("SYNTHETIC_V0", "NOT_OFFICIAL_DECISION");
    private final AdjudicationApplicationService service;

    public AdjudicationController(AdjudicationApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public Envelope open(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("X-V0-Organization") String organization,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody OpenWorkRequest request) {
        require(actor); require(organization); require(key);
        if (request == null || request.materialIds() == null) throw invalid();
        require(request.caseId()); require(request.caseVersion()); request.materialIds().forEach(AdjudicationController::require);
        return ok(requestId, "WORK_ITEM_CREATED", service.openWork(actor, organization, request.caseId(),
                request.caseVersion(), request.materialIds(), key));
    }

    @PostMapping("/{workItemId}/material-inspections")
    public Envelope inspect(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId) {
        require(actor); require(key); require(workItemId);
        return ok(requestId, "MATERIAL_INSPECTION_READY", service.inspectMaterials(actor, workItemId, key));
    }

    @PostMapping("/{workItemId}/legal-candidate-sets")
    public Envelope match(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody VersionRequest request) {
        require(actor); require(key); require(workItemId); if (request == null || request.inspectionVersion() < 1) throw invalid();
        return ok(requestId, "LEGAL_CANDIDATES_READY", service.matchLegalCandidates(actor, workItemId,
                request.inspectionVersion(), key));
    }

    @PostMapping("/{workItemId}/citations/actions")
    public Envelope cite(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody CitationActionRequest request) {
        require(actor); require(key); require(workItemId);
        if (request == null || request.expectedVersion() < 0) throw invalid();
        require(request.code()); require(request.action());
        return ok(requestId, "CITATION_RECORDED", service.cite(actor, workItemId, request.expectedVersion(),
                request.code(), request.action(), request.reason(), key));
    }

    @PostMapping("/{workItemId}/drafts")
    public Envelope draft(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody VersionRequest request) {
        require(actor); require(key); require(workItemId); if (request == null || request.inspectionVersion() < 1) throw invalid();
        return ok(requestId, "DRAFT_READY", service.createDraft(actor, workItemId, request.inspectionVersion(), key));
    }

    @PostMapping("/{workItemId}/drafts/actions")
    public Envelope reviseDraft(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody DraftActionRequest request) {
        require(actor); require(key); require(workItemId);
        if (request == null || request.expectedVersion() < 1) throw invalid();
        return ok(requestId, "DRAFT_REVISED", service.reviseDraft(actor, workItemId, request.expectedVersion(),
                request.replacementText(), request.reason(), key));
    }

    @PostMapping("/{workItemId}/drafts/test-reviews")
    public Envelope review(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId,
            @RequestBody ReviewRequest request) {
        require(actor); require(key); require(workItemId);
        if (request == null || request.expectedDraftVersion() < 1) throw invalid(); require(request.action());
        return ok(requestId, "TEST_REVIEW_RECORDED", service.reviewDraft(actor, workItemId,
                request.expectedDraftVersion(), request.action(), request.reason(), key));
    }

    @GetMapping("/{workItemId}")
    public Envelope read(
            @RequestHeader("X-V0-Actor") String actor,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String workItemId) {
        require(actor); require(workItemId);
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
        require(actor); require(workItemId); require(resource); require(resourceId); if (version < 1) throw invalid();
        return ok(requestId, "EXACT_VERSION", service.readVersion(actor, workItemId, resource, resourceId, version));
    }

    @ExceptionHandler(AdjudicationException.class)
    ResponseEntity<ErrorEnvelope> adjudicationError(AdjudicationException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "INVALID_INPUT", "INVALID_ACTION", "REASON_REQUIRED" -> HttpStatus.BAD_REQUEST;
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "UNAUTHORIZED" -> HttpStatus.FORBIDDEN;
            case "NOT_FOUND", "SOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SOURCE_UNAVAILABLE", "MATERIALS_INSUFFICIENT", "VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            case "UPSTREAM_FAILURE" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), safeMessage(error.code()), status.is5xxServerError(), "local-trace"), LIMITATIONS));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    ResponseEntity<ErrorEnvelope> missingHeader(
            org.springframework.web.bind.MissingRequestHeaderException error, HttpServletRequest request) {
        boolean identityMissing = "X-V0-Actor".equalsIgnoreCase(error.getHeaderName());
        HttpStatus status = identityMissing ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        String code = identityMissing ? "UNAUTHENTICATED" : "INVALID_INPUT";
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(code, "缺少必要请求头", false, "local-trace"), LIMITATIONS));
    }

    private static Envelope ok(String requestId, String resultType, Object data) {
        return new Envelope(id(requestId), resultType, data, LIMITATIONS);
    }

    private static void require(String value) { if (value == null || value.isBlank()) throw invalid(); }
    private static AdjudicationException invalid() { return new AdjudicationException("INVALID_INPUT"); }
    private static String id(String value) { return value == null || value.isBlank() ? UUID.randomUUID().toString() : value; }
    private static String safeMessage(String code) {
        return switch (code) {
            case "UNAUTHORIZED" -> "当前身份无权执行该操作";
            case "NOT_FOUND", "SOURCE_NOT_FOUND" -> "资源不存在或不可访问";
            case "VERSION_CONFLICT" -> "目标版本已变化";
            case "IDEMPOTENCY_CONFLICT" -> "幂等键已绑定其他输入";
            case "SOURCE_UNAVAILABLE" -> "当前来源不可用";
            case "MATERIALS_INSUFFICIENT" -> "材料不足或存在冲突，不能确认";
            default -> "请求未完成";
        };
    }

    public record OpenWorkRequest(String caseId, String caseVersion, List<String> materialIds) {
        public OpenWorkRequest { materialIds = materialIds == null ? null : List.copyOf(materialIds); }
    }
    public record VersionRequest(int inspectionVersion) { }
    public record CitationActionRequest(int expectedVersion, String code, String action, String reason) { }
    public record DraftActionRequest(int expectedVersion, String replacementText, String reason) { }
    public record ReviewRequest(int expectedDraftVersion, String action, String reason) { }
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) { }
    public record ErrorEnvelope(String requestId, ErrorBody error, List<String> limitations) { }
    public record ErrorBody(String code, String message, boolean retryable, String traceRef) { }
}
