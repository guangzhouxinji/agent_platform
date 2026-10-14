package cn.xinji.agentplatform.security.product;

import java.util.List;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="product.consultation.prompt.enabled",havingValue="true")
@RequestMapping("/v2/consultation/prompts/{promptId}/revisions")
final class ProductPromptReadController {
    private final ProductPromptReadApplication application;
    ProductPromptReadController(ProductPromptReadApplication application){this.application=application;}
    @GetMapping(value="/{revisionId}",produces="application/json")
    ResponseEntity<ProductPromptReadView.Revision> revision(@PathVariable String promptId,@PathVariable String revisionId,
            @AuthenticationPrincipal Jwt principal){
        String token=token(principal);id(promptId);id(revisionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(application.revision(token,promptId,revisionId));
    }
    @GetMapping(produces="application/json")
    ResponseEntity<ProductPromptReadView.History> history(@PathVariable String promptId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt principal){
        String token=token(principal);id(promptId);
        if(page<1||!List.of(10,20,50).contains(size))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(application.history(token,promptId,page,size));
    }
    private static String token(Jwt principal){
        if(principal==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return principal.getTokenValue();
    }
    private static void id(String value){
        try{ProductPromptRecords.uuid(value);}catch(IllegalArgumentException failure){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    }
    @ExceptionHandler(ProductPromptAccessDenied.class)
    ResponseEntity<Map<String,String>> denied(){return error(HttpStatus.FORBIDDEN,"PROMPT_NOT_AVAILABLE");}
    @ExceptionHandler(JwtException.class)
    ResponseEntity<Map<String,String>> identity(){return error(HttpStatus.UNAUTHORIZED,"PROMPT_SESSION_UNAVAILABLE");}
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class,DataAccessException.class})
    ResponseEntity<Map<String,String>> unavailable(){return error(HttpStatus.SERVICE_UNAVAILABLE,"PROMPT_UNAVAILABLE");}
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String,String>> invalid(){return error(HttpStatus.BAD_REQUEST,"INVALID_PROMPT_REQUEST");}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(ResponseStatusException failure){
        return failure.getStatusCode().value()==401?identity():invalid();
    }
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
    }
}
