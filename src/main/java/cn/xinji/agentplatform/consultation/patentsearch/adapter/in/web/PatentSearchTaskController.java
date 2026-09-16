package cn.xinji.agentplatform.consultation.patentsearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchApplicationService;
import java.util.List;
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
@RequestMapping("/api/v0/patent-search-tasks")
public class PatentSearchTaskController {
    private final PatentSearchApplicationService service;

    public PatentSearchTaskController(PatentSearchApplicationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PatentSearchTaskResponse> create(
            @RequestHeader("X-V0-Requester") String requester,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreatePatentSearchTaskRequest request) {
        if (request == null) throw new PatentSearchApplicationService.InvalidRequestException("request body is required");
        try {
            return ResponseEntity.accepted().body(PatentSearchTaskResponse.from(service.create(requester, key, request.toCommand())));
        } catch (IllegalArgumentException exception) {
            throw new PatentSearchApplicationService.InvalidRequestException(exception.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public PatentSearchTaskResponse read(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return PatentSearchTaskResponse.from(service.read(requester, taskId));
    }

    @GetMapping
    public List<PatentSearchTaskResponse> history(@RequestHeader("X-V0-Requester") String requester) {
        return service.history(requester).stream().map(PatentSearchTaskResponse::from).toList();
    }

    @PostMapping("/{taskId}/cancel")
    public PatentSearchTaskResponse cancel(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return PatentSearchTaskResponse.from(service.cancel(requester, taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<PatentSearchTaskResponse> retry(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return ResponseEntity.accepted().body(PatentSearchTaskResponse.from(service.retry(requester, taskId)));
    }

    @ExceptionHandler(PatentSearchApplicationService.InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(PatentSearchApplicationService.InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(PatentSearchApplicationService.IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求"));
    }

    @ExceptionHandler(PatentSearchApplicationService.InvalidStateException.class)
    ResponseEntity<ErrorResponse> invalidState(PatentSearchApplicationService.InvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("INVALID_TASK_STATE", exception.getMessage()));
    }

    @ExceptionHandler(PatentSearchApplicationService.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("ACCESS_DENIED", "无权执行专利检索"));
    }

    @ExceptionHandler(PatentSearchApplicationService.NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("TASK_NOT_FOUND", "任务不存在"));
    }

    record ErrorResponse(String code, String message) {}
}
