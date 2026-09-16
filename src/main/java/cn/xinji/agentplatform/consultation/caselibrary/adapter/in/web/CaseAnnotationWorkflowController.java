package cn.xinji.agentplatform.consultation.caselibrary.adapter.in.web;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v0/case-annotations") public class CaseAnnotationWorkflowController{
 private final CaseAnnotationWorkflowService service;public CaseAnnotationWorkflowController(CaseAnnotationWorkflowService s){service=s;}
 @PostMapping("/extractions")Task extract(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody ExtractRequest r){return service.extract(actor,r.caseId(),r.sourceVersion(),r.config(),id(requestId));}
 @GetMapping("/cases/{caseId}")Snapshot snapshot(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String caseId){return service.snapshot(actor,caseId,id(requestId));}
 @PostMapping("/{annotationId}/revisions")Annotation revise(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String annotationId,@RequestBody RevisionRequest r){return service.revise(actor,annotationId,r.expectedVersion(),r.fact(),r.issue(),r.holding(),r.sourceLocation(),r.reason(),id(requestId));}
 @PostMapping("/{annotationId}/submit")Annotation submit(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String annotationId,@RequestBody VersionRequest r){return service.submit(actor,annotationId,r.expectedVersion(),id(requestId));}
 @PostMapping("/{annotationId}/reviews")Annotation review(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String annotationId,@RequestBody ReviewRequest r){return service.review(actor,annotationId,r.expectedVersion(),r.action(),r.reason(),id(requestId));}
 @PostMapping("/{annotationId}/withdraw")Annotation withdraw(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String annotationId,@RequestBody ReviewRequest r){return service.withdraw(actor,annotationId,r.expectedVersion(),r.reason(),id(requestId));}
 @ExceptionHandler(WorkflowException.class)ResponseEntity<ErrorResponse> error(WorkflowException e){return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(),e.getMessage(),e.requestId()));}private static String id(String v){return v==null||v.isBlank()?UUID.randomUUID().toString():v;}
 record ExtractRequest(String caseId,String sourceVersion,String config){}record RevisionRequest(int expectedVersion,String fact,String issue,String holding,String sourceLocation,String reason){}record VersionRequest(int expectedVersion){}record ReviewRequest(int expectedVersion,String action,String reason){}record ErrorResponse(String code,String message,String requestId){}
}
