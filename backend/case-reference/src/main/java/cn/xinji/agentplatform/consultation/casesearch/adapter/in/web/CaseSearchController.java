package cn.xinji.agentplatform.consultation.casesearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.casesearch.application.CaseSearchApplicationService;
import cn.xinji.agentplatform.consultation.casesearch.application.CaseSearchApplicationService.Preparation;
import cn.xinji.agentplatform.consultation.casesearch.application.CaseSearchApplicationService.SearchException;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCommand;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchTask;
import java.util.List;
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
@RequestMapping("/api/v0/case-search-tasks")
public class CaseSearchController {
    private final CaseSearchApplicationService service;
    public CaseSearchController(CaseSearchApplicationService service) { this.service = service; }

    @PostMapping("/preparations")
    public Preparation prepare(@RequestHeader("X-V0-Requester") String requester, @RequestBody PrepareRequest request) {
        if (request == null) throw new SearchException("INVALID_REQUEST", "请求体不能为空", org.springframework.http.HttpStatus.BAD_REQUEST);
        return service.prepare(requester, request.question());
    }

    @PostMapping
    public ResponseEntity<CaseSearchTask> create(@RequestHeader("X-V0-Requester") String requester,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CaseSearchCommand command) {
        try { return ResponseEntity.accepted().body(service.create(requester, key, command)); }
        catch (IllegalArgumentException exception) { throw new SearchException("INVALID_REQUEST", exception.getMessage(), org.springframework.http.HttpStatus.BAD_REQUEST); }
    }

    @GetMapping("/{taskId}")
    public CaseSearchTask read(@RequestHeader("X-V0-Requester") String requester, @PathVariable String taskId) {
        return service.read(requester, taskId);
    }

    @GetMapping
    public List<CaseSearchTask> history(@RequestHeader("X-V0-Requester") String requester) {
        return service.history(requester);
    }

    @ExceptionHandler(SearchException.class)
    ResponseEntity<ErrorResponse> error(SearchException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    record PrepareRequest(String question) {}
    record ErrorResponse(String code, String message) {}
}
