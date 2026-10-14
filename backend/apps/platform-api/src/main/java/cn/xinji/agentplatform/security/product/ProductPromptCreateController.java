package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Strict bounded transport for CREATE; a receipt does not grant draft reading permission. */
@RestController
@ConditionalOnProperty(name="product.consultation.prompt.create-enabled",havingValue="true")
@RequestMapping("/v2/consultation/prompt-workspaces/{workspaceId}")
final class ProductPromptCreateController {
    static final int MAX_BODY=1024*1024;
    private final ProductPromptCreateApplication application;
    ProductPromptCreateController(ProductPromptCreateApplication application){this.application=application;}

    @PostMapping(value="/prompts",produces="application/json")
    ResponseEntity<ReceiptView> create(@PathVariable String workspaceId,@AuthenticationPrincipal Jwt principal,
                                       HttpServletRequest request)throws IOException{
        String token=token(principal),key=coordinates(workspaceId,request);
        json(request);
        byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
        if(bytes.length>MAX_BODY)throw rejected(HttpStatus.PAYLOAD_TOO_LARGE);
        ProductPromptCreateCommand command;
        try{command=ProductPromptCreateCommand.decode(bytes);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        return ok(application.request(token,workspaceId,key,command));
    }
    @GetMapping(value="/requests/CREATE",produces="application/json")
    ResponseEntity<ReceiptView> lookup(@PathVariable String workspaceId,@AuthenticationPrincipal Jwt principal,
                                       HttpServletRequest request)throws IOException{
        String token=token(principal),key=coordinates(workspaceId,request);
        if(request.getInputStream().read()!=-1)throw rejected(HttpStatus.BAD_REQUEST);
        return ok(application.lookup(token,workspaceId,key));
    }
    static String coordinates(String workspaceId,HttpServletRequest request){
        try{ProductPromptRecords.uuid(workspaceId);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        // Do not ask the servlet container to parse a form body before enforcing the byte limit.
        if(request.getQueryString()!=null)throw rejected(HttpStatus.BAD_REQUEST);
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1)throw rejected(HttpStatus.BAD_REQUEST);
        String key=keys.get(0);
        if(key.isEmpty()||key.length()>64||key.chars().anyMatch(c->c<33||c>126))throw rejected(HttpStatus.BAD_REQUEST);
        return key;
    }
    static void json(HttpServletRequest request){
        var headers=Collections.list(request.getHeaders("Content-Type"));
        if(headers.size()!=1)throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        try{
            MediaType type=MediaType.parseMediaType(headers.get(0));
            if(!"application".equalsIgnoreCase(type.getType())||!"json".equalsIgnoreCase(type.getSubtype())
                    ||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }catch(IllegalArgumentException malformed){throw rejected(HttpStatus.UNSUPPORTED_MEDIA_TYPE);}
    }
    static String token(Jwt principal){
        if(principal==null)throw rejected(HttpStatus.UNAUTHORIZED);return principal.getTokenValue();
    }
    static ResponseStatusException rejected(HttpStatus status){return new ResponseStatusException(status);}
    private static ResponseEntity<ReceiptView> ok(ProductPromptRecords.Receipt receipt){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReceiptView.from(receipt));
    }
    record ReceiptView(String receiptId,String workspaceId,String promptId,String revisionId,long generation,
                       String contentSha,String resultSha,Instant submittedAt,Instant auditUntil){
        static ReceiptView from(ProductPromptRecords.Receipt value){
            return new ReceiptView(value.id(),value.workspaceId(),value.promptId(),value.revisionId(),value.generation(),
                    value.contentSha(),value.resultSha(),value.submittedAt(),value.auditUntil());
        }
    }
    @ExceptionHandler(ProductPromptAccessDenied.class)
    ResponseEntity<Map<String,String>> denied(){return error(HttpStatus.FORBIDDEN,"PROMPT_NOT_AVAILABLE");}
    @ExceptionHandler(ProductPromptCreateConflict.class)
    ResponseEntity<Map<String,String>> conflict(){return error(HttpStatus.CONFLICT,"PROMPT_CREATE_CONFLICT");}
    @ExceptionHandler(JwtException.class)
    ResponseEntity<Map<String,String>> identity(){return error(HttpStatus.UNAUTHORIZED,"PROMPT_SESSION_UNAVAILABLE");}
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class,DataAccessException.class,IOException.class})
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
