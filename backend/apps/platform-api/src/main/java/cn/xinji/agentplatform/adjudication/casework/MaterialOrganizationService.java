package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.materials.*;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.*;
import cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.Input;
import cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.Candidate;

/** Current case, source and model permission are checked before and after the external call. */
public final class MaterialOrganizationService {
    public record RunView(String runId,long workVersion,long inputVersion,boolean stale,String model,String promptVersion,List<Candidate> candidates) { }
    private final CaseWorkRepository cases; private final JdbcMaterialRunStore runs; private final MaterialModelPolicy policy;
    private final MaterialOrganizationModel model; private final ObjectMapper json; private final TransactionTemplate tx;
    private final CaseSourceAuthority sources; private final ProductGrantWatermark grants; private final Clock clock;
    private final MaterialCandidateValidator validator=new MaterialCandidateValidator();
    public MaterialOrganizationService(CaseWorkRepository cases,JdbcMaterialRunStore runs,MaterialModelPolicy policy,
            MaterialOrganizationModel model,ObjectMapper json,TransactionTemplate tx,CaseSourceAuthority sources,ProductGrantWatermark grants,Clock clock) {
        this.cases=cases;this.runs=runs;this.policy=policy;this.model=model;this.json=json.copy();this.tx=tx;this.sources=sources;this.grants=grants;this.clock=clock;
    }
    public RunView generate(String caseId,long version,List<String> materialIds,String key,CaseWorkRepository.Access access) {
        if(version<1 || materialIds==null || materialIds.isEmpty() || materialIds.size()>20 || new HashSet<>(materialIds).size()!=materialIds.size())throw new IllegalArgumentException("MODEL_MATERIAL_SELECTION_INVALID");
        State latest=cases.read(caseId,null,access);
        access.require(CaseWorkRepository.Scope.of(latest.caseInput()),"MODEL_EXECUTE");
        if(latest.version()!=version && !runs.requestExists(policy,key,access.identity()))throw new IllegalArgumentException("WORK_VERSION_CONFLICT");
        State state=latest.version()==version?latest:cases.read(caseId,version,access);
        var selected=select(state,materialIds);var input=inputs(selected);policy.require(caseId,input,clock);authorize(state,selected,access,true);
        long grantStamp=grants.currentGeneration(),sourceStamp=sources.generation();
        String request=model.request(input,2048);long upper=request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+4096L;
        String fingerprint=sha256(jsonText(List.of(policy.configurationSha(),caseId,version,access.actor(),selected,request)));
        if(latest.version()!=version){var replay=runs.existing(policy,key,access.identity(),fingerprint);if(replay==null)throw new IllegalArgumentException("WORK_VERSION_CONFLICT");return read(caseId,replay.id(),access);}
        var reservation=runs.reserve(policy,key,access.identity(),fingerprint,upper,2048);
        if(reservation.replay())return read(caseId,reservation.id(),access);
        try {
            var reply=model.invoke(request);List<Candidate> candidates;
            try{candidates=validator.validate(input,reply.rawContent());}
            catch(IllegalArgumentException rejected){runs.complete(policy,reservation,reply.usage().inputTokens(),reply.usage().outputTokens(),jsonText(Map.of("request",request,"reply",reply)),false);throw rejected;}
            grants.assertUnchanged(grantStamp);sources.assertUnchanged(sourceStamp);authorize(state,selected,access,true);
            String payload=jsonText(Map.of("caseId",caseId,"workVersion",version+1,"inputVersion",state.inputVersion(),"model",reply.model(),
                    "promptVersion","ADJ_MATERIAL_ORGANIZATION/v1","materialIds",materialIds,"inputs",input,"request",request,"reply",reply,"candidates",candidates));
            tx.executeWithoutResult(status->{
                grants.assertUnchanged(grantStamp);sources.assertUnchanged(sourceStamp);authorize(state,selected,access,true);
                cases.mutate(caseId,version,"model_"+key,fingerprint,"MODEL_ORGANIZATION",selected.stream().map(Material::source).toList(),null,access,
                        current->recordMaterialRun(current,version,reservation.id(),access.actor(),clock.instant()));
                runs.complete(policy,reservation,reply.usage().inputTokens(),reply.usage().outputTokens(),payload,true);
                grants.assertUnchanged(grantStamp);sources.assertUnchanged(sourceStamp);authorize(state,selected,access,true);
            });
            return read(caseId,reservation.id(),access);
        }catch(RuntimeException failure){if("ADJ_MODEL_CHANGED".equals(failure.getMessage()))runs.freeze(reservation);else runs.uncertain(reservation);throw failure;}
    }
    public RunView read(String caseId,String id,CaseWorkRepository.Access access) {
        State current=cases.read(caseId,null,access);
        if(!caseId.equals(policy.caseId()))throw new SecurityException("MODEL_APPROVED_SCOPE_REQUIRED");
        var node=jsonTree(runs.load(policy,id));
        if(!caseId.equals(node.path("caseId").asText()))throw new IllegalStateException("MODEL_PAYLOAD_INTEGRITY_FAILED");
        var ids=new ArrayList<String>();node.path("materialIds").forEach(x->ids.add(x.asText()));
        var selected=new ArrayList<Material>();for(var materialId:ids)selected.add(current.materials().stream().filter(m->m.id().equals(materialId)).findFirst().orElseThrow(()->new IllegalStateException("MODEL_SOURCE_MISSING")));
        authorize(current,selected,access,false);var input=inputs(selected);policy.require(caseId,input,clock);
        var checked=validator.validate(input,node.path("reply").path("rawContent").asText());
        if(!jsonTree(jsonText(checked)).equals(node.path("candidates")))throw new IllegalStateException("MODEL_PAYLOAD_INTEGRITY_FAILED");
        boolean stale=current.inputVersion()!=node.path("inputVersion").asLong() || !current.activeMaterials().containsAll(selected);
        return new RunView(id,node.path("workVersion").asLong(),node.path("inputVersion").asLong(),stale,node.path("model").asText(),node.path("promptVersion").asText(),checked);
    }
    private void authorize(State state,List<Material> materials,CaseWorkRepository.Access access,boolean write) {
        var scope=CaseWorkRepository.Scope.of(state.caseInput());access.require(scope,"READ");access.require(scope,"MODEL_EXECUTE");if(write)access.require(scope,"WRITE");
        var src=materials.stream().map(Material::source).toList();for(var use:write?List.of("READ","SAVE","ANALYZE","MODEL"):List.of("READ","MODEL"))access.sources(scope,src,use);
    }
    private static List<Material> select(State state,List<String> ids){var selected=new ArrayList<Material>();for(var id:ids)selected.add(state.activeMaterials().stream().filter(m->m.id().equals(id)&&m.readable()).findFirst().orElseThrow(()->new IllegalArgumentException("MODEL_MATERIAL_NOT_CURRENT")));return List.copyOf(selected);}
    private static List<Input> inputs(List<Material> materials){var out=new ArrayList<Input>();for(var m:materials) {
        if(m.source().kind()!=InputKind.PROJECT_AUTHORED || !sha256(m.content()).equals(m.source().sha256()))throw new SecurityException("MODEL_APPROVED_SOURCE_REQUIRED");
        out.add(new Input("s"+(out.size()+1),MaterialOrganizationModel.Side.valueOf(m.side().name()),m.source(),m.content()));
    }return List.copyOf(out);}
    private String jsonText(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("MODEL_PAYLOAD_ENCODING_FAILED");}}
    private JsonNode jsonTree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("MODEL_PAYLOAD_INTEGRITY_FAILED");}}
}
