package cn.xinji.agentplatform.enforcement.adapter.in.web;

import static cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementDiscoveryRuleService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v0/enforcement/discovery-rules")
public class EnforcementDiscoveryRuleController {
    private final EnforcementDiscoveryRuleService service;
    public EnforcementDiscoveryRuleController(EnforcementDiscoveryRuleService service) { this.service = service; }

    @GetMapping
    Envelope list(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @RequestParam(required = false) String status, @RequestParam(required = false) String category,
                  @RequestParam(required = false) String name, @RequestParam(required = false) String sourceId) {
        return ok(requestId, "DISCOVERY_RULE_LIST", service.list(actor, organization,
                new RuleFilter(status, category, name, sourceId)));
    }

    @PostMapping
    Envelope create(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @RequestBody SaveRequest request) {
        return ok(requestId, "DISCOVERY_RULE_CREATED", service.create(actor, organization, request.command()));
    }

    @GetMapping("/{ruleId}")
    Envelope read(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @PathVariable String ruleId) {
        return ok(requestId, "DISCOVERY_RULE", service.read(actor, organization, ruleId, null));
    }

    @GetMapping("/{ruleId}/versions")
    Envelope versions(@RequestHeader("X-V0-Actor") String actor,
                      @RequestHeader("X-V0-Organization") String organization,
                      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                      @PathVariable String ruleId) {
        return ok(requestId, "DISCOVERY_RULE_VERSION_LIST", service.versions(actor, organization, ruleId));
    }

    @GetMapping("/{ruleId}/versions/{version}")
    Envelope version(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String ruleId, @PathVariable int version) {
        return ok(requestId, "DISCOVERY_RULE", service.read(actor, organization, ruleId, version));
    }

    @PutMapping("/{ruleId}/versions/{version}")
    Envelope update(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String ruleId, @PathVariable int version, @RequestBody SaveRequest request) {
        return ok(requestId, "DISCOVERY_RULE_UPDATED", service.update(actor, organization, ruleId, version, request.command()));
    }

    @PostMapping("/{ruleId}/versions/{version}/copies")
    Envelope copy(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @PathVariable String ruleId, @PathVariable int version) {
        return ok(requestId, "DISCOVERY_RULE_COPIED", service.copy(actor, organization, ruleId, version));
    }

    @PostMapping("/{ruleId}/versions/{version}/dry-runs")
    Envelope run(@RequestHeader("X-V0-Actor") String actor,
                 @RequestHeader("X-V0-Organization") String organization,
                 @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                 @PathVariable String ruleId, @PathVariable int version, @RequestBody DryRunCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "DISCOVERY_RULE_DRY_RUN", service.dryRun(actor, organization, ruleId, version, command, resolved));
    }

    @GetMapping("/{ruleId}/versions/{version}/dry-runs")
    Envelope runs(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @PathVariable String ruleId, @PathVariable int version) {
        return ok(requestId, "DISCOVERY_RULE_DRY_RUN_LIST", service.runs(actor, organization, ruleId, version));
    }

    @PostMapping("/{ruleId}/versions/{version}/publish")
    Envelope publish(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String ruleId, @PathVariable int version, @RequestBody TransitionRequest request) {
        return ok(requestId, "DISCOVERY_RULE_PUBLISHED", service.publish(actor, organization, ruleId, version, request.expectedRevision()));
    }

    @PostMapping("/{ruleId}/versions/{version}/disable")
    Envelope disable(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String ruleId, @PathVariable int version, @RequestBody TransitionRequest request) {
        return ok(requestId, "DISCOVERY_RULE_DISABLED", service.disable(actor, organization, ruleId, version, request.expectedRevision()));
    }

    @ExceptionHandler(RuleException.class)
    ResponseEntity<ErrorEnvelope> ruleError(RuleException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "RULE_NOT_FOUND", "SOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "RULE_ALREADY_EXISTS", "RULE_VERSION_IMMUTABLE", "INVALID_STATE_TRANSITION" -> HttpStatus.CONFLICT;
            case "SOURCE_DISABLED", "SOURCE_UNAVAILABLE", "SUCCESSFUL_DRY_RUN_REQUIRED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "RULE_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), message(error.code()), status.is5xxServerError(), error.field(), "local-trace")));
    }

    private static Envelope ok(String requestId, String type, Object data) {
        return new Envelope(id(requestId), type, data, List.of("DEVELOPMENT_ONLY", "NOT_PROFESSIONAL_RULE"));
    }
    private static String id(String value) { return value == null || value.isBlank() ? UUID.randomUUID().toString() : value; }
    private static String message(String code) {
        return switch (code) {
            case "RULE_NOT_FOUND", "SOURCE_NOT_FOUND" -> "资源不存在或当前机构无权访问";
            case "VERSION_CONFLICT" -> "规则版本已变化，请刷新后重试";
            case "RULE_VERSION_IMMUTABLE" -> "已发布或停用的规则版本不可修改，请复制新版本";
            case "SOURCE_DISABLED" -> "规则引用的数据源已停用";
            case "SOURCE_UNAVAILABLE" -> "规则引用的数据源尚未通过连接检查";
            case "SUCCESSFUL_DRY_RUN_REQUIRED" -> "发布前需对当前内容完成一次成功的合成样例试运行";
            case "RULE_STORE_UNAVAILABLE" -> "本地规则存储当前不可用";
            default -> "规则请求未完成，请检查字段后重试";
        };
    }

    public record SaveRequest(String ruleId, String ruleName, String category, String description,
                              List<String> sourceIds, Condition condition, Integer expectedRevision) {
        SaveCommand command() { return new SaveCommand(ruleId, ruleName, category, description, sourceIds, condition, expectedRevision); }
    }
    public record TransitionRequest(int expectedRevision) {}
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
