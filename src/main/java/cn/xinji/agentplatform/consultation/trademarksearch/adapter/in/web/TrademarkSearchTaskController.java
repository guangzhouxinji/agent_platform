package cn.xinji.agentplatform.consultation.trademarksearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchApplicationService;
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
@RequestMapping("/api/v0/trademark-search-tasks")
public class TrademarkSearchTaskController {
    private final TrademarkSearchApplicationService service;

    public TrademarkSearchTaskController(TrademarkSearchApplicationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<TrademarkSearchTaskResponse> create(
            @RequestHeader("X-V0-Requester") String requester,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateTrademarkSearchTaskRequest request) {
        if (request == null) throw new TrademarkSearchApplicationService.InvalidRequestException("request body is required");
        try {
            return ResponseEntity.accepted().body(TrademarkSearchTaskResponse.from(service.create(requester, key, request.toCommand())));
        } catch (UnsupportedOperationException exception) {
            throw new DimensionUnavailableException(exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new TrademarkSearchApplicationService.InvalidRequestException(exception.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public TrademarkSearchTaskResponse read(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return TrademarkSearchTaskResponse.from(service.read(requester, taskId));
    }

    @GetMapping
    public List<TrademarkSearchTaskResponse> history(@RequestHeader("X-V0-Requester") String requester) {
        return service.history(requester).stream().map(TrademarkSearchTaskResponse::from).toList();
    }

    @PostMapping("/{taskId}/cancel")
    public TrademarkSearchTaskResponse cancel(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return TrademarkSearchTaskResponse.from(service.cancel(requester, taskId));
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<TrademarkSearchTaskResponse> retry(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return ResponseEntity.accepted().body(TrademarkSearchTaskResponse.from(service.retry(requester, taskId)));
    }

    @ExceptionHandler(TrademarkSearchApplicationService.InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(TrademarkSearchApplicationService.InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(DimensionUnavailableException.class)
    ResponseEntity<ErrorResponse> dimensionUnavailable(DimensionUnavailableException exception) {
        return ResponseEntity.unprocessableEntity().body(new ErrorResponse("DIMENSION_UNAVAILABLE", exception.getMessage()));
    }

    @ExceptionHandler(TrademarkSearchApplicationService.IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求"));
    }

    @ExceptionHandler(TrademarkSearchApplicationService.InvalidStateException.class)
    ResponseEntity<ErrorResponse> invalidState(TrademarkSearchApplicationService.InvalidStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("INVALID_TASK_STATE", exception.getMessage()));
    }

    @ExceptionHandler(TrademarkSearchApplicationService.AccessDeniedException.class)
    ResponseEntity<ErrorResponse> denied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("ACCESS_DENIED", "无权执行商标检索"));
    }

    @ExceptionHandler(TrademarkSearchApplicationService.NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("TASK_NOT_FOUND", "任务不存在"));
    }

    record ErrorResponse(String code, String message) {}
    private static final class DimensionUnavailableException extends RuntimeException {
        private DimensionUnavailableException(String message) { super(message); }
    }
}
