package cn.xinji.agentplatform.adjudication.casework;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestControllerAdvice(assignableTypes=MaterialOrganizationController.class)
public final class MaterialOrganizationErrors {
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){String c=safe(e);HttpStatus s=c.equals("MODEL_BUDGET_EXHAUSTED")?HttpStatus.TOO_MANY_REQUESTS:c.contains("CONFLICT")||c.equals("MODEL_ATTEMPT_NOT_RETRYABLE")?HttpStatus.CONFLICT:(c.equals("MODEL_RUN_NOT_FOUND")||c.equals("CASE_NOT_FOUND")||c.equals("CASE_VERSION_NOT_FOUND"))?HttpStatus.NOT_FOUND:c.startsWith("MODEL_QUOTE")||c.startsWith("MODEL_LOCATOR")||c.startsWith("MODEL_JSON")||c.startsWith("MODEL_UNKNOWN")||c.startsWith("MODEL_CLASSIFICATION")||c.startsWith("MODEL_SOURCE_OR_SIDE")?HttpStatus.BAD_GATEWAY:HttpStatus.BAD_REQUEST;return ResponseEntity.status(s).body(Map.of("code",c));}
    @ExceptionHandler(SecurityException.class) public ResponseEntity<Map<String,String>> denied(SecurityException e){return ResponseEntity.status(403).body(Map.of("code","CASE_ACCESS_DENIED","reason",safe(e)));}
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class})public ResponseEntity<Map<String,String>> unavailable(RuntimeException e){return ResponseEntity.status(503).body(Map.of("code",e instanceof IllegalStateException?safe(e):"MODEL_PERSISTENCE_UNAVAILABLE"));}
    private static String safe(Exception e){var m=e.getMessage();return m!=null&&m.matches("[A-Z0-9_]{1,100}")?m:"MODEL_OPERATION_FAILED";}
}
