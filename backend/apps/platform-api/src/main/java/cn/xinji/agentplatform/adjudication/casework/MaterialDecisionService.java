package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.materials.*;
import cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.*;
import cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.*;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Current product permissions, immutable model evidence, and existing primary mutation transaction. */
public final class MaterialDecisionService {
    public record View(Decision version,long currentOrganizationVersion,long currentInputVersion,boolean stale,boolean currentCandidateDecision) { }
    public record Page(List<View> versions,long nextAfterOrganizationVersion,long currentOrganizationVersion) { }
    private record Bound(Run run,Candidate candidate,Input original,String inputSha) { }
    private final CaseWorkRepository cases; private final JdbcMaterialDecisionStore decisions; private final JdbcMaterialRunStore runs;
    private final MaterialOrganizationService organization; private final MaterialModelPolicy policy; private final JdbcTemplate db;
    private final CaseSourceAuthority sources; private final ProductGrantWatermark grants; private final CaseWorkCodec codec;
    private final org.springframework.transaction.support.TransactionTemplate readTx;
    private final MaterialCandidateDecisions domain; private final Clock clock; private final ObjectMapper json;
    public MaterialDecisionService(CaseWorkRepository cases,JdbcMaterialDecisionStore decisions,JdbcMaterialRunStore runs,
            MaterialOrganizationService organization,MaterialModelPolicy policy,JdbcTemplate db,CaseSourceAuthority sources,
            ProductGrantWatermark grants,CaseWorkCodec codec,MaterialCandidateDecisions domain,Clock clock,ObjectMapper json,org.springframework.transaction.support.TransactionTemplate readTx){
        this.cases=cases;this.decisions=decisions;this.runs=runs;this.organization=organization;this.policy=policy;this.db=db;
        this.sources=sources;this.grants=grants;this.codec=codec;this.domain=domain;this.clock=clock;this.json=json.copy();this.readTx=readTx;
    }
    public View decide(String caseId,String runId,String candidateId,Request request,String key,CaseWorkRepository.Access access){return command(caseId,runId,candidateId,null,request,key,access);}
    public View revise(String caseId,String baseId,Request request,String key,CaseWorkRepository.Access access){
        var prior=detail(caseId,baseId,access).version();return command(caseId,prior.runId(),prior.candidateId(),baseId,request,key,access);
    }
    private View command(String caseId,String runId,String candidateId,String baseId,Request request,String key,CaseWorkRepository.Access access){
        if(key==null||!key.matches("[A-Za-z0-9_-]{1,80}")||request==null)throw new IllegalArgumentException("MATERIAL_DECISION_REQUEST_INVALID");
        CaseWorkCommands.validId(caseId);uuid(runId);if(baseId!=null)uuid(baseId);if(candidateId==null||!candidateId.matches("c[1-9][0-9]{0,3}"))throw new IllegalArgumentException("MATERIAL_CANDIDATE_INVALID");
        long g=grants.currentGeneration(),s=sources.generation();var pre=cases.read(caseId,null,access);var initial=bound(caseId,runId,candidateId,access);authorize(pre,initial,access,true);
        String fingerprint=sha256(codec.encode(Arrays.asList(caseId,runId,candidateId,baseId,request,access.identity(),policy.key())));
        // Stale evidence cannot authorize a new write. An existing exact request still goes through
        // the original transaction receipt replay and current-authority/history checks below.
        if(initial.run().inputVersion()!=pre.inputVersion()&&decisions.requestVersion(caseId,access.identity(),key,fingerprint)==null)throw new IllegalArgumentException("MATERIAL_MODEL_RUN_STALE");
        String commandKey="md_"+sha256(codec.encode(List.of(caseId,access.identity(),key)));
        cases.mutate(caseId,request.expectedWorkVersion(),commandKey,fingerprint,"MATERIAL_DECISION",List.of(initial.original().source()),null,access,current->{
            grants.assertUnchanged(g);sources.assertUnchanged(s);
            var b=bound(caseId,runId,candidateId,access);authorize(current,b,access,true);
            long head=decisions.lockHead(caseId);verifyIndex(current,runId);var snapshot=new Current(caseId,current.version(),head,current.inputVersion(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),decisions.candidateHeads(caseId));
            String id=UUID.randomUUID().toString();var d=baseId==null?domain.decide(b.run(),b.candidate(),b.original(),snapshot,request,access.actor(),id):domain.revise(decisions.load(caseId,baseId),b.run(),b.original(),snapshot,request,access.actor(),id);
            decisions.save(d,access.identity(),key,fingerprint,policy.key(),b.inputSha());
            var audit=new ArrayList<Audit>(current.audits());audit.add(new Audit(d.workVersion(),"MATERIAL_"+d.action().name(),access.actor(),codec.encode(d.auditReference()),d.createdAt()));
            var next=new State(d.workVersion(),current.inputVersion(),current.caseInput(),current.materials(),current.claims(),current.findings(),current.citations(),current.reports(),current.inputContributors(),audit);
            authorize(next,b,access,true);grants.assertUnchanged(g);sources.assertUnchanged(s);return next;
        });
        // Authority is external: failure here rejects the response, not a primary commit already acknowledged.
        grants.assertUnchanged(g);sources.assertUnchanged(s);authorize(cases.read(caseId,null,access),initial,access,true);
        String id=decisions.requestVersion(caseId,access.identity(),key,fingerprint);if(id==null)throw new IllegalStateException("MATERIAL_DECISION_RECEIPT_INCONSISTENT");return detail(caseId,id,access);
    }
    public View detail(String caseId,String id,CaseWorkRepository.Access access){long g=grants.currentGeneration(),s=sources.generation();var result=readTx.execute(status->detailLocked(caseId,id,access));grants.assertUnchanged(g);sources.assertUnchanged(s);return result;}
    private View detailLocked(String caseId,String id,CaseWorkRepository.Access access){
        uuid(id);long g=grants.currentGeneration(),s=sources.generation();var current=cases.read(caseId,null,access);var binding=decisions.binding(caseId,id);
        var b=bound(caseId,binding.runId(),binding.candidateId(),access);authorize(current,b,access,false);
        if(!binding.policyKey().equals(policy.key())||!binding.inputSha().equals(b.inputSha())||!binding.expiresAt().equals(b.run().expiresAt())||binding.workVersion()>current.version()||binding.organizationVersion()>decisions.head(caseId))throw new IllegalStateException("MATERIAL_DECISION_INTEGRITY_FAILED");
        verifyIndex(current,binding.runId());var d=decisions.load(caseId,id);if(d.inputVersion()!=b.run().inputVersion()||!sameCandidate(d,b.candidate()))throw new IllegalStateException("MATERIAL_DECISION_INTEGRITY_FAILED");
        var saved=cases.read(caseId,d.workVersion(),access);var last=saved.audits().getLast();if(!last.actor().equals(d.actor())||!last.action().equals("MATERIAL_"+d.action().name())||!last.objectId().equals(codec.encode(d.auditReference()))||saved.inputVersion()!=d.inputVersion())throw new IllegalStateException("MATERIAL_DECISION_RECEIPT_INCONSISTENT");
        var active=decisions.candidateHeads(caseId);long head=decisions.head(caseId);grants.assertUnchanged(g);sources.assertUnchanged(s);
        return new View(d,head,current.inputVersion(),current.inputVersion()!=d.inputVersion(),id.equals(active.get(d.runId()+":"+d.candidateId())));
    }
    public Page list(String caseId,long after,int limit,CaseWorkRepository.Access access){long g=grants.currentGeneration(),s=sources.generation();var result=readTx.execute(status->listLocked(caseId,after,limit,access));grants.assertUnchanged(g);sources.assertUnchanged(s);return result;}
    private Page listLocked(String caseId,long after,int limit,CaseWorkRepository.Access access){long g=grants.currentGeneration(),s=sources.generation();var current=cases.read(caseId,null,access);access.require(CaseWorkRepository.Scope.of(current.caseInput()),"MODEL_EXECUTE");if(!caseId.equals(policy.caseId())||!policy.validUntil().isAfter(clock.instant()))throw new SecurityException("MODEL_APPROVED_SCOPE_REQUIRED");var ids=decisions.page(caseId,after,limit);var views=ids.stream().map(id->detail(caseId,id,access)).toList();grants.assertUnchanged(g);sources.assertUnchanged(s);return new Page(views,views.isEmpty()?after:views.getLast().version().organizationVersion(),decisions.head(caseId));}
    private void verifyIndex(State state,String activeRun){
        long organizationVersion=0;var expected=new HashMap<String,String>();
        for(var a:state.audits())if(Set.of("MATERIAL_ACCEPT","MATERIAL_EDIT","MATERIAL_REJECT").contains(a.action())){
            var ref=codec.decode(a.objectId(),AuditReference.class);
            if(ref.organizationVersion()!=++organizationVersion||!a.action().equals("MATERIAL_"+ref.action().name()))throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT");
            if(ref.runId().equals(activeRun)){Decision d;try{d=decisions.load(state.caseInput().id(),ref.versionId());}catch(IllegalArgumentException missing){throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT",missing);}if(!d.auditReference().equals(ref)||d.workVersion()!=a.version()||!d.actor().equals(a.actor()))throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT");expected.put(ref.runId()+":"+ref.candidateId(),ref.versionId());}
        }
        if(decisions.head(state.caseInput().id())!=organizationVersion)throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT");
        var actual=decisions.candidateHeads(state.caseInput().id());for(var entry:actual.entrySet())if(entry.getKey().startsWith(activeRun+":")&&!Objects.equals(expected.get(entry.getKey()),entry.getValue()))throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT");for(var entry:expected.entrySet())if(!entry.getValue().equals(actual.get(entry.getKey())))throw new IllegalStateException("MATERIAL_DECISION_INDEX_INCONSISTENT");
    }
    private Bound bound(String caseId,String runId,String candidateId,CaseWorkRepository.Access access){
        var view=organization.read(caseId,runId,access);var candidate=view.candidates().stream().filter(c->c.id().equals(candidateId)).findFirst().orElseThrow(()->new IllegalArgumentException("MATERIAL_CANDIDATE_NOT_FOUND"));
        JsonNode node;try{node=json.readTree(runs.load(policy,runId));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("MODEL_PAYLOAD_INTEGRITY_FAILED");}
        var originals=new ArrayList<Input>();for(var n:node.path("inputs"))originals.add(codec.decode(codec.encode(n),Input.class));
        var original=originals.stream().filter(i->i.tag().equals(candidate.sourceTag())).findFirst().orElseThrow(()->new IllegalStateException("MODEL_SOURCE_MISSING"));
        var meta=db.query("SELECT created_at,expires_at,state FROM adj3_material_run WHERE run_id=? AND policy_key=?",(r,n)->new Object[]{r.getTimestamp(1).toInstant(),r.getTimestamp(2).toInstant(),r.getString(3)},runId,policy.key());
        if(meta.size()!=1||!"COMMITTED".equals(meta.getFirst()[2]))throw new IllegalArgumentException("MODEL_RUN_NOT_COMMITTED");
        var state=cases.read(caseId,null,access);Instant sourceDeadline=sources.permittedUntil(CaseWorkRepository.Scope.of(state.caseInput()),originals.stream().map(Input::source).toList(),"MODEL");Instant deadline=(Instant)meta.getFirst()[1];if(sourceDeadline.isBefore(deadline))deadline=sourceDeadline;deadline=deadline.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return new Bound(new Run(runId,caseId,view.inputVersion(),(Instant)meta.getFirst()[0],deadline),candidate,original,sha256(codec.encode(originals)));
    }
    private void authorize(State state,Bound b,CaseWorkRepository.Access access,boolean write){var scope=CaseWorkRepository.Scope.of(state.caseInput());access.require(scope,"READ");access.require(scope,"MODEL_EXECUTE");if(write)access.require(scope,"WRITE");for(var use:write?List.of("READ","MODEL","SAVE","ANALYZE"):List.of("READ","MODEL"))access.sources(scope,List.of(b.original().source()),use);}
    private static boolean sameCandidate(Decision d,Candidate c){return d.candidateId().equals(c.id())&&d.kind()==c.kind()&&d.providerSide()==c.providerSide()&&d.machineStatement().equals(c.statement())&&d.exactQuote().equals(c.exactQuote())&&d.start()==c.start()&&d.end()==c.end()&&d.sourceTag().equals(c.sourceTag())&&d.source().id().equals(c.sourceId())&&d.source().version().equals(c.sourceVersion())&&d.source().sha256().equals(c.sourceSha256())&&d.source().locator().equals(c.originalLocator());}
    private static void uuid(String id){if(id==null||!id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("MATERIAL_DECISION_ID_INVALID");}
}
