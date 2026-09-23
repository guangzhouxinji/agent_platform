package cn.xinji.agentplatform.enforcement.adapter.in.web;

import static cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService.*;

import cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v0/enforcement/evidence")
public class EnforcementEvidenceController {
    private final EnforcementEvidenceService service;

    public EnforcementEvidenceController(EnforcementEvidenceService service) { this.service = service; }

    @GetMapping
    Envelope list(@RequestHeader("X-V0-Actor") String actor,
                  @RequestHeader("X-V0-Organization") String organization,
                  @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                  @RequestParam(required = false) String leadId,
                  @RequestParam(required = false) String type,
                  @RequestParam(required = false) String status,
                  @RequestParam(defaultValue = "1") int page,
                  @RequestParam(defaultValue = "20") int pageSize) {
        return ok(requestId, "ENFORCEMENT_EVIDENCE_PAGE", service.list(actor, organization, leadId, type, status, page, pageSize));
    }

    @GetMapping("/{evidenceId}")
    Envelope detail(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String evidenceId) {
        return ok(requestId, "ENFORCEMENT_EVIDENCE", service.detail(actor, organization, evidenceId));
    }

    @PostMapping
    Envelope create(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @RequestBody CreateCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_EVIDENCE", service.create(actor, organization, command, resolved));
    }

    @PutMapping("/{evidenceId}")
    Envelope revise(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String evidenceId, @RequestBody RevisionCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_EVIDENCE", service.revise(actor, organization, evidenceId, command, resolved));
    }

    @PostMapping("/{evidenceId}/derived-versions")
    Envelope derived(@RequestHeader("X-V0-Actor") String actor,
                     @RequestHeader("X-V0-Organization") String organization,
                     @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                     @PathVariable String evidenceId, @RequestBody DerivedCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_EVIDENCE", service.addDerivedVersion(actor, organization, evidenceId, command, resolved));
    }

    @PostMapping("/{evidenceId}/status")
    Envelope status(@RequestHeader("X-V0-Actor") String actor,
                    @RequestHeader("X-V0-Organization") String organization,
                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                    @PathVariable String evidenceId, @RequestBody StatusCommand command) {
        String resolved = id(requestId);
        return ok(resolved, "ENFORCEMENT_EVIDENCE", service.changeStatus(actor, organization, evidenceId, command, resolved));
    }

    @GetMapping("/{evidenceId}/versions/{version}/preview")
    ResponseEntity<byte[]> preview(@RequestHeader("X-V0-Actor") String actor,
                                   @RequestHeader("X-V0-Organization") String organization,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                   @PathVariable String evidenceId, @PathVariable int version) {
        return content(service.content(actor, organization, evidenceId, version, "PREVIEW", id(requestId)), false);
    }

    @GetMapping("/{evidenceId}/versions/{version}/download")
    ResponseEntity<byte[]> download(@RequestHeader("X-V0-Actor") String actor,
                                    @RequestHeader("X-V0-Organization") String organization,
                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                    @PathVariable String evidenceId, @PathVariable int version) {
        return content(service.content(actor, organization, evidenceId, version, "DOWNLOAD", id(requestId)), true);
    }

    @ExceptionHandler(EvidenceException.class)
    ResponseEntity<ErrorEnvelope> evidenceError(EvidenceException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case "EVIDENCE_NOT_FOUND", "LEAD_NOT_FOUND", "CONTENT_VERSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "VERSION_CONFLICT", "INVALID_STATE_TRANSITION" -> HttpStatus.CONFLICT;
            case "PREVIEW_UNAVAILABLE", "CONTENT_NOT_STORED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "EVIDENCE_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorEnvelope(id(request.getHeader("X-Request-Id")),
                new ErrorBody(error.code(), message(error.code()), status.is5xxServerError(), error.field(), "local-trace")));
    }

    private static ResponseEntity<byte[]> content(ContentResource value, boolean attachment) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(value.mediaType()));
        headers.setContentDisposition(ContentDisposition.builder(attachment ? "attachment" : "inline")
                .filename(value.fileName(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(value.bytes(), headers, HttpStatus.OK);
    }

    private static Envelope ok(String requestId, String type, Object data) {
        return new Envelope(id(requestId), type, data,
                List.of("DEVELOPMENT_ONLY", "NOT_EVIDENCE_ACCEPTANCE", "NO_MODEL_USED", "LOCAL_DM8_PRIMARY"));
    }
    private static String id(String value) { return value == null || value.isBlank() ? UUID.randomUUID().toString() : value; }
    private static String message(String code) {
        return switch (code) {
            case "EVIDENCE_NOT_FOUND", "LEAD_NOT_FOUND", "CONTENT_VERSION_NOT_FOUND" -> "资源不存在或当前机构无权访问";
            case "VERSION_CONFLICT" -> "证据记录已变化，请刷新后重试";
            case "INVALID_STATE_TRANSITION" -> "当前证据状态不允许该操作";
            case "PREVIEW_UNAVAILABLE" -> "该格式无法在线预览，可在授权后下载";
            case "CONTENT_NOT_STORED" -> "该记录仅保存受控资源引用，本地没有可下载字节";
            case "FILE_TOO_LARGE" -> "文件超过当前开发环境允许的大小";
            case "MEDIA_TYPE_NOT_ALLOWED" -> "当前开发环境未允许该文件类型";
            case "COLLECTION_INFO_REQUIRED", "COLLECTION_INFO_INCOMPLETE" -> "请填写完整收集信息，未知时填写缺失原因";
            case "EVIDENCE_STORE_UNAVAILABLE" -> "本地证据存储当前不可用";
            default -> "证据请求未完成，请检查字段后重试";
        };
    }
    public record Envelope(String requestId, String resultType, Object data, List<String> limitations) {}
    public record ErrorEnvelope(String requestId, ErrorBody error) {}
    public record ErrorBody(String code, String message, boolean retryable, String fieldPath, String traceRef) {}
}
