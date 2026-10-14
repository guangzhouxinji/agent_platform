package cn.xinji.agentplatform.adjudication.agent;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import cn.xinji.agentplatform.runtime.AgentRuntime;
import java.time.Clock;
import java.util.*;

/** Independently owned adjudication Agent. Domain services remain authoritative; only its own ports are callable. */
public final class AdjudicationAgent implements Skills, CompletionEvaluator {
    public static final String TASK_TYPE="ADJUDICATION_AUXILIARY", COMPLETION_CONTRACT="ADJUDICATION_HUMAN_REVIEW/v1";
    public static final Definition DEFINITION=new Definition("adjudication-agent","1",Set.of(TASK_TYPE),
            Map.ofEntries(Map.entry("MaterialOrganization",Set.of("material-organize")),
                Map.entry("IssueAndLawMatch",Set.of("issue-law-match")),
                Map.entry("PatentStatusAndRisk",Set.of("patent-status")),
                Map.entry("ElementEvidenceComparison",Set.of("element-evidence")),
                Map.entry("ReportDraftAndReview",Set.of("report-draft"))),"ADJUDICATION_AGENT_PROMPT/v1","ADJUDICATION_AGENT_POLICY/v1","ADJUDICATION_AGENT_EVALUATION/v1");
    public interface Tools {
        Observation observe(Checkpoint c);
        Observation materials(Checkpoint c, Invocation invocation);
        Observation issuesAndLaw(Checkpoint c, Invocation invocation);
        Observation patentStatus(Checkpoint c, Invocation invocation);
        Observation elements(Checkpoint c, Invocation invocation);
        Observation report(Checkpoint c, Invocation invocation);
        Evaluation evaluate(Checkpoint c);
    }
    private final Tools tools;
    private final AgentRuntime runtime;
    public AdjudicationAgent(CheckpointStore store,Tools tools,Authorization authorization,Clock clock) {
        this.tools=Objects.requireNonNull(tools);this.runtime=new AgentRuntime(DEFINITION,store,this,authorization,this,clock);
    }
    public Checkpoint start(String runId,Task task,Budget budget) {
        if(!COMPLETION_CONTRACT.equals(task.goal().completionContract())) throw new IllegalArgumentException("DOMAIN_COMPLETION_CONTRACT_REQUIRED");
        return runtime.start(runId,task,budget);
    }
    public Checkpoint advance(String runId) { return runtime.advance(runId); }
    @Override public Observation observe(Checkpoint c) { return tools.observe(c); }
    @Override public Plan plan(Checkpoint c) {
        return new Plan("ADJUDICATION_PLAN/v1/"+c.replans(),List.of(
                new Step("1", "MaterialOrganization", "material-organize", c.task().goal().description()),
                new Step("2", "IssueAndLawMatch", "issue-law-match", c.task().goal().description()),
                new Step("3", "PatentStatusAndRisk", "patent-status", c.task().goal().description()),
                new Step("4", "ElementEvidenceComparison", "element-evidence", c.task().goal().description()),
                new Step("5", "ReportDraftAndReview", "report-draft", c.task().goal().description())));
    }
    @Override public Observation invoke(Checkpoint c,Invocation invocation) {
        return switch(invocation.step().tool()) {
            case "material-organize" -> tools.materials(c, invocation);
            case "issue-law-match" -> tools.issuesAndLaw(c, invocation);
            case "patent-status" -> tools.patentStatus(c, invocation);
            case "element-evidence" -> tools.elements(c, invocation);
            case "report-draft" -> tools.report(c, invocation);
            default -> throw new SecurityException("DOMAIN_TOOL_NOT_ALLOWED");
        };
    }
    @Override public Evaluation evaluate(Checkpoint c) {
        Evaluation evaluation=tools.evaluate(c);
        // A draft is never a formal punishment/adjudication or a substitute for authorized human review.
        if(evaluation.verdict()==Verdict.SATISFIED) return new Evaluation(Verdict.NEED_HUMAN,evaluation.outcomeRef());
        return evaluation;
    }
}
