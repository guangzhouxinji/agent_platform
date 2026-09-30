package cn.xinji.agentplatform.enforcement.adapter.in.web;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("test-synthetic")
@RequestMapping("/api/v0/enforcement/source-configurations")
public class EnforcementSourceManagementController {
    private final EnforcementSourceManagementService service;

    public EnforcementSourceManagementController(EnforcementSourceManagementService service) {
        this.service = service;
    }

    @GetMapping
    public Envelope list(@RequestHeader("X-V0-Actor") String actor,
                         @RequestHeader("X-V0-Organization") String organization,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ok(requestId, "SOURCE_CONFIGURATION_LIST", service.list(actor, organization));
    }

    @GetMapping("/{sourceId}")
    public Envelope read(@RequestHeader("X-V0-Actor") String actor,
                         @RequestHeader("X-V0-Organization") String organization,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @PathVariable String sourceId) {
        return ok(requestId, "SOURCE_CONFIGURATION", service.read(actor, organization, sourceId));
    }

    @PutMapping("/{sourceId}")
    public Envelope save(@RequestHeader("X-V0-Actor") String actor,
                         @RequestHeader("X-V0-Organization") String organization,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @PathVariable String sourceId, @RequestBody SaveRequest request) {
        if (request == null) throw EnforcementSourceManagementService.failure("INVALID_INPUT");
        SaveCommand command = new SaveCommand(sourceId, request.sourceName(), request.dataType(),
                request.accessMethod(), request.controlledEndpoint(), request.credentialRef(), request.fieldMapping(),
                request.ownerName(), request.scheduleMode(), request.scheduleInterval(), request.scheduleUnit(),
                request.enabled(), request.expectedVersion());
        return ok(requestId, request.expectedVersion() == null ? "SOURCE_CONFIGURATION_CREATED" : "SOURCE_CONFIGURATION_UPDATED",
                service.save(actor, organization, command));
    }

    @PostMapping("/{sourceId}/enabled")
    public Envelope setEnabled(@RequestHeader("X-V0-Actor") String actor,
                               @RequestHeader("X-V0-Organization") String organization,
                               @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                               @PathVariable String sourceId, @RequestBody EnableRequest request) {
        if (request == null) throw EnforcementSourceManagementService.failure("INVALID_INPUT");
        return ok(requestId, "SOURCE_CONFIGURATION_UPDATED", service.setEnabled(actor, organization, sourceId,
                request.expectedVersion(), request.enabled()));
    }

    @PostMapping("/{sourceId}/connection-tests")
    public Envelope test(@RequestHeader("X-V0-Actor") String actor,
                         @RequestHeader("X-V0-Organization") String organization,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @PathVariable String sourceId) {
        String resolved = id(requestId);
        return ok(resolved, "SOURCE_CONNECTION_TEST", service.testConnection(actor, organization, sourceId, resolved));
    }

    @PostMapping("/{sourceId}/sync-tasks")
    public Envelope synchronize(@RequestHeader("X-V0-Actor") String actor,
                                @RequestHeader("X-V0-Organization") String organization,
                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                @PathVariable String sourceId) {
        String resolved = id(requestId);
        return ok(resolved, "SOURCE_SYNC_TASK", service.synchronize(actor, organization, sourceId,
                idempotencyKey, "MANUAL", resolved));
    }

    @GetMapping("/{sourceId}/sync-tasks")
    public Envelope tasks(@RequestHeader("X-V0-Actor") String actor,
                          @RequestHeader("X-V0-Organization") String organization,
                          @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                          @PathVariable String sourceId) {
        return ok(requestId, "SOURCE_SYNC_TASK_LIST", service.tasks(actor, organization, sourceId));
    }

    @GetMapping("/{sourceId}/sync-tasks/{taskId}")
    public Envelope task(@RequestHeader("X-V0-Actor") String actor,
                         @RequestHeader("X-V0-Organization") String organization,
                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                         @PathVariable String sourceId, @PathVariable String taskId) {
        return ok(requestId, "SOURCE_SYNC_TASK", service.task(actor, organization, sourceId, taskId));
    }

    @GetMapping("/{sourceId}/history")
    public Envelope history(@RequestHeader("X-V0-Actor") String actor,
                            @RequestHeader("X-V0-Organization") String organization,
                            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                            @PathVariable String sourceId) {
        return ok(requestId, "SOURCE_CONFIGURATION_HISTORY", service.history(actor, organization, sourceId));
    }

    @ExceptionHandler(SourceManagementException.class)
    ResponseEntity<ErrorEnvelope> sourceError(SourceManagementException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "INVALID_INPUT", "FIELD_MAPPING_INVALID", "SCHEDULE_INVALID" -> HttpStatus.BAD_REQUEST;
            case "SOURCE_NOT_FOUND", "SOURCE_TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT", "SOURCE_ALREADY_EXISTS", "SOURCE_SYNC_IN_PROGRESS" -> HttpStatus.CONFLICT;
            case "SOURCE_DISABLED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "SOURCE_ADAPTER_NOT_ALLOWED" -> HttpStatus.FORBIDDEN;
            case "SOURCE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), message(error.code()), status.is5xxServerError(), null, "local-trace")));
    }

    private static Envelope ok(String requestId, String resultType, Object data) {
        return new Envelope(id(requestId), resultType, data, List.of(
                "本地开发数据源管理；非真实中心联调、非全网采集、非违法认定。"));
    }
    private static String id(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
    private static String message(String code) {
        return switch (code) {
            case "SOURCE_NOT_FOUND", "SOURCE_TASK_NOT_FOUND" -> "资源不存在或当前机构无权访问";
            case "VERSION_CONFLICT" -> "配置版本已变化，请刷新后重试";
            case "SOURCE_SYNC_IN_PROGRESS" -> "该数据源已有同步任务正在运行";
            case "SOURCE_DISABLED" -> "数据源已停用，不能创建新同步任务";
            case "SOURCE_ADAPTER_NOT_ALLOWED" -> "当前接入方式不在受控适配器范围内";
            case "SOURCE_UNAVAILABLE" -> "本地数据源存储当前不可用";
            default -> "请求未完成，请检查配置后重试";
        };
    }

    public record SaveRequest(String sourceName, String dataType, String accessMethod, String controlledEndpoint,
                              String credentialRef, Map<String, String> fieldMapping, String ownerName,
                              String scheduleMode, Integer scheduleInterval, String scheduleUnit,
                              boolean enabled, Integer expectedVersion) {}
    public record EnableRequest(int expectedVersion, boolean enabled) {}
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
