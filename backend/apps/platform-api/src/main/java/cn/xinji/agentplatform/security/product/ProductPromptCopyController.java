package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptCreateController.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Exact COPY coordinates and original-key recovery; response contains no source text or licence. */
@RestController
@ConditionalOnProperty(name="product.consultation.prompt.copy-enabled",havingValue="true")
@RequestMapping("/v2/consultation/prompt-workspaces")
final class ProductPromptCopyController {
    private static final String SOURCE_QUERY="sourceWorkspaceId=";
    private final ProductPromptCopyApplication application;
    ProductPromptCopyController(ProductPromptCopyApplication application){this.application=application;}

    @PostMapping(value="/{sourceWorkspaceId}/prompts/{sourcePromptId}/copies",produces="application/json")
    ResponseEntity<ReceiptView> copy(@PathVariable String sourceWorkspaceId,@PathVariable String sourcePromptId,
            @AuthenticationPrincipal Jwt principal,HttpServletRequest request)throws IOException{
        String token=token(principal),key=coordinates(sourceWorkspaceId,request);uuid(sourcePromptId);
        json(request);byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
        if(bytes.length>MAX_BODY)throw rejected(HttpStatus.PAYLOAD_TOO_LARGE);
        ProductPromptCopyCommand command;
        try{command=ProductPromptCopyCommand.decode(bytes);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        return ok(application.request(token,sourceWorkspaceId,sourcePromptId,key,command));
    }
    @GetMapping(value="/{targetWorkspaceId}/requests/COPY",produces="application/json")
    ResponseEntity<ReceiptView> lookup(@PathVariable String targetWorkspaceId,@AuthenticationPrincipal Jwt principal,
            HttpServletRequest request)throws IOException{
        String token=token(principal);uuid(targetWorkspaceId);
        String query=request.getQueryString();
        if(query==null||!query.startsWith(SOURCE_QUERY))throw rejected(HttpStatus.BAD_REQUEST);
        String source=query.substring(SOURCE_QUERY.length());uuid(source);
        String key=key(request);
        if(request.getInputStream().read()!=-1)throw rejected(HttpStatus.BAD_REQUEST);
        return ok(application.lookup(token,source,targetWorkspaceId,key));
    }
    private static void uuid(String value){
        try{ProductPromptRecords.uuid(value);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
    }
    private static String key(HttpServletRequest request){
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1)throw rejected(HttpStatus.BAD_REQUEST);
        String key=keys.getFirst();
        if(key.isEmpty()||key.length()>64||key.chars().anyMatch(c->c<33||c>126))throw rejected(HttpStatus.BAD_REQUEST);
        return key;
    }
    record ReceiptView(String receiptId,String workspaceId,String promptId,String revisionId,String generation,
            String contentSha,String resultSha,Instant submittedAt,Instant auditUntil){
        static ReceiptView from(ProductPromptRecords.Receipt value){
            if(value.operation()!=ProductPromptRecords.Operation.COPY||value.generation()!=1)throw ProductPromptRecords.unavailable();
            return new ReceiptView(value.id(),value.workspaceId(),value.promptId(),value.revisionId(),"1",value.contentSha(),
                    value.resultSha(),value.submittedAt(),value.auditUntil());
        }
    }
    private static ResponseEntity<ReceiptView> ok(ProductPromptRecords.Receipt value){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReceiptView.from(value));
    }
    @ExceptionHandler(ProductPromptCreateConflict.class)
    ResponseEntity<Map<String,String>> keyConflict(){return error(HttpStatus.CONFLICT,"PROMPT_COPY_KEY_CONFLICT");}
    @ExceptionHandler(ProductPromptAccessDenied.class)
    ResponseEntity<Map<String,String>> denied(){return error(HttpStatus.FORBIDDEN,"PROMPT_NOT_AVAILABLE");}
    @ExceptionHandler(JwtException.class)
    ResponseEntity<Map<String,String>> identity(){return error(HttpStatus.UNAUTHORIZED,"PROMPT_SESSION_UNAVAILABLE");}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,DataAccessException.class,IOException.class})
    ResponseEntity<Map<String,String>> unavailable(){return error(HttpStatus.SERVICE_UNAVAILABLE,"PROMPT_UNAVAILABLE");}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> invalid(ResponseStatusException failure){
        return switch(failure.getStatusCode().value()){
            case 401->identity();
            case 413->error(HttpStatus.PAYLOAD_TOO_LARGE,"PROMPT_REQUEST_TOO_LARGE");
            case 415->error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PROMPT_MEDIA_TYPE_UNSUPPORTED");
            default->error(HttpStatus.BAD_REQUEST,"INVALID_PROMPT_REQUEST");
        };
    }
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
    }
}
