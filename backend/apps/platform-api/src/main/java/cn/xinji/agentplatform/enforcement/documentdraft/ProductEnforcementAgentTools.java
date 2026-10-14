package cn.xinji.agentplatform.enforcement.documentdraft;

import cn.xinji.agentplatform.enforcement.agent.EnforcementAgent;
import cn.xinji.agentplatform.enforcement.procedure.*;
import cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.ToolFailure;
import cn.xinji.agentplatform.runtime.AgentRunPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

/** Calls actual evidence/template/rule, deterministic procedure and document services; never final review/penalty actions. */
final class ProductEnforcementAgentTools implements EnforcementAgent.Tools {
    record Input(DocumentDraftContract.Input document,EnforcementProcedureController.CheckCommand procedure) {
        Input {
            // The procedure snapshot must concern the exact case revision used by the document.
            // Validate here so HTTP deserialization and retained-checkpoint recovery share the same boundary.
            if(document==null || procedure==null || !document.caseInput().id().equals(procedure.caseRef())
                    || !Long.toString(document.caseInput().revision()).equals(procedure.caseVersion()))
                throw new IllegalArgumentException("EXACT_CASE_PROCEDURE_BINDING_REQUIRED");
        }
    }
    private final EnforcementDocumentDraftController.Context services;private final ProcedureService procedure;
    private final String actor;private final ObjectMapper json;
    ProductEnforcementAgentTools(EnforcementDocumentDraftController.Context services,ProcedureService procedure,String actor,ObjectMapper json) {
        this.services=services;this.procedure=procedure;this.actor=actor;this.json=json;
    }
    Input input(Checkpoint c) {
        try {var input=json.readValue(c.task().goal().description(),Input.class);
            if(input.procedure()==null || !input.document().caseInput().id().equals(input.procedure().caseRef())
                    || !input.document().organization().equals(c.task().scope()))throw new SecurityException("TASK_CASE_SCOPE_MISMATCH");
            return input;
        }catch(SecurityException error){throw error;}catch(Exception error){throw new IllegalArgumentException("TASK_INPUT_INVALID");}
    }
    Preparation current(Checkpoint c) {
        var input=input(c);var prepared=services.drafts().prepare(actor,c.task().id(),input.document());
        if(!prepared.inputManifestHash().equals(c.task().goal().version()))throw new IllegalArgumentException("TASK_INPUT_STALE");
        return prepared;
    }
    @Override public Observation observe(Checkpoint c) {return observation("document-input:"+c.task().goal().version(),current(c));}
    @Override public Observation evidence(Checkpoint c,Invocation invocation) {
        var resolved=services.source().resolve(actor,input(c).document());
        return observation("evidence-map:"+c.task().goal().version(),resolved.sources());
    }
    @Override public Observation gaps(Checkpoint c,Invocation invocation) {
        var prepared=current(c);if(!prepared.ready())throw new ToolFailure(StopReason.INSUFFICIENT_EVIDENCE);
        return observation("document-gaps:"+c.task().goal().version(),prepared.diagnostics());
    }
    @Override public Observation rules(Checkpoint c,Invocation invocation) {
        var resolved=services.source().resolve(actor,input(c).document());
        return observation("document-rule:"+resolved.ruleSnapshot().id()+"/"+resolved.ruleSnapshot().revision(),resolved.mapping());
    }
    @Override public Observation procedure(Checkpoint c,Invocation invocation) {
        current(c);var selected=input(c).procedure();
        var receipt=procedure.check(actor,AgentRunPersistence.invocationKey(invocation.id()),selected.id(),new ProcedureService.CheckRequest(
                selected.caseRef(),selected.caseVersion(),selected.ruleId(),selected.ruleVersion(),selected.calendarRef(),selected.calendarVersion()));
        var report=procedure.readReport(actor,receipt.resultId(),receipt.version());
        if(!selected.caseRef().equals(report.caseRef()) || !selected.caseVersion().equals(report.caseVersion())
                || report.items().stream().anyMatch(item->item.state()==ProcedureContract.State.FAIL || item.state()==ProcedureContract.State.UNDETERMINED)
                || !report.unprocessed().isEmpty())throw new ToolFailure(StopReason.NEED_HUMAN);
        return observation("procedure-report:"+receipt.resultId()+"/"+receipt.version(),report);
    }
    @Override public Observation draft(Checkpoint c,Invocation invocation) {
        var prepared=current(c);
        var receipt=services.drafts().generate(actor,AgentRunPersistence.invocationKey(invocation.id()),c.task().id(),
                input(c).document(),prepared.inputManifestHash());
        var draft=services.drafts().read(actor,receipt.draftId(),receipt.revision());
        return observation("document-draft:"+draft.id()+"/"+draft.revision(),draft);
    }
    @Override public Evaluation evaluate(Checkpoint c) {
        current(c);
        if(!c.observations().getLast().reference().startsWith("document-draft:"))return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"draft-not-retained");
        String[] ref=c.observations().getLast().reference().substring("document-draft:".length()).split("/");
        var draft=services.drafts().read(actor,ref[0],Long.parseLong(ref[1]));
        if(!draft.inputManifestHash().equals(c.task().goal().version()) || draft.contentJson().isBlank()
                || draft.missingItems().stream().anyMatch(Diagnostic::blocking))return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"draft-critical-gap");
        return new Evaluation(Verdict.NEED_HUMAN,c.observations().getLast().reference());
    }
    private Observation observation(String ref,Object value) {
        try{return new Observation(ref,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))));}
        catch(Exception error){throw new IllegalStateException("AGENT_EVIDENCE_ENCODING_FAILED");}
    }
}
