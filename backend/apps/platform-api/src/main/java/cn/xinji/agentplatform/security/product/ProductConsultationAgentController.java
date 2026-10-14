package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.agent.ConsultationAgent;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AuthorizedAgentExecution;
import cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent;
import cn.xinji.agentplatform.runtime.persistence.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** Independent consultation lifecycle, formally bound to the existing confirmed Product Task. */
@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/agent-runs")
@ConditionalOnProperty(name={"agent-platform.runtime.enabled","product.identity.enabled","product.source.engine.enabled","product.consultation.model.enabled","product.consultation.workspace.enabled"},havingValue="true")
final class ProductConsultationAgentController {
    private final AuthorizedAgentExecution execution;
    record View(Checkpoint checkpoint,String goalText,List<AgentEvent> events) { }
    private final ProductRetrievalWorkspace workspace;private final ProductRetrievalWorkflow workflow;
    private final ProductRetrievalDialogue dialogue;private final JdbcTemplate db;private final TransactionTemplate tx;
    private final AgentCheckpointCodec codec;private final ObjectMapper json;private final Clock clock=Clock.systemUTC();
    ProductConsultationAgentController(ProductRetrievalWorkspace workspace,ProductRetrievalWorkflow workflow,
            ProductRetrievalDialogue dialogue,JdbcTemplate db,PlatformTransactionManager manager,
            AgentCheckpointCodec codec,ObjectMapper json,AuthorizedAgentExecution execution) {
        this.execution=execution;
        this.workspace=workspace;this.workflow=workflow;this.dialogue=dialogue;this.db=db;this.tx=new TransactionTemplate(manager);this.codec=codec;this.json=json;
    }
    @PostMapping View start(@PathVariable String taskId,@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode command) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,120}") || command==null || !command.isObject()
                || command.size()!=1 || !command.path("expectedTaskVersion").isIntegralNumber()
                || !command.path("expectedTaskVersion").canConvertToLong()) throw new IllegalArgumentException("EXACT_TASK_VERSION_REQUIRED");
        var nativeView=workspace.read(taskId,jwt);
        workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.WRITE);
        if(nativeView.task().version()!=command.path("expectedTaskVersion").asLong())throw ProductRetrievalWorkspace.conflict();
        String runId=cn.xinji.agentplatform.runtime.AgentRunPersistence.runId(ConsultationAgent.DEFINITION.agentId(),taskId,jwt.getIssuer()+"#"+jwt.getSubject(),key);
        var context=context(taskId,runId,jwt,true);var prior=context.store.load(runId);
        if(prior==null) {
            var goal=context.nativeView.goal();
            var task=new Task(taskId,ConsultationAgent.TASK_TYPE,context.nativeView.task().dataScope(),
                    new Goal(goal.goalId(),ProductConsultationAgentTools.version(goal),"confirmed-input:"+goal.inputId(),ConsultationAgent.COMPLETION_CONTRACT));
            context.agent.start(runId,task,new Budget(12,2,Duration.ofMinutes(3)));
        }
        return view(runId,context);
    }
    @GetMapping("/{runId}") View read(@PathVariable String taskId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        return view(runId,context(taskId,runId,jwt,false));
    }
    @PostMapping("/{runId}/advance") View advance(@PathVariable String taskId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        var context=context(taskId,runId,jwt,true);context.agent.advance(runId);return view(runId,context);
    }
    @PostMapping("/{runId}/execute") View execute(@PathVariable String taskId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        return execution.execute(jwt, fresh->read(taskId,runId,fresh).checkpoint(),
                fresh->advance(taskId,runId,fresh), fresh->read(taskId,runId,fresh));
    }
    private record Context(ProductRetrievalWorkspace.View nativeView,JdbcAgentCheckpointStore store,ConsultationAgent agent) { }
    private Context context(String taskId,String runId,Jwt jwt,boolean write) {
        if(!UUID.fromString(runId).toString().equals(runId))throw new IllegalArgumentException("RUN_ID_INVALID");
        var view=workspace.read(taskId,jwt);var goal=view.goal();
        if(goal==null || !("QUERY".equals(goal.purpose()) || "SEARCH".equals(goal.purpose())))throw ProductRetrievalWorkspace.conflict();
        if(write)workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.WRITE);
        Instant expiry=ProductRetrievalWorkspace.earlier(view.task().expiresAt(),goal.expiresAt());
        var selected=ProductRetrievalWorkflow.bind(goal,view.sources(),clock.instant());
        for(var source:selected)expiry=ProductRetrievalWorkspace.earlier(expiry,source.expiresAt());
        var priorExpiry=db.query("SELECT expires_at FROM agent_run_head WHERE run_id=? AND agent_id=? AND task_id=?",
                (rs,n)->rs.getTimestamp(1).toInstant(),runId,ConsultationAgent.DEFINITION.agentId(),taskId);
        if(!priorExpiry.isEmpty()) {
            if(priorExpiry.getFirst().isAfter(expiry))throw new SecurityException("RUN_PARENT_RETENTION_CHANGED");
            expiry=priorExpiry.getFirst();
        }
        var binding=new JdbcAgentCheckpointStore.Binding(ConsultationAgent.DEFINITION.agentId(),taskId,goal.goalId(),
                ProductConsultationAgentTools.version(goal),view.task().dataScope(),expiry);
        var tools=new ProductConsultationAgentTools(workspace,workflow,dialogue,jwt,json);
        var store=new JdbcAgentCheckpointStore(db,tx,codec,binding,(b,c)->{
            var live=workspace.read(taskId,jwt);
            if(write)workspace.permitted(taskId,jwt,ProductAccessPolicy.Action.WRITE);
            if(live.goal()==null || !b.goalId().equals(live.goal().goalId()) || !b.goalVersion().equals(ProductConsultationAgentTools.version(live.goal()))
                    || !b.scope().equals(live.task().dataScope()))throw ProductRetrievalWorkspace.conflict();
            ProductRetrievalWorkflow.bind(live.goal(),live.sources(),clock.instant());
            tools.requireDependencies(c);
        },clock);
        var agent=new ConsultationAgent(store,tools,(c,step)->{tools.current(c);return true;},clock);
        return new Context(view,store,agent);
    }
    private View view(String runId,Context c) {
        var checkpoint=c.store.load(runId);if(checkpoint==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return new View(checkpoint,c.nativeView.goal().messageText(),c.store.events(runId));
    }
    @ExceptionHandler(org.springframework.security.oauth2.jwt.JwtException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED) void expiredIdentity() { }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) void invalid() { }
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
