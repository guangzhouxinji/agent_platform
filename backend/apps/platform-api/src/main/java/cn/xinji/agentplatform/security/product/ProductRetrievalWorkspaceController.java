package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled",
        "product.consultation.workspace.enabled"},havingValue="true")
final class ProductRetrievalWorkspaceController {
    record Execute(long expectedTaskVersion) { }
    private final ProductRetrievalWorkspace workspace;
    private final ProductRetrievalWorkflow workflow;
    ProductRetrievalWorkspaceController(ProductRetrievalWorkspace workspace,ObjectProvider<ProductRetrievalWorkflow> workflow) {
        this.workspace=workspace; this.workflow=workflow.getIfAvailable();
    }
    @GetMapping("/retrieval-workspace")
    ProductRetrievalWorkspace.View read(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt) {
        return workspace.read(taskId,jwt);
    }
    @PostMapping("/retrievals/execute")
    ProductPatentSearchExecution.Page execute(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Execute command) {
        if (command==null || command.expectedTaskVersion()<1 || key==null
                || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact confirmed task version required");
        if(workflow==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RETRIEVAL_EXECUTION_NOT_CONFIGURED");
        return workflow.execute(taskId,command.expectedTaskVersion(),jwt);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
