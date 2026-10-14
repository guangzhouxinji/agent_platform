package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.agent.AdjudicationAgent;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AuthorizedAgentExecution;
import cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent;
import cn.xinji.agentplatform.runtime.AgentRunPersistence;
import cn.xinji.agentplatform.runtime.persistence.JdbcAgentCheckpointStore;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Independent case-scoped Agent lifecycle. No grants, official decisions or review actions are written by this Agent. */
@RestController
@RequestMapping("/v2/adjudication/case-works/{caseId}/agent-runs")
@ConditionalOnProperty(name={"agent-platform.runtime.enabled","adjudication.v2.enabled"},havingValue="true")
final class ProductAdjudicationAgentController {
    private final AuthorizedAgentExecution execution;
    record View(Checkpoint checkpoint,String goalText,List<AgentEvent> events) { }
    private final CaseWorkRepository cases;private final CaseWorkCommands commands;private final CaseSourceAuthority sources;
    private final JdbcTemplate db;private final ProductGrantWatermark grants;private final AgentRunPersistence persistence;
    private final ObjectMapper json;private final ObjectProvider<MaterialOrganizationService> models;private final String issuer;
    ProductAdjudicationAgentController(CaseWorkRepository cases,CaseWorkCommands commands,CaseSourceAuthority sources,
            JdbcTemplate db,ProductGrantWatermark grants,AgentRunPersistence persistence,ObjectMapper json,
            ObjectProvider<MaterialOrganizationService> models,@Value("${product.identity.issuer:}")String issuer,AuthorizedAgentExecution execution) {
        this.execution=execution;
        this.cases=cases;this.commands=commands;this.sources=sources;this.db=db;this.grants=grants;this.persistence=persistence;this.json=json;this.models=models;this.issuer=issuer;
    }
    private CaseWorkAccess access(Jwt jwt){return new CaseWorkAccess(db,grants,sources,jwt,issuer,Clock.systemUTC());}
    @PostMapping View start(@PathVariable String caseId,@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode command) throws Exception {
        CaseWorkCommands.validId(caseId);
        if(command==null || !command.isObject() || !command.path("expectedVersion").isIntegralNumber() || !command.path("expectedVersion").canConvertToLong()
                || !command.path("modelMaterialIds").isArray())throw new IllegalArgumentException("EXACT_CASE_VERSION_AND_SELECTION_REQUIRED");
        command.fieldNames().forEachRemaining(f->{if(!Set.of("expectedVersion","modelMaterialIds").contains(f))throw new IllegalArgumentException("UNKNOWN_COMMAND_FIELD");});
        List<String> selected=new ArrayList<>();command.path("modelMaterialIds").forEach(n->{if(!n.isTextual())throw new IllegalArgumentException("MATERIAL_ID_REQUIRED");selected.add(n.asText());});
        var input=new ProductAdjudicationAgentTools.Input(command.path("expectedVersion").asLong(),selected);
        var access=access(jwt);var state=cases.read(caseId,null,access);var scope=CaseWorkRepository.Scope.of(state.caseInput());access.require(scope,"WRITE");
        String run=AgentRunPersistence.runId(AdjudicationAgent.DEFINITION.agentId(),caseId,access.identity(),key);
        JdbcAgentCheckpointStore.Binding binding;
        try {binding=persistence.binding(AdjudicationAgent.DEFINITION.agentId(),caseId,run,state.caseInput().dataScope());}
        catch(org.springframework.web.server.ResponseStatusException missing) {
            if(missing.getStatusCode().value()!=404)throw missing;
            if(state.version()!=input.initialWorkVersion())throw new IllegalArgumentException("WORK_VERSION_CONFLICT");
            for(String id:selected)if(state.activeMaterials().stream().noneMatch(m->m.id().equals(id)&&m.readable()))throw new IllegalArgumentException("CURRENT_MATERIAL_SELECTION_REQUIRED");
            Instant until=sources.permittedUntil(scope,originals(state),"READ");
            binding=new JdbcAgentCheckpointStore.Binding(AdjudicationAgent.DEFINITION.agentId(),caseId,caseId,Long.toString(state.inputVersion()),state.caseInput().dataScope(),until);
        }
        var context=context(binding,jwt,true);var existing=context.store.load(run);
        if(existing==null)context.agent.start(run,new Task(caseId,AdjudicationAgent.TASK_TYPE,binding.scope(),
                new Goal(caseId,binding.goalVersion(),json.writeValueAsString(input),AdjudicationAgent.COMPLETION_CONTRACT)),new Budget(10,1,Duration.ofMinutes(3)));
        else if(!existing.task().goal().description().equals(json.writeValueAsString(input)))throw new IllegalArgumentException("IDEMPOTENCY_INPUT_CONFLICT");
        return view(run,context);
    }
    @GetMapping("/{runId}") View read(@PathVariable String caseId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        var state=cases.read(caseId,null,access(jwt));return view(runId,context(persistence.binding(AdjudicationAgent.DEFINITION.agentId(),caseId,runId,state.caseInput().dataScope()),jwt,false));
    }
    @PostMapping("/{runId}/advance") View advance(@PathVariable String caseId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        var access=access(jwt);var state=cases.read(caseId,null,access);access.require(CaseWorkRepository.Scope.of(state.caseInput()),"WRITE");
        var context=context(persistence.binding(AdjudicationAgent.DEFINITION.agentId(),caseId,runId,state.caseInput().dataScope()),jwt,true);
        context.agent.advance(runId);return view(runId,context);
    }
    @PostMapping("/{runId}/execute") View execute(@PathVariable String caseId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt) {
        return execution.execute(jwt, fresh->read(caseId,runId,fresh).checkpoint(),
                fresh->advance(caseId,runId,fresh), fresh->read(caseId,runId,fresh));
    }
    private record Context(JdbcAgentCheckpointStore store,AdjudicationAgent agent) { }
    private Context context(JdbcAgentCheckpointStore.Binding binding,Jwt jwt,boolean write) {
        var access=access(jwt);var tools=new ProductAdjudicationAgentTools(cases,commands,access,models.getIfAvailable(),json);
        var store=persistence.store(binding,(b,c)->{
            var state=cases.read(b.taskId(),null,access);var scope=CaseWorkRepository.Scope.of(state.caseInput());
            if(write)access.require(scope,"WRITE");
            if(!Long.toString(state.inputVersion()).equals(b.goalVersion()) || !state.caseInput().dataScope().equals(b.scope()))throw new IllegalArgumentException("AGENT_CASE_INPUT_STALE");
            if(sources.permittedUntil(scope,originals(state),"READ").isBefore(b.expiresAt()))throw new SecurityException("RUN_SOURCE_RETENTION_CHANGED");
        });
        return new Context(store,new AdjudicationAgent(store,tools,(c,s)->{tools.current(c);return true;},Clock.systemUTC()));
    }
    private static List<Source> originals(State state) {
        var refs=new ArrayList<Source>();refs.add(state.caseInput().source());state.activeMaterials().stream().filter(Material::readable).forEach(m->refs.add(m.source()));
        state.claims().forEach(c->refs.add(c.source()));state.citations().forEach(c->refs.add(c.source()));return refs;
    }
    private View view(String run,Context context) {
        var c=context.store.load(run);if(c==null)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return new View(c,"组织当前案件材料、争点、法律与要素对应，形成带缺口说明的辅助报告并进入人工复核",context.store.events(run));
    }
    @ExceptionHandler(org.springframework.security.oauth2.jwt.JwtException.class) @ResponseStatus(HttpStatus.UNAUTHORIZED) void expiredIdentity() { }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT) void conflict() { }
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
