package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.agent.ConsultationAgent;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.ToolFailure;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Own-domain product adapter: real retrieval worker/fence/source and persisted model dialogue, no synthetic fallback. */
final class ProductConsultationAgentTools implements ConsultationAgent.Tools {
    private final ProductRetrievalWorkspace workspace; private final ProductRetrievalWorkflow workflow;
    private final ProductRetrievalDialogue dialogue; private final Jwt jwt; private final ObjectMapper json;
    ProductConsultationAgentTools(ProductRetrievalWorkspace workspace,ProductRetrievalWorkflow workflow,
            ProductRetrievalDialogue dialogue,Jwt jwt,ObjectMapper json) {
        this.workspace=workspace;this.workflow=workflow;this.dialogue=dialogue;this.jwt=jwt;this.json=json;
    }
    ProductRetrievalWorkspace.View current(Checkpoint c) {
        var view=workspace.read(c.task().id(),jwt);var goal=view.goal();
        workspace.permitted(c.task().id(),jwt,ProductAccessPolicy.Action.WRITE);
        if(goal==null || !goal.goalId().equals(c.task().goal().id()) || !version(goal).equals(c.task().goal().version())
                || !view.task().dataScope().equals(c.task().scope())) throw ProductRetrievalWorkspace.conflict();
        requireDependencies(c);
        return view;
    }
    /** Exact Run evidence is independent of the workface's current-result/recent-answer window. */
    void requireDependencies(Checkpoint c) {
        if(c==null)return;
        workspace.permitted(c.task().id(),jwt,ProductAccessPolicy.Action.READ);
        for(var observed:c.observations()) {
            String reference=observed.reference();
            if(reference.startsWith("retrieval:"))
                workspace.requireResultDependency(c.task().id(),reference.substring("retrieval:".length()),jwt);
            else if(reference.startsWith("retrieval-citations:"))
                workspace.requireResultDependency(c.task().id(),reference.substring("retrieval-citations:".length()),jwt);
            else if(reference.startsWith("answer-turn:"))
                dialogue.read(c.task().id(),reference.substring("answer-turn:".length()),jwt);
        }
        workspace.permitted(c.task().id(),jwt,ProductAccessPolicy.Action.READ);
    }
    static String version(ProductTaskGoalReader.GoalView g) { return g.goalRevision()+":"+g.taskVersion()+":"+g.inputId(); }
    @Override public Observation observe(Checkpoint c) { return observation("task:"+c.task().id()+"/goal:"+c.task().goal().id(),current(c).goal()); }
    @Override public Observation search(Checkpoint c,Invocation invocation) {
        var view=current(c);
        try {
            var page=workflow.execute(c.task().id(),view.task().version(),jwt);
            return observation("retrieval:"+page.resultSetId(),page);
        } catch(ResponseStatusException failure) {
            if(failure.getStatusCode().value()==503)throw new ToolFailure(StopReason.SOURCE_UNAVAILABLE,true);
            throw failure;
        }
    }
    @Override public Observation sources(Checkpoint c,Invocation invocation) {
        var page=current(c).result();if(page==null)throw new ToolFailure(StopReason.INSUFFICIENT_EVIDENCE,true);
        if(page.members().stream().anyMatch(m->page.citations().stream().noneMatch(citation->citation.memberId().equals(m.memberId()))))
            throw new ToolFailure(StopReason.INSUFFICIENT_EVIDENCE);
        return observation("retrieval-citations:"+page.resultSetId(),page.citations());
    }
    @Override public Observation draft(Checkpoint c,Invocation invocation) {
        var view=current(c);if(view.result()==null)throw new ToolFailure(StopReason.INSUFFICIENT_EVIDENCE);
        String key=UUID.nameUUIDFromBytes(invocation.id().getBytes(StandardCharsets.UTF_8)).toString();
        var turn=dialogue.invoke(c.task().id(),jwt,key,"EXPLANATION",new ProductRetrievalDialogue.Input(
                view.task().version(),null,view.result().resultSetId()));
        return observation("answer-turn:"+turn.turnId(),turn);
    }
    @Override public Evaluation evaluate(Checkpoint c) {
        if(!c.observations().isEmpty() && c.observations().getLast().reference().startsWith("runtime-failure/"))
            return new Evaluation(Verdict.REPLAN,"current-source-recovery");
        var view=current(c);var page=view.result();
        if(page==null)return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"retrieval-not-available");
        var last=c.observations().getLast().reference();
        if(!last.startsWith("answer-turn:"))return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"answer-not-persisted");
        var turn=dialogue.read(c.task().id(),last.substring("answer-turn:".length()),jwt);
        if(!"EXPLANATION".equals(turn.kind()) || !page.resultSetId().equals(turn.resultSetId())
                || !page.resultSha().equals(turn.resultSha()) || turn.output().path("answer").asText().isBlank()
                || (!page.members().isEmpty() && turn.citations().isEmpty()))
            return new Evaluation(Verdict.INSUFFICIENT_EVIDENCE,"answer-evidence-not-supported");
        if("PARTIAL".equals(page.status()))return new Evaluation(Verdict.NEED_HUMAN,"partial-source-coverage");
        return new Evaluation(Verdict.SATISFIED,"answer-turn:"+turn.turnId());
    }
    private Observation observation(String ref,Object value) {
        try {return new Observation(ref,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))));}
        catch(Exception error){throw new IllegalStateException("AGENT_EVIDENCE_ENCODING_FAILED");}
    }
}
