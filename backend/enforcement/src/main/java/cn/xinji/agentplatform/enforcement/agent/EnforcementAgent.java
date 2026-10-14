package cn.xinji.agentplatform.enforcement.agent;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import cn.xinji.agentplatform.runtime.AgentRuntime;
import java.time.Clock;
import java.util.*;

/** Independently owned enforcement Agent. Domain services remain authoritative; only its own ports are callable. */
public final class EnforcementAgent implements Skills, CompletionEvaluator {
    public static final String TASK_TYPE="ENFORCEMENT_AUXILIARY", COMPLETION_CONTRACT="ENFORCEMENT_HUMAN_REVIEW/v1";
    public static final Definition DEFINITION=new Definition("enforcement-agent","1",Set.of(TASK_TYPE),
            Map.ofEntries(Map.entry("EvidenceMapping",Set.of("evidence-map")),
                Map.entry("GapDetection",Set.of("gap-detect")),
                Map.entry("RuleCheck",Set.of("rule-evaluate")),
                Map.entry("ProcedureCheck",Set.of("procedure-check")),
                Map.entry("DocumentDraft",Set.of("document-draft"))),"ENFORCEMENT_AGENT_PROMPT/v1","ENFORCEMENT_AGENT_POLICY/v1","ENFORCEMENT_AGENT_EVALUATION/v1");
    public interface Tools {
        Observation observe(Checkpoint c);
        Observation evidence(Checkpoint c, Invocation invocation);
        Observation gaps(Checkpoint c, Invocation invocation);
        Observation rules(Checkpoint c, Invocation invocation);
        Observation procedure(Checkpoint c, Invocation invocation);
        Observation draft(Checkpoint c, Invocation invocation);
        Evaluation evaluate(Checkpoint c);
    }
    private final Tools tools;
    private final AgentRuntime runtime;
    public EnforcementAgent(CheckpointStore store,Tools tools,Authorization authorization,Clock clock) {
        this.tools=Objects.requireNonNull(tools);this.runtime=new AgentRuntime(DEFINITION,store,this,authorization,this,clock);
    }
    public Checkpoint start(String runId,Task task,Budget budget) {
        if(!COMPLETION_CONTRACT.equals(task.goal().completionContract())) throw new IllegalArgumentException("DOMAIN_COMPLETION_CONTRACT_REQUIRED");
        return runtime.start(runId,task,budget);
    }
    public Checkpoint advance(String runId) { return runtime.advance(runId); }
    @Override public Observation observe(Checkpoint c) { return tools.observe(c); }
    @Override public Plan plan(Checkpoint c) {
        return new Plan("ENFORCEMENT_PLAN/v1/"+c.replans(),List.of(
                new Step("1", "EvidenceMapping", "evidence-map", c.task().goal().description()),
                new Step("2", "GapDetection", "gap-detect", c.task().goal().description()),
                new Step("3", "RuleCheck", "rule-evaluate", c.task().goal().description()),
                new Step("4", "ProcedureCheck", "procedure-check", c.task().goal().description()),
                new Step("5", "DocumentDraft", "document-draft", c.task().goal().description())));
    }
    @Override public Observation invoke(Checkpoint c,Invocation invocation) {
        return switch(invocation.step().tool()) {
            case "evidence-map" -> tools.evidence(c, invocation);
            case "gap-detect" -> tools.gaps(c, invocation);
            case "rule-evaluate" -> tools.rules(c, invocation);
            case "procedure-check" -> tools.procedure(c, invocation);
            case "document-draft" -> tools.draft(c, invocation);
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
