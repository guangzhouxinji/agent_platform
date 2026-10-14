package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.application.ProductTrademarkReadBoundaryPort;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/trademarks")
@ConditionalOnProperty(name="product.consultation.trademark-read.enabled",havingValue="true")
final class ProductTrademarkLibraryController {
    private final ProductTrademarkLibraryConfiguration.Assembly reads;
    ProductTrademarkLibraryController(ProductTrademarkLibraryConfiguration.Assembly reads) { this.reads=reads; }
    @ModelAttribute void noStore(jakarta.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    @GetMapping ProductTrademarkReadTransaction.Response<Page> query(@PathVariable String taskId,
            @RequestHeader("Authorization") String authorization,@RequestHeader("X-Request-Id") String requestId,
            @RequestParam(required=false) String registrationNumber,@RequestParam(required=false) String name,@RequestParam(required=false) String applicant,
            @RequestParam(required=false) Set<String> categories,@RequestParam(required=false) Set<String> statuses,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate registeredTo,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validTo,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="false") boolean refresh) {
        text(registrationNumber);text(name);text(applicant);values(categories);values(statuses);
        var query=new Query(registrationNumber,name,applicant,categories,statuses,registeredFrom,registeredTo,validFrom,validTo,new PageRequest(page,size,null),refresh);
        var request=request(authorization,taskId,Operation.QUERY,null,requestId);
        return reads.boundary().response(()->reads.service().query(request,query));
    }
    @GetMapping("/sources/{sourceId}/records/{recordId}") ProductTrademarkReadTransaction.Response<Detail> detail(@PathVariable String taskId,
            @PathVariable String sourceId,@PathVariable String recordId,@RequestParam String version,
            @RequestHeader("Authorization") String authorization,@RequestHeader("X-Request-Id") String requestId,
            @RequestParam(defaultValue="false") boolean refresh) {
        text(sourceId);text(recordId);text(version);
        var request=request(authorization,taskId,Operation.DETAIL,new RecordRef(sourceId,recordId,version),requestId);
        return reads.boundary().response(()->reads.service().detail(request,refresh));
    }
    @GetMapping("/records/detail") ProductTrademarkReadTransaction.Response<Detail> detailByQuery(@PathVariable String taskId,
            @RequestParam MultiValueMap<String,String> parameters,
            @RequestHeader("Authorization") String authorization,@RequestHeader("X-Request-Id") String requestId) {
        require(Set.of("sourceId","recordId","version","refresh").containsAll(parameters.keySet()),Code.INVALID_REQUEST);
        for(String key:parameters.keySet())require(parameters.get(key).size()==1,Code.INVALID_REQUEST);
        for(String key:Set.of("sourceId","recordId","version"))
            require(parameters.containsKey(key)&&parameters.getFirst(key)!=null&&!parameters.getFirst(key).isBlank(),Code.INVALID_REQUEST);
        String refresh=parameters.getFirst("refresh");
        require(refresh==null||refresh.equals("true")||refresh.equals("false"),Code.INVALID_REQUEST);
        return detail(taskId,parameters.getFirst("sourceId"),parameters.getFirst("recordId"),parameters.getFirst("version"),authorization,requestId,Boolean.parseBoolean(refresh));
    }
    private static ProductTrademarkReadBoundaryPort.Request request(String authorization,String task,Operation operation,RecordRef ref,String id) {
        require(authorization!=null&&authorization.startsWith("Bearer "),Code.UNAUTHENTICATED);
        require(id!=null&&id.matches("[A-Za-z0-9._:-]{1,100}")&&task!=null&&task.length()<=160,Code.INVALID_REQUEST);
        return new ProductTrademarkReadBoundaryPort.Request(authorization.substring(7),task,operation,ref,id);
    }
    private static void text(String value) { require(value==null||value.length()<=1000&&!value.chars().anyMatch(Character::isISOControl),Code.INVALID_REQUEST); }
    private static void values(Set<String> values) { if(values!=null){require(values.size()<=50,Code.INVALID_REQUEST);values.forEach(ProductTrademarkLibraryController::text);} }
    record Error(String code) { }
    @ExceptionHandler(Failure.class) ResponseEntity<Error> failed(Failure failure) { return ResponseEntity.status(failure.code().status()).body(new Error(failure.code().name())); }
    @ExceptionHandler(QueryTimeoutException.class) ResponseEntity<Error> timeout() { return ResponseEntity.status(504).body(new Error("SOURCE_TIMEOUT")); }
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class,IllegalArgumentException.class})
    ResponseEntity<Error> unavailable() { return ResponseEntity.status(503).body(new Error("SOURCE_UNAVAILABLE")); }
}
