package cn.xinji.agentplatform.consultation.agent;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import cn.xinji.agentplatform.runtime.AgentRuntime;
import java.time.Clock;
import java.util.*;

/** Independently owned consultation Agent. Domain services remain authoritative; only its own ports are callable. */
public final class ConsultationAgent implements Skills, CompletionEvaluator {
    public static final String TASK_TYPE="CONSULTATION_RETRIEVAL", COMPLETION_CONTRACT="CONSULTATION_COMPLETION/v1";
    public static final Definition DEFINITION=new Definition("consultation-agent","1",Set.of(TASK_TYPE),
            Map.ofEntries(Map.entry("PatentSearch",Set.of("search")),
                Map.entry("EvidenceAssessment",Set.of("source-read")),
                Map.entry("AnswerDraft",Set.of("answer-draft"))),"CONSULTATION_AGENT_PROMPT/v1","CONSULTATION_AGENT_POLICY/v1","CONSULTATION_AGENT_EVALUATION/v1");
    public interface Tools {
        Observation observe(Checkpoint c);
        Observation search(Checkpoint c, Invocation invocation);
        Observation sources(Checkpoint c, Invocation invocation);
        Observation draft(Checkpoint c, Invocation invocation);
        Evaluation evaluate(Checkpoint c);
    }
    private final Tools tools;
    private final AgentRuntime runtime;
    public ConsultationAgent(CheckpointStore store,Tools tools,Authorization authorization,Clock clock) {
        this.tools=Objects.requireNonNull(tools);this.runtime=new AgentRuntime(DEFINITION,store,this,authorization,this,clock);
    }
    public Checkpoint start(String runId,Task task,Budget budget) {
        if(!COMPLETION_CONTRACT.equals(task.goal().completionContract())) throw new IllegalArgumentException("DOMAIN_COMPLETION_CONTRACT_REQUIRED");
        return runtime.start(runId,task,budget);
    }
    public Checkpoint advance(String runId) { return runtime.advance(runId); }
    @Override public Observation observe(Checkpoint c) { return tools.observe(c); }
    @Override public Plan plan(Checkpoint c) {
        return new Plan("CONSULTATION_PLAN/v1/"+c.replans(),List.of(
                new Step("1", "PatentSearch", "search", c.task().goal().description()),
                new Step("2", "EvidenceAssessment", "source-read", c.task().goal().description()),
                new Step("3", "AnswerDraft", "answer-draft", c.task().goal().description())));
    }
    @Override public Observation invoke(Checkpoint c,Invocation invocation) {
        return switch(invocation.step().tool()) {
            case "search" -> tools.search(c, invocation);
            case "source-read" -> tools.sources(c, invocation);
            case "answer-draft" -> tools.draft(c, invocation);
            default -> throw new SecurityException("DOMAIN_TOOL_NOT_ALLOWED");
        };
    }
    @Override public Evaluation evaluate(Checkpoint c) {
        Evaluation evaluation=tools.evaluate(c);
        return evaluation;
    }
}
