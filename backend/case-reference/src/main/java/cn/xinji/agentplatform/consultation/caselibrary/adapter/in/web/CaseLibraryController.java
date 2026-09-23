package cn.xinji.agentplatform.consultation.caselibrary.adapter.in.web;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryService;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryService.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v0/case-library")
public class CaseLibraryController {
    private final CaseLibraryService service; public CaseLibraryController(CaseLibraryService service){this.service=service;}
    @GetMapping public Page query(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,
      @RequestParam(required=false)String caseNumber,@RequestParam(required=false)String docketNumber,@RequestParam(required=false)String title,
      @RequestParam(required=false)List<String> caseType,@RequestParam(required=false)List<String> ipType,@RequestParam(required=false)List<String> institution,
      @RequestParam(required=false)List<String> procedure,@RequestParam(required=false)List<String> annotationState,
      @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate decisionFrom,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate decisionTo,
      @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return service.query(actor,new Query(caseNumber,docketNumber,title,split(caseType),split(ipType),split(institution),split(procedure),split(annotationState),decisionFrom,decisionTo,page,pageSize),id(requestId));}
    @GetMapping("/{sourceId}") public Detail detail(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String sourceId){return service.detail(actor,sourceId,id(requestId));}
    @GetMapping("/{sourceId}/documents/{versionId}") public Document document(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String sourceId,@PathVariable String versionId){return service.document(actor,sourceId,versionId,id(requestId));}
    @GetMapping("/{sourceId}/published-annotation") public Annotation annotation(@RequestHeader("X-V0-Actor")String actor,@RequestHeader(value="X-Request-Id",required=false)String requestId,@PathVariable String sourceId){return service.annotation(actor,sourceId,id(requestId));}
    @ExceptionHandler(LibraryException.class)ResponseEntity<ErrorResponse> error(LibraryException e){return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(),e.getMessage(),e.requestId()));}
    private static List<String> split(List<String> v){return v==null?List.of():v.stream().flatMap(x->Arrays.stream(x.split(","))).map(String::trim).filter(x->!x.isEmpty()).distinct().toList();}private static String id(String v){return v==null||v.isBlank()?UUID.randomUUID().toString():v;}record ErrorResponse(String code,String message,String requestId){}
}
