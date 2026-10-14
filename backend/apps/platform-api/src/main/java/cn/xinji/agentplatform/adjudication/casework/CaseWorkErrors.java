package cn.xinji.agentplatform.adjudication.casework;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={CaseWorkController.class,CaseSourceController.class,CaseGrantController.class})
public final class CaseWorkErrors {
    private static final java.util.Set<String> CONFLICTS=java.util.Set.of("WORK_VERSION_CONFLICT","CLAIM_REVISION_CONFLICT","MATERIAL_ID_ALREADY_EXISTS",
            "FINDING_ID_ALREADY_EXISTS","CITATION_ID_ALREADY_EXISTS","REPORT_INPUT_OR_HEAD_STALE","REPORT_HASH_CONFLICT","REPORT_INCOMPLETE","REPORT_NOT_DRAFT",
            "REPORT_NOT_SUBMITTED","INDEPENDENT_REVIEW_REQUIRED","CASE_ALREADY_EXISTS","IDEMPOTENCY_KEY_CONFLICT","CONTEXT_IN_USE","CASE_SCOPE_IMMUTABLE",
            "REPORT_POLICY_CONFLICT","SOURCE_VERSION_CONTENT_CONFLICT","SOURCE_USE_ALREADY_REGISTERED","SOURCE_USE_VERSION_CONFLICT","NEW_CASE_SOURCE_VERSION_REQUIRED","SUPERSEDED_MATERIAL_NOT_CURRENT");
    private static final java.util.Set<String> NOT_FOUND=java.util.Set.of("CASE_NOT_FOUND","CASE_VERSION_NOT_FOUND","REPORT_NOT_FOUND");
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String,String>> invalid(IllegalArgumentException failure){
        String code=failure.getMessage();if(code==null || !code.matches("[A-Z0-9_]{1,100}"))code="INVALID_COMMAND_BODY";
        HttpStatus status=NOT_FOUND.contains(code)?HttpStatus.NOT_FOUND:CONFLICTS.contains(code)?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("code",code));
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String,String> forbidden(SecurityException failure){
        String reason=switch(String.valueOf(failure.getMessage())){case "SOURCE_USE_NOT_CURRENT" -> "SOURCE_REVOKED";case "EXACT_SOURCE_USE_REQUIRED" -> "SOURCE_USE_UNAVAILABLE";default -> "";};
        return reason.isEmpty()?Map.of("code","CASE_ACCESS_DENIED"):Map.of("code","CASE_ACCESS_DENIED","reason",reason);
    }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable(){return Map.of("code","CASE_AUTHORITY_OR_PERSISTENCE_UNAVAILABLE");}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> malformed(){return Map.of("code","INVALID_COMMAND_BODY");}
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> rejected(ResponseStatusException failure){
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode()).body(Map.of("code","MAINTENANCE_IDENTITY_REQUIRED"));
    }
}
