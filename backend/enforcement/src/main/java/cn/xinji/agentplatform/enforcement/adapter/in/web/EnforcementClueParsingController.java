package cn.xinji.agentplatform.enforcement.adapter.in.web;

import static cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementClueParsingService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/enforcement/leads/{leadId}")
public class EnforcementClueParsingController {
    private final EnforcementClueParsingService service;

    public EnforcementClueParsingController(EnforcementClueParsingService service) {
        this.service = service;
    }

    @PostMapping("/parse-tasks")
    Envelope parse(@RequestHeader("X-V0-Actor") String actor,
                   @RequestHeader("X-V0-Organization") String organization,
                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                   @PathVariable String leadId) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_CLUE_PARSE_TASK",
                service.parse(actor, organization, leadId, idempotencyKey, resolved));
    }

    @GetMapping("/parse-tasks")
    Envelope tasks(@RequestHeader("X-V0-Actor") String actor,
                   @RequestHeader("X-V0-Organization") String organization,
                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                   @PathVariable String leadId) {
        return ok(requestId, "ENFORCEMENT_CLUE_PARSE_TASK_LIST", service.tasks(actor, organization, leadId));
    }

    @GetMapping("/parses")
    Envelope results(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String leadId) {
        return ok(requestId, "ENFORCEMENT_CLUE_PARSE_RESULT_LIST", service.results(actor, organization, leadId));
    }

    @GetMapping("/parses/{version}")
    Envelope result(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String leadId, @PathVariable int version) {
        return ok(requestId, "ENFORCEMENT_CLUE_PARSE_RESULT", service.result(actor, organization, leadId, version));
    }

    @PostMapping("/parses/{version}/revisions")
    Envelope revise(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String leadId, @PathVariable int version,
                    @RequestBody RevisionRequest request) {
        String resolved = id(requestId);
        if (request.expectedVersion() != version) throw failure("INVALID_INPUT", "expectedVersion");
        return ok(resolved, "ENFORCEMENT_CLUE_PARSE_RESULT",
                service.revise(actor, organization, leadId,
                        new RevisionCommand(request.expectedVersion(), request.reason(), request.fields()), resolved));
    }

    @PostMapping("/parses/{version}/confirm")
    Envelope confirm(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String leadId, @PathVariable int version,
                     @RequestBody ReviewRequest request) {
        return review(actor, organization, requestId, leadId, version, "CONFIRM", request);
    }

    @PostMapping("/parses/{version}/return")
    Envelope returnForRevision(@RequestHeader("X-V0-Actor") String actor,
                               @RequestHeader("X-V0-Organization") String organization,
                               @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                               @PathVariable String leadId, @PathVariable int version,
                               @RequestBody ReviewRequest request) {
        return review(actor, organization, requestId, leadId, version, "RETURN", request);
    }

    @ExceptionHandler(ParsingException.class)
    ResponseEntity<ErrorEnvelope> parsingError(ParsingException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "LEAD_NOT_FOUND", "PARSE_RESULT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "RESULT_IMMUTABLE" -> HttpStatus.CONFLICT;
            case "LEAD_STATE_NOT_PARSEABLE", "SOURCE_WITHDRAWN", "FROZEN_INPUT_CHANGED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "PARSING_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), message(error.code()), status.is5xxServerError(), error.field(), "local-trace")));
    }

    private Envelope review(String actor, String organization, String requestId, String leadId, int version,
                            String action, ReviewRequest request) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_CLUE_PARSE_RESULT",
                service.review(actor, organization, leadId, version,
                        new ReviewCommand(action, request.expectedVersion(), request.reason()), resolved));
    }

    private static Envelope ok(String requestId, String type, Object data) {
        return new Envelope(id(requestId), type, data,
                List.of("DEVELOPMENT_ONLY", "PENDING_HUMAN_REVIEW", "NOT_A_LEGAL_FINDING",
                        "NO_MODEL_USED", "EVIDENCE_NOT_ACCEPTED"));
    }

    private static String id(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    private static String message(String code) {
        return switch (code) {
            case "LEAD_NOT_FOUND", "PARSE_RESULT_NOT_FOUND" -> "资源不存在或当前机构无权访问";
            case "IDEMPOTENCY_CONFLICT" -> "幂等键已用于不同的解析输入";
            case "VERSION_CONFLICT" -> "解析版本已变化，请刷新后重试";
            case "RESULT_IMMUTABLE" -> "已确认的解析版本不可修改";
            case "INVALID_STATE_TRANSITION" -> "当前审核状态不允许该操作";
            case "LEAD_STATE_NOT_PARSEABLE" -> "当前候选状态不可解析";
            case "SOURCE_WITHDRAWN" -> "来源已撤回，不能发起新的解析";
            case "FROZEN_INPUT_CHANGED" -> "冻结快照校验失败";
            case "REASON_REQUIRED" -> "退回或修订必须填写原因";
            case "PARSING_STORE_UNAVAILABLE" -> "本地线索解析存储当前不可用";
            default -> "线索解析请求未完成，请检查字段后重试";
        };
    }

    public record RevisionRequest(int expectedVersion, String reason, List<FieldInput> fields) {}
    public record ReviewRequest(int expectedVersion, String reason) {}
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
