package cn.xinji.agentplatform.enforcement.documentdraft;

import cn.xinji.agentplatform.enforcement.agent.EnforcementAgent;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.procedure.EnforcementProcedureController;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AuthorizedAgentExecution;
import cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent;
import cn.xinji.agentplatform.runtime.AgentRunPersistence;
import cn.xinji.agentplatform.runtime.persistence.JdbcAgentCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Independent enforcement tasks: explicit case/material/rule/procedure selection, actual services and current Product grants. */
@RestController
@RequestMapping("/v2/enforcement/document-drafts/{draftId}/agent-runs")
@ConditionalOnProperty(name={"agent-platform.runtime.enabled","enforcement.v2.enabled"},havingValue="true")
final class ProductEnforcementAgentController {
    private final AuthorizedAgentExecution execution;
    record View(Checkpoint checkpoint,String goalText,List<AgentEvent> events) { }
    private final EnforcementDocumentDraftController documents;private final EnforcementProcedureController procedures;
    private final MaterialRepository materials;private final AgentRunPersistence persistence;private final ObjectMapper json;
    ProductEnforcementAgentController(EnforcementDocumentDraftController documents,EnforcementProcedureController procedures,
            MaterialRepository materials,AgentRunPersistence persistence,ObjectMapper json,AuthorizedAgentExecution execution) {
        this.execution=execution;
        this.documents=documents;this.procedures=procedures;this.materials=materials;this.persistence=persistence;this.json=json;
    }
    @PostMapping View start(@PathVariable String draftId,@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,
            @RequestBody ProductEnforcementAgentTools.Input input) throws Exception {
        if(input==null)
            throw new IllegalArgumentException("EXACT_CASE_PROCEDURE_BINDING_REQUIRED");
        String actor=EnforcementDocumentDraftController.actor(jwt);var services=documents.context(jwt);
        var prepared=services.drafts().prepare(actor,draftId,input.document());
        Instant until=input.document().evidence().stream().map(ref->materials.material(ref.id()).orElseThrow().expiresAt()).min(Instant::compareTo).orElseThrow();
        var binding=new JdbcAgentCheckpointStore.Binding(EnforcementAgent.DEFINITION.agentId(),draftId,input.document().caseInput().id(),prepared.inputManifestHash(),input.document().organization(),until);
        String run=AgentRunPersistence.runId(binding.agentId(),draftId,jwt.getIssuer()+"#"+actor,key);
        var context=context(binding,jwt,true);var existing=context.store.load(run);
        if(existing==null)context.agent.start(run,new Task(draftId,EnforcementAgent.TASK_TYPE,binding.scope(),
                new Goal(binding.goalId(),binding.goalVersion(),json.writeValueAsString(input),EnforcementAgent.COMPLETION_CONTRACT)),new Budget(10,1,Duration.ofMinutes(3)));
        else if(!existing.task().goal().description().equals(json.writeValueAsString(input)))throw new IllegalArgumentException("IDEMPOTENCY_INPUT_CONFLICT");
        return view(run,context);
    }
    @GetMapping("/{runId}") View read(@PathVariable String draftId,@PathVariable String runId,@RequestParam String organization,@AuthenticationPrincipal Jwt jwt) {
        authorize(draftId,organization,jwt,"READ");return view(runId,context(persistence.binding(EnforcementAgent.DEFINITION.agentId(),draftId,runId,organization),jwt,false));
    }
    @PostMapping("/{runId}/advance") View advance(@PathVariable String draftId,@PathVariable String runId,@RequestParam String organization,@AuthenticationPrincipal Jwt jwt) {
        authorize(draftId,organization,jwt,"PREPARE");var context=context(persistence.binding(EnforcementAgent.DEFINITION.agentId(),draftId,runId,organization),jwt,true);
        context.agent.advance(runId);return view(runId,context);
    }
    @PostMapping("/{runId}/execute") View execute(@PathVariable String draftId,@PathVariable String runId,@RequestParam String organization,@AuthenticationPrincipal Jwt jwt) {
        return execution.execute(jwt, fresh->read(draftId,runId,organization,fresh).checkpoint(),
                fresh->advance(draftId,runId,organization,fresh), fresh->read(draftId,runId,organization,fresh));
    }
    private record Context(JdbcAgentCheckpointStore store,EnforcementAgent agent) { }
    private void authorize(String id,String org,Jwt jwt,String action) {
        documents.context(jwt).access().require(EnforcementDocumentDraftController.actor(jwt),org,id,1,action);
    }
    private Context context(JdbcAgentCheckpointStore.Binding binding,Jwt jwt,boolean write) {
        String actor=EnforcementDocumentDraftController.actor(jwt);var services=documents.context(jwt);
        authorize(binding.taskId(),binding.scope(),jwt,write?"PREPARE":"READ");
        var tools=new ProductEnforcementAgentTools(services,procedures.service(jwt),actor,json);
        var store=persistence.store(binding,(b,c)->{
            authorize(b.taskId(),b.scope(),jwt,write?"PREPARE":"READ");
            if(c!=null) {
                var selected=tools.input(c);
                if(write)tools.current(c);else services.source().assertReadable(actor,selected.document());
                for(var ref:selected.document().evidence())if(materials.material(ref.id()).orElseThrow().expiresAt().isBefore(b.expiresAt()))throw new SecurityException("RUN_RETENTION_CHANGED");
            }
        });
        return new Context(store,new EnforcementAgent(store,tools,(c,s)->{tools.current(c);return true;},Clock.systemUTC()));
    }
    private View view(String run,Context context) {
        var checkpoint=context.store.load(run);if(checkpoint==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return new View(checkpoint,"形成证据、规则和程序检查支持的文书草稿，交由授权人员复核",context.store.events(run));
    }
    @ExceptionHandler(org.springframework.security.oauth2.jwt.JwtException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED) void expiredIdentity() { }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) void invalid() { }
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
