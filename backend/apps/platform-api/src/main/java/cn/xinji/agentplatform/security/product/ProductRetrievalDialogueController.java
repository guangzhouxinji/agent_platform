package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/dialogue")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled",
        "product.consultation.model.enabled"}, havingValue = "true")
public final class ProductRetrievalDialogueController {
    private final ProductRetrievalDialogue dialogue;
    ProductRetrievalDialogueController(ProductRetrievalDialogue dialogue) { this.dialogue=dialogue; }

    @PostMapping("/proposals")
    ProductRetrievalDialogue.Turn propose(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId,@RequestBody ProductRetrievalDialogue.Input input) {
        return dialogue.invoke(taskId,jwt,requestId,"PROPOSAL",input);
    }
    @PostMapping("/explanations")
    ProductRetrievalDialogue.Turn explain(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String requestId,@RequestBody ProductRetrievalDialogue.Input input) {
        return dialogue.invoke(taskId,jwt,requestId,"EXPLANATION",input);
    }
    @GetMapping("/{turnId}")
    ProductRetrievalDialogue.Turn read(@PathVariable String taskId,@PathVariable String turnId,
            @AuthenticationPrincipal Jwt jwt) {return dialogue.read(taskId,turnId,jwt);}

    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
