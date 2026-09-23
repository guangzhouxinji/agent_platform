package cn.xinji.agentplatform.enforcement.adapter.in.web;

import static cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/enforcement")
public class EnforcementClueDiscoveryController {
    private final EnforcementClueDiscoveryService service;

    public EnforcementClueDiscoveryController(EnforcementClueDiscoveryService service) {
        this.service = service;
    }

    @PostMapping("/discovery-tasks")
    Envelope discover(@RequestHeader("X-V0-Actor") String actor,
                      @RequestHeader("X-V0-Organization") String organization,
                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                      @RequestBody DiscoveryCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_DISCOVERY_TASK",
                service.discover(actor, organization, idempotencyKey, resolved, command));
    }

    @GetMapping("/discovery-tasks")
    Envelope tasks(@RequestHeader("X-V0-Actor") String actor,
                   @RequestHeader("X-V0-Organization") String organization,
                   @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ok(requestId, "ENFORCEMENT_DISCOVERY_TASK_LIST", service.tasks(actor, organization));
    }

    @GetMapping("/discovery-tasks/{taskId}")
    Envelope task(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @PathVariable String taskId) {
        return ok(requestId, "ENFORCEMENT_DISCOVERY_TASK", service.task(actor, organization, taskId));
    }

    @GetMapping("/leads")
    Envelope leads(@RequestHeader("X-V0-Actor") String actor,
                   @RequestHeader("X-V0-Organization") String organization,
                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                   @RequestParam(required = false) String status,
                   @RequestParam(required = false) String suspectedType,
                   @RequestParam(required = false) String sourceId,
                   @RequestParam(required = false) String keyword,
                   @RequestParam(defaultValue = "1") int page,
                   @RequestParam(defaultValue = "20") int pageSize) {
        return ok(requestId, "ENFORCEMENT_LEAD_LIST",
                service.leads(actor, organization, new LeadFilter(status, suspectedType, sourceId, keyword, page, pageSize)));
    }

    @GetMapping("/leads/{leadId}")
    Envelope lead(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @PathVariable String leadId) {
        return ok(requestId, "ENFORCEMENT_LEAD", service.lead(actor, organization, leadId));
    }

    @PostMapping("/leads/{leadId}/start-verification")
    Envelope startVerification(@RequestHeader("X-V0-Actor") String actor,
                               @RequestHeader("X-V0-Organization") String organization,
                               @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                               @PathVariable String leadId, @RequestBody ActionRequest request) {
        return transition(actor, organization, requestId, leadId, "START_VERIFICATION", request);
    }

    @PostMapping("/leads/{leadId}/transfer")
    Envelope transfer(@RequestHeader("X-V0-Actor") String actor,
                      @RequestHeader("X-V0-Organization") String organization,
                      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                      @PathVariable String leadId, @RequestBody ActionRequest request) {
        return transition(actor, organization, requestId, leadId, "TRANSFER", request);
    }

    @PostMapping("/leads/{leadId}/exclude")
    Envelope exclude(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String leadId, @RequestBody ActionRequest request) {
        return transition(actor, organization, requestId, leadId, "EXCLUDE", request);
    }

    @PostMapping("/leads/{leadId}/close")
    Envelope close(@RequestHeader("X-V0-Actor") String actor,
                   @RequestHeader("X-V0-Organization") String organization,
                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                   @PathVariable String leadId, @RequestBody ActionRequest request) {
        return transition(actor, organization, requestId, leadId, "CLOSE", request);
    }

    @PostMapping("/leads/duplicate-links")
    Envelope markDuplicate(@RequestHeader("X-V0-Actor") String actor,
                           @RequestHeader("X-V0-Organization") String organization,
                           @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                           @RequestBody DuplicateCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_LEAD_DUPLICATE_LINK",
                service.markDuplicate(actor, organization, command, resolved));
    }

    @PostMapping("/leads/duplicate-links/{linkId}/confirm")
    Envelope confirmDuplicate(@RequestHeader("X-V0-Actor") String actor,
                              @RequestHeader("X-V0-Organization") String organization,
                              @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                              @PathVariable String linkId, @RequestBody DuplicateReviewRequest request) {
        return reviewDuplicate(actor, organization, requestId, linkId, "CONFIRMED", request);
    }

    @PostMapping("/leads/duplicate-links/{linkId}/reject")
    Envelope rejectDuplicate(@RequestHeader("X-V0-Actor") String actor,
                             @RequestHeader("X-V0-Organization") String organization,
                             @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                             @PathVariable String linkId, @RequestBody DuplicateReviewRequest request) {
        return reviewDuplicate(actor, organization, requestId, linkId, "REJECTED", request);
    }

    @ExceptionHandler(DiscoveryException.class)
    ResponseEntity<ErrorEnvelope> discoveryError(DiscoveryException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "TASK_NOT_FOUND", "LEAD_NOT_FOUND", "RULE_NOT_FOUND", "SOURCE_SYNC_TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "DUPLICATE_LINK_EXISTS" -> HttpStatus.CONFLICT;
            case "SOURCE_SYNC_TASK_NOT_READY", "RULE_NOT_PUBLISHED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "DISCOVERY_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), message(error.code()), status.is5xxServerError(), error.field(), "local-trace")));
    }

    private Envelope transition(String actor, String organization, String requestId, String leadId,
                                String action, ActionRequest request) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_LEAD",
                service.transition(actor, organization, leadId,
                        new StatusCommand(action, request.expectedVersion(), request.reason()), resolved));
    }

    private Envelope reviewDuplicate(String actor, String organization, String requestId, String linkId,
                                     String action, DuplicateReviewRequest request) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_LEAD_DUPLICATE_LINK",
                service.reviewDuplicate(actor, organization, linkId,
                        new DuplicateReview(action, request.expectedVersion(), request.reason()), resolved));
    }

    private static Envelope ok(String requestId, String type, Object data) {
        return new Envelope(id(requestId), type, data,
                List.of("DEVELOPMENT_ONLY", "PENDING_VERIFICATION", "NOT_AN_INFRINGEMENT_FINDING", "LOCAL_QUERY_ONLY"));
    }

    private static String id(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String message(String code) {
        return switch (code) {
            case "TASK_NOT_FOUND", "LEAD_NOT_FOUND", "RULE_NOT_FOUND", "SOURCE_SYNC_TASK_NOT_FOUND" -> "资源不存在或当前机构无权访问";
            case "IDEMPOTENCY_CONFLICT" -> "幂等键已用于不同的发现任务输入";
            case "VERSION_CONFLICT" -> "数据版本已变化，请刷新后重试";
            case "INVALID_STATE_TRANSITION" -> "当前状态不允许执行该操作";
            case "REASON_REQUIRED" -> "排除或关闭时必须填写原因";
            case "SOURCE_SYNC_TASK_NOT_READY" -> "来源同步任务尚未完成";
            case "RULE_NOT_PUBLISHED" -> "只能使用已发布的开发期规则";
            case "DISCOVERY_STORE_UNAVAILABLE" -> "本地线索发现存储当前不可用";
            default -> "线索发现请求未完成，请检查字段后重试";
        };
    }

    public record ActionRequest(int expectedVersion, String reason) {}
    public record DuplicateReviewRequest(int expectedVersion, String reason) {}
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
