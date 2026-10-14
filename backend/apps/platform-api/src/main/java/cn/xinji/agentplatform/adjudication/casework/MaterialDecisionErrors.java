package cn.xinji.agentplatform.adjudication.casework;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=MaterialDecisionController.class)
public final class MaterialDecisionErrors {
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){String c=safe(e);var s=c.endsWith("NOT_FOUND")?HttpStatus.NOT_FOUND:c.contains("CONFLICT")||c.contains("STALE")||c.contains("ALREADY_DECIDED")||c.contains("REVISION_NOT_ALLOWED")||c.contains("DRIFTED")?HttpStatus.CONFLICT:HttpStatus.BAD_REQUEST;return ResponseEntity.status(s).body(Map.of("code",c));}
    @ExceptionHandler(SecurityException.class) public ResponseEntity<Map<String,String>> denied(SecurityException e){return ResponseEntity.status(403).body(Map.of("code","CASE_ACCESS_DENIED","reason",safe(e)));}
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) public ResponseEntity<Map<String,String>> unavailable(RuntimeException e){return ResponseEntity.status(503).body(Map.of("code",e instanceof IllegalStateException?safe(e):"MATERIAL_DECISION_PERSISTENCE_UNAVAILABLE"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})public ResponseEntity<Map<String,String>> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMMAND_BODY"));}
    private static String safe(Exception e){var m=e.getMessage();return m!=null&&m.matches("[A-Z0-9_]{1,100}")?m:"MATERIAL_DECISION_OPERATION_FAILED";}
}
