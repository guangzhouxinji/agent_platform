package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptCreateController.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

/** Complete TEST bytes only; no original-key request, source registration or model invocation. */
@RestController
@ConditionalOnProperty(name="product.consultation.prompt.preview-enabled",havingValue="true")
@RequestMapping("/v2/consultation/prompt-workspaces/{workspaceId}/prompts/{promptId}/revisions/{revisionId}")
final class ProductPromptPreviewController {
    private final ProductPromptPreviewApplication application;
    ProductPromptPreviewController(ProductPromptPreviewApplication application){this.application=application;}
    @PostMapping(value="/preview",produces="application/json")
    ResponseEntity<byte[]> preview(@PathVariable String workspaceId,@PathVariable String promptId,@PathVariable String revisionId,
            @AuthenticationPrincipal Jwt principal,HttpServletRequest request)throws IOException{
        String token=token(principal);uuid(workspaceId);uuid(promptId);uuid(revisionId);
        if(request.getQueryString()!=null||request.getHeader("Idempotency-Key")!=null)throw rejected(HttpStatus.BAD_REQUEST);
        json(request);byte[] bytes=request.getInputStream().readNBytes(MAX_BODY+1);
        if(bytes.length>MAX_BODY)throw rejected(HttpStatus.PAYLOAD_TOO_LARGE);
        ProductPromptPreviewCommand command;
        try{command=ProductPromptPreviewCommand.decode(bytes);}catch(ProductPromptPreviewLimit oversized){throw oversized;}
        catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
        var result=application.preview(token,workspaceId,promptId,revisionId,command);
        byte[] body=result.body();application.requireCurrent(result);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(body);
    }
    private static void uuid(String value){
        try{ProductPromptRecords.uuid(value);}catch(IllegalArgumentException malformed){throw rejected(HttpStatus.BAD_REQUEST);}
    }
    @ExceptionHandler(ProductPromptPreviewLimit.class)
    ResponseEntity<Map<String,String>> limit(){return error(HttpStatus.PAYLOAD_TOO_LARGE,"PROMPT_REQUEST_TOO_LARGE");}
    @ExceptionHandler(ProductPromptAccessDenied.class)
    ResponseEntity<Map<String,String>> denied(){return error(HttpStatus.FORBIDDEN,"PROMPT_NOT_AVAILABLE");}
    @ExceptionHandler(JwtException.class)
    ResponseEntity<Map<String,String>> identity(){return error(HttpStatus.UNAUTHORIZED,"PROMPT_SESSION_UNAVAILABLE");}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,DataAccessException.class,IOException.class})
    ResponseEntity<Map<String,String>> unavailable(){return error(HttpStatus.SERVICE_UNAVAILABLE,"PROMPT_UNAVAILABLE");}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> invalid(ResponseStatusException failure){
        return switch(failure.getStatusCode().value()){
            case 401->identity();case 413->limit();case 415->error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PROMPT_MEDIA_TYPE_UNSUPPORTED");
            default->error(HttpStatus.BAD_REQUEST,"INVALID_PROMPT_REQUEST");
        };
    }
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
    }
}
