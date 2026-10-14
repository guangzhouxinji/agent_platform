package cn.xinji.agentplatform.adjudication.agent;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed engineering task contracts with explicit test ports; NOT real-data Golden or professional acceptance. */
class AdjudicationAgentTaskContractTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
    final List<String> allowed = List.of("material-organize", "issue-law-match", "patent-status", "element-evidence", "report-draft");
    final Task task = new Task("task", AdjudicationAgent.TASK_TYPE, "organization/task",
            new Goal("goal", "source-v1", "existing approved domain task", AdjudicationAgent.COMPLETION_CONTRACT));

    @Test void onlyOwnApprovedToolsAreInThePlan() {
        var f = new Fixture(); f.start(); f.advance(); var c = f.advance();
        assertEquals(allowed, c.plan().steps().stream().map(Step::tool).toList());
        assertTrue(c.plan().steps().stream().allMatch(AdjudicationAgent.DEFINITION::permits));
        assertFalse(AdjudicationAgent.DEFINITION.permits(new Step("x", "ForeignAgent", "official-write", "other-task")));
        assertEquals(0, f.effects.size());
    }
    @Test void completionRequiresEvaluationAndDomainHumanBoundary() {
        var f = new Fixture(); f.start();
        while (f.store.value.phase() != Phase.EVALUATE) f.advance();
        assertNull(f.store.value.outcome()); assertEquals(allowed.size(), f.effects.size());
        var end = f.advance(); assertEquals(StopReason.NEED_HUMAN, end.outcome().reason());
        assertEquals(end, f.advance()); assertEquals(allowed.size(), f.effects.size());
    }
    @Test void missingEvidenceNeverReportsGoalCompleted() {
        var f = new Fixture(); f.verdict = Verdict.INSUFFICIENT_EVIDENCE;
        assertEquals(StopReason.INSUFFICIENT_EVIDENCE, f.run().outcome().reason());
    }
    @Test void humanGateIsNotAutomaticApproval() {
        var f = new Fixture(); f.verdict = Verdict.NEED_HUMAN;
        assertEquals(StopReason.NEED_HUMAN, f.run().outcome().reason());
        assertFalse(f.effects.values().stream().anyMatch(tool -> tool.contains("review") || tool.contains("publish") || tool.contains("official")));
    }
    @Test void wrongGoalContractAndForeignTaskTypeCannotCreateRuns() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.agent.start("run",
                new Task("task", AdjudicationAgent.TASK_TYPE, "scope", new Goal("g", "1", "goal", "foreign-contract")), f.budget));
        assertThrows(IllegalArgumentException.class, () -> f.agent.start("run",
                new Task("task", "FOREIGN_TASK", "scope", task.goal()), f.budget));
        assertNull(f.store.value);
    }
    @Test void unauthorizedToolCannotBeDispatchedDirectly() {
        var f = new Fixture(); f.start();
        assertThrows(SecurityException.class, () -> f.agent.invoke(f.store.value,
                new Invocation("foreign", new Step("x", "Foreign", "official-write", "other-task"))));
        assertTrue(f.effects.isEmpty());
    }
    @Test void currentRevocationAfterReservationStopsBeforeAnyTool() {
        var f = new Fixture(); f.toPending(); f.authorized = false;
        assertEquals(StopReason.PERMISSION_BLOCKED, f.advance().outcome().reason());
        assertTrue(f.effects.isEmpty());
    }
    @Test void invocationBudgetStopsBeforeExceedingAllowedActions() {
        var f = new Fixture(); f.budget = new Budget(1, 1, Duration.ofMinutes(1));
        assertEquals(StopReason.BUDGET_EXCEEDED, f.run().outcome().reason());
        assertEquals(1, f.effects.size());
    }
    @Test void recoverableFailureCannotLoopOrBecomeFalseCompletion() {
        var f = new Fixture(); f.failTool = true;
        assertEquals(StopReason.BUDGET_EXCEEDED, f.run().outcome().reason());
        assertEquals(1, f.store.value.replans()); assertTrue(f.effects.isEmpty());
        assertTrue(f.store.value.observations().stream().anyMatch(o -> o.reference().startsWith("runtime-failure/")));
    }
    @Test void checkpointFailureReplaysExactInvocationAfterAgentRecreation() {
        var f = new Fixture(); f.toPending(); String invocation = f.store.value.pending().id();
        f.store.reject = true; assertThrows(IllegalStateException.class, f::advance);
        assertEquals(invocation, f.store.value.pending().id()); assertEquals(1, f.effects.size());
        f.recreate(); f.advance(); assertNull(f.store.value.pending()); assertEquals(1, f.effects.size());
        assertEquals(2, f.calls.get(invocation));
    }
    final class Fixture {
        final Store store = new Store(); final Map<String,String> effects = new LinkedHashMap<>();
        final Map<String,Integer> calls = new HashMap<>();
        boolean authorized = true, failTool; Verdict verdict = Verdict.SATISFIED;
        Budget budget = new Budget(20, 1, Duration.ofMinutes(1)); AdjudicationAgent agent;
        final AdjudicationAgent.Tools tools = mock(AdjudicationAgent.Tools.class, call -> {
            if (call.getMethod().getName().equals("evaluate")) {
                var c = (Checkpoint) call.getArgument(0);
                return new Evaluation(c.observations().getLast().reference().startsWith("runtime-failure/") ? Verdict.REPLAN : verdict, "domain-outcome");
            }
            if (call.getArguments().length == 1) return new Observation("input/source-v1", "a".repeat(64));
            Invocation invocation = call.getArgument(1);
            calls.merge(invocation.id(), 1, Integer::sum);
            if (failTool) throw new ToolFailure(StopReason.SOURCE_UNAVAILABLE, true);
            effects.putIfAbsent(invocation.id(), invocation.step().tool());
            return new Observation("result/" + invocation.id(), "b".repeat(64));
        });
        Fixture() { recreate(); }
        void recreate() { agent = new AdjudicationAgent(store, tools, (c,s) -> authorized, clock); }
        void start() { agent.start("run", task, budget); }
        Checkpoint advance() { return agent.advance("run"); }
        void toPending() { start(); advance(); advance(); advance(); assertNotNull(store.value.pending()); }
        Checkpoint run() { start(); for(int i=0;i<100 && store.value.phase()!=Phase.STOP;i++) advance(); assertEquals(Phase.STOP, store.value.phase()); return store.value; }
    }
    static final class Store implements CheckpointStore {
        Checkpoint value; boolean reject;
        public Checkpoint load(String id) { return value; }
        public void commit(long expected, Checkpoint next, AgentEvent event) {
            if (reject) { reject=false; throw new IllegalStateException("injected persistence failure"); }
            assertEquals(expected, value==null ? -1 : value.version()); value=next;
        }
    }
}
