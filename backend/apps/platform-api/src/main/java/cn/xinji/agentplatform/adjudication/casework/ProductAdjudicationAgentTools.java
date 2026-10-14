package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.agent.AdjudicationAgent;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.ToolFailure;
import cn.xinji.agentplatform.runtime.AgentRunPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

/** Existing materials/findings/law/report services, with optional explicitly selected approved model materials. */
final class ProductAdjudicationAgentTools implements AdjudicationAgent.Tools {
    record Input(long initialWorkVersion,List<String> modelMaterialIds) {
        Input {modelMaterialIds=List.copyOf(modelMaterialIds);if(initialWorkVersion<1 || new HashSet<>(modelMaterialIds).size()!=modelMaterialIds.size())throw new IllegalArgumentException("EXACT_CASE_INPUT_REQUIRED");}
    }
    private final CaseWorkRepository cases;private final CaseWorkCommands commands;private final CaseWorkAccess access;
    private final MaterialOrganizationService model;private final ObjectMapper json;
    ProductAdjudicationAgentTools(CaseWorkRepository cases,CaseWorkCommands commands,CaseWorkAccess access,MaterialOrganizationService model,ObjectMapper json) {
        this.cases=cases;this.commands=commands;this.access=access;this.model=model;this.json=json;
    }
    Input input(Checkpoint c) {
        try{return json.readValue(c.task().goal().description(),Input.class);}catch(Exception error){throw new IllegalArgumentException("AGENT_CASE_INPUT_INVALID");}
    }
    State current(Checkpoint c) {
        var state=cases.read(c.task().id(),null,access);
        if(!Long.toString(state.inputVersion()).equals(c.task().goal().version()) || !state.caseInput().dataScope().equals(c.task().scope()))
            throw new IllegalArgumentException("AGENT_CASE_INPUT_STALE");
        return state;
    }
    @Override public Observation observe(Checkpoint c) {var state=current(c);return observation(ref(state,"case-goal"),state.caseInput());}
    @Override public Observation materials(Checkpoint c,Invocation invocation) {
        var state=current(c);var input=input(c);
        if(!input.modelMaterialIds().isEmpty()) {
            if(model==null)throw new ToolFailure(StopReason.NEED_HUMAN);
            var run=model.generate(c.task().id(),input.initialWorkVersion(),input.modelMaterialIds(),AgentRunPersistence.invocationKey(invocation.id()),access);
            return observation("material-model-run:"+run.runId(),run);
        }
        // No synthetic model output: deterministic grouping of the actually readable persisted material versions.
        return observation(ref(state,"material-organization"),state.activeMaterials().stream().map(m->Map.of(
                "id",m.id(),"side",m.side(),"source",m.source(),"readable",m.readable(),"assertedPurpose",m.assertedPurpose())).toList());
    }
    @Override public Observation issuesAndLaw(Checkpoint c,Invocation invocation) {
        var state=current(c);
        var candidates=state.citations().stream().map(law->Map.of("citationId",law.id(),"source",law.source(),
                "regionMatchesRecordedContext",Objects.equals(state.caseInput().region(),law.region()),
                "procedureMatchesRecordedContext",Objects.equals(state.caseInput().procedure(),law.procedure()),
                "humanSelectionReason",law.selectionReason())).toList();
        return observation(ref(state,"issue-law-candidates"),Map.of("recordedIssues",state.caseInput().issues(),"selectedLawCandidates",candidates));
    }
    @Override public Observation patentStatus(Checkpoint c,Invocation invocation) {
        var state=current(c);
        // The existing source contract has no authoritative status field: identify that gap, never invent a status.
        return observation(ref(state,"patent-status-gap"),Map.of("patentReference",state.claim()==null?"":state.claim().patentReference(),
                "gap","OFFICIAL_STATUS_NOT_VERIFIED","authorityVerified",false));
    }
    @Override public Observation elements(Checkpoint c,Invocation invocation) {
        var state=current(c);return observation(ref(state,"element-evidence"),Map.of("claims",state.claims(),"humanRecordedCorrespondences",state.findings()));
    }
    @Override public Observation report(Checkpoint c,Invocation invocation) {
        current(c);var input=input(c);long expected=input.initialWorkVersion()+(input.modelMaterialIds().isEmpty()?0:1);
        var receipt=commands.execute(c.task().id(),"GENERATE_REPORT",null,json.createObjectNode().put("expectedVersion",expected),
                AgentRunPersistence.invocationKey(invocation.id()),access);
        var report=cases.read(c.task().id(),null,access).report(receipt.reportRevision());
        return observation("case-report:"+c.task().id()+"/"+report.revision(),report);
    }
    @Override public Evaluation evaluate(Checkpoint c) {
        var state=current(c);String ref=c.observations().getLast().reference();
        if(!ref.startsWith("case-report:"))return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"report-not-retained");
        long revision=Long.parseLong(ref.substring(ref.lastIndexOf('/')+1));var report=state.report(revision);
        if(report.inputVersion()!=state.inputVersion() || !report.policyVersion().equals(commands.currentPolicyVersion()))
            return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"report-stale");
        for(var observation:c.observations())if(observation.reference().startsWith("material-model-run:")) {
            if(model==null || model.read(c.task().id(),observation.reference().substring("material-model-run:".length()),access).stale())
                return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"material-model-stale");
        }
        return new Evaluation(Verdict.NEED_HUMAN,ref+(report.complete()?"":"#critical-gaps"));
    }
    private String ref(State state,String kind) {return "case-work:"+state.caseInput().id()+"/"+state.version()+"/"+kind;}
    private Observation observation(String ref,Object value) {
        try{return new Observation(ref,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))));}
        catch(Exception error){throw new IllegalStateException("AGENT_EVIDENCE_ENCODING_FAILED");}
    }
}
