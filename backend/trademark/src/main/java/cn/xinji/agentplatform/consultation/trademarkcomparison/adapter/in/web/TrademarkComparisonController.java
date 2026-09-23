package cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.in.web;

import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonService;
import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonService.*;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand.*;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v0/trademark-comparisons")
public class TrademarkComparisonController {
    private final TrademarkComparisonService service;
    public TrademarkComparisonController(TrademarkComparisonService service){this.service=service;}
    @PostMapping("/preparations") Preparation prepare(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody PrepareRequest body){return service.prepare(actor,body.purpose(),body.target(),body.candidates(),body.dimensions(),id(requestId));}
    @PostMapping("/tasks") TrademarkComparisonTask create(@RequestHeader("X-V0-Actor")String actor,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="X-Request-Id",required=false)String requestId,@RequestBody TrademarkComparisonCommand body){return service.create(actor,key,body,id(requestId));}
    @GetMapping("/tasks/{taskId}") TrademarkComparisonTask get(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String taskId){return service.get(actor,taskId,id(requestId));}
    @GetMapping("/tasks") List<TrademarkComparisonTask> history(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId){return service.history(actor,id(requestId));}
    @ExceptionHandler(ComparisonException.class) ResponseEntity<ErrorResponse> error(ComparisonException exception){return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(),exception.getMessage(),exception.requestId()));}
    private static String id(String value){return value==null||value.isBlank()?UUID.randomUUID().toString():value;}
    record PrepareRequest(Purpose purpose,DocumentRef target,List<DocumentRef> candidates,List<Dimension> dimensions){}
    record ErrorResponse(String code,String message,String requestId){}
}
