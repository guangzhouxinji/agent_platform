package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Maintenance registration transport; the application owns the exact mTLS and approval checks. */
@RestController
@ConditionalOnProperty(name="product.consultation.prompt.register-enabled",havingValue="true")
@RequestMapping("/v2/identity/prompt-workspaces/{workspaceId}")
final class ProductPromptRegisterController {
    private static final Logger LOG=LoggerFactory.getLogger(ProductPromptRegisterController.class);
    private final ProductPromptRegisterApplication application;
    ProductPromptRegisterController(ProductPromptRegisterApplication application){this.application=application;}

    @PostMapping(value="/register",produces="application/json")
    ResponseEntity<ReceiptView> register(@PathVariable String workspaceId,HttpServletRequest request)throws IOException{
        requireMethod(request,"POST");String key=input(workspaceId,request);
        return ok(application.register(request,workspaceId,key));
    }
    @GetMapping(value="/requests/REGISTER",produces="application/json")
    ResponseEntity<ReceiptView> lookup(@PathVariable String workspaceId,HttpServletRequest request)throws IOException{
        requireMethod(request,"GET");String key=input(workspaceId,request);
        return ok(application.lookup(request,workspaceId,key));
    }
    private static String input(String workspaceId,HttpServletRequest request)throws IOException{
        String key=ProductPromptCreateController.coordinates(workspaceId,request);
        if(request.getInputStream().read()!=-1)throw ProductPromptCreateController.rejected(HttpStatus.BAD_REQUEST);
        return key;
    }
    private static void requireMethod(HttpServletRequest request,String expected){
        if(!expected.equals(request.getMethod()))throw ProductPromptCreateController.rejected(HttpStatus.METHOD_NOT_ALLOWED);
    }
    private static ResponseEntity<ReceiptView> ok(ProductPromptRegisterRecords.Receipt value){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReceiptView.from(value));
    }
    record ReceiptView(String requestId,String receiptId,String workspaceId,String state,String resultSha,
                       Instant preparedAt,Instant receiptUntil){
        static ReceiptView from(ProductPromptRegisterRecords.Receipt value){
            return new ReceiptView(value.requestId(),value.receiptId(),value.key().workspaceId(),"READY",value.resultSha(),
                    value.preparedAt(),value.receiptUntil());
        }
    }
    @ExceptionHandler(ProductPromptAccessDenied.class)
    ResponseEntity<Map<String,String>> denied(){return error(HttpStatus.FORBIDDEN,"PROMPT_NOT_AVAILABLE");}
    @ExceptionHandler(ProductPromptRegisterConflict.class)
    ResponseEntity<Map<String,String>> conflict(){return error(HttpStatus.CONFLICT,"PROMPT_REGISTER_CONFLICT");}
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class,DataAccessException.class,IOException.class})
    ResponseEntity<Map<String,String>> unavailable(Exception failure){
        LOG.warn("Prompt workspace registration unavailable",failure);
        return error(HttpStatus.SERVICE_UNAVAILABLE,"PROMPT_UNAVAILABLE");
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> invalid(ResponseStatusException failure){
        return failure.getStatusCode().value()==405
                ?error(HttpStatus.METHOD_NOT_ALLOWED,"PROMPT_METHOD_NOT_ALLOWED")
                :error(HttpStatus.BAD_REQUEST,"INVALID_PROMPT_REQUEST");
    }
    private static ResponseEntity<Map<String,String>> error(HttpStatus status,String code){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code));
    }
}
