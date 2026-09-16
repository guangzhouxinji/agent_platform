package cn.xinji.agentplatform.consultation.patentcomparison.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonService;
import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonService.*;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonCommand;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonCommand.*;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonTask;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v0/patent-comparisons")
public class PatentComparisonController {
    private final PatentComparisonService service;public PatentComparisonController(PatentComparisonService service){this.service=service;}
    @PostMapping("/preparations")Preparation prepare(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody PrepareRequest body){return service.prepare(actor,body.target(),body.candidates(),body.purpose(),id(requestId));}
    @PostMapping("/tasks")PatentComparisonTask create(@RequestHeader("X-V0-Actor")String actor,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody PatentComparisonCommand body){return service.create(actor,key,body,id(requestId));}
    @GetMapping("/tasks/{taskId}")PatentComparisonTask get(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String taskId){return service.get(actor,taskId,id(requestId));}
    @GetMapping("/tasks")List<PatentComparisonTask> history(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId){return service.history(actor,id(requestId));}
    @ExceptionHandler(ComparisonException.class)ResponseEntity<ErrorResponse> error(ComparisonException e){return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(),e.getMessage(),e.requestId()));}
    private static String id(String value){return value==null||value.isBlank()?UUID.randomUUID().toString():value;}
    record PrepareRequest(Purpose purpose,DocumentRef target,List<DocumentRef> candidates){}record ErrorResponse(String code,String message,String requestId){}
}
