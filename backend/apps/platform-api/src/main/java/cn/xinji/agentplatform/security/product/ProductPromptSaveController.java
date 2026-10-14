package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptCreateController.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
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

/** SAVE accepts the displayed workspace explicitly and returns only an exact finite receipt. */
@RestController
@ConditionalOnProperty(name="product.consultation.prompt.save-enabled",havingValue="true")
@RequestMapping("/v2/consultation/prompt-workspaces/{workspaceId}")
final class ProductPromptSaveController {
    private final ProductPromptSaveApplication application;
    ProductPromptSaveController(ProductPromptSaveApplication application){this.application=application;}
    @PostMapping(value="/prompts/{promptId}/revisions",produces="application/json")
    ResponseEntity<ReceiptView> save(@PathVariable String workspaceId,@PathVariable String promptId,@AuthenticationPrincipal Jwt principal,
                                     HttpServletRequest request)throws IOException{
        String token=token(principal),key=coordinates(workspaceId,request);
        try{ProductPromptRecords.uuid(promptId);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        json(request);byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
        if(bytes.length>MAX_BODY)throw rejected(HttpStatus.PAYLOAD_TOO_LARGE);
        ProductPromptSaveCommand command;
        try{command=ProductPromptSaveCommand.decode(bytes);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        return ok(application.request(token,workspaceId,promptId,key,command));
    }
    @GetMapping(value="/requests/SAVE",produces="application/json")
    ResponseEntity<ReceiptView> lookup(@PathVariable String workspaceId,@AuthenticationPrincipal Jwt principal,HttpServletRequest request)throws IOException{
        String token=token(principal),key=coordinates(workspaceId,request);
        if(request.getInputStream().read()!=-1)throw rejected(HttpStatus.BAD_REQUEST);
        return ok(application.lookup(token,workspaceId,key));
    }
    record ReceiptView(String receiptId,String workspaceId,String promptId,String revisionId,String generation,
                       String contentSha,String resultSha,Instant submittedAt,Instant auditUntil){
        static ReceiptView from(ProductPromptRecords.Receipt value){
            if(value.operation()!=ProductPromptRecords.Operation.SAVE||value.generation()<2)throw ProductPromptRecords.unavailable();
            return new ReceiptView(value.id(),value.workspaceId(),value.promptId(),value.revisionId(),Long.toString(value.generation()),
                    value.contentSha(),value.resultSha(),value.submittedAt(),value.auditUntil());
        }
    }
    private static ResponseEntity<ReceiptView> ok(ProductPromptRecords.Receipt value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReceiptView.from(value));}
    @ExceptionHandler(ProductPromptSaveBaseConflict.class)
    ResponseEntity<Map<String,String>> baseConflict(){return error(HttpStatus.CONFLICT,"PROMPT_SAVE_BASE_CONFLICT");}
    @ExceptionHandler(ProductPromptCreateConflict.class)
    ResponseEntity<Map<String,String>> keyConflict(){return error(HttpStatus.CONFLICT,"PROMPT_SAVE_KEY_CONFLICT");}
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
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));}
}
