package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Exact mTLS receipt ingress. Never delegates to claim, execute or the Model Gateway. */
@RestController
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled","product.consultation.comparison.start.enabled"},havingValue="true")
final class ProductComparisonHandoffController {
    record Request(String inputSha,String delegationId,String requestSha) { }
    private final ProductComparisonStartCommands commands;
    private final ProductComparisonWorkerMaterials materials;
    private final ProductComparisonHandoffStore store;
    private final ProductComparisonJson json;
    ProductComparisonHandoffController(ProductComparisonStartCommands commands,ProductComparisonWorkerMaterials materials,ProductComparisonHandoffStore store,ProductComparisonJson json){this.commands=commands;this.materials=materials;this.store=store;this.json=json;}
    @PostMapping("/v2/consultation/tasks/{task}/patent-comparisons/{comparison}/candidates/{candidate}/start-commands/{command}/handoff-receipts")
    ProductComparisonHandoffStore.Proof receive(@PathVariable String task,@PathVariable String comparison,@PathVariable String candidate,@PathVariable String command,
            @RequestHeader("Idempotency-Key") String key,@RequestBody String body,HttpServletRequest request) {
        var r=json.request(body,Request.class);
        // Authorize exact worker and source before accessing persisted command metadata.
        var original=materials.consume(request,task,comparison,candidate,r.inputSha(),r.delegationId());
        var c=commands.metadata(command);
        if(!c.scope().task().equals(task)||!c.scope().comparison().equals(comparison)||!c.scope().candidate().equals(candidate)
                ||!c.scope().inputSha().equals(r.inputSha())||!java.util.Objects.equals(c.delegationId(),r.delegationId())
                ||!c.requestSha().equals(r.requestSha())||!("start:"+command).equals(key))throw new IllegalStateException("COMPARISON_HANDOFF_BINDING_CONFLICT");
        return store.accept(c,()->{
            if(!ProductComparisonWorkerMaterials.sameCurrent(original,materials.consume(request,task,comparison,candidate,r.inputSha(),r.delegationId())))throw new IllegalStateException("COMPARISON_HANDOFF_MATERIALS_CHANGED");
        });
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMPARISON_HANDOFF"));}
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","COMPARISON_HANDOFF_UNAVAILABLE"));}
}
