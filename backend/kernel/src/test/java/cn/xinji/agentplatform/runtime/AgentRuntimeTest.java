package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Component tests with real file IO, not product-domain Golden Task acceptance. */
class AgentRuntimeTest {
    @TempDir Path directory;
    final Instant now = Instant.parse("2026-10-05T00:00:00Z");
    final Step step = new Step("read", "organize", "file-read", "source/v1");
    final Definition definition = new Definition("independent-agent", "v1", Set.of("organize"),
            Map.of("organize", Set.of("file-read")), "prompt/v1", "policy/v1", "eval/v1");
    final Task task = new Task("task-1", "organize", "scope-1", new Goal("goal", "1", "read authorized file", "sha-bound/v1"));
    final Budget budget = new Budget(3, 1, Duration.ofMinutes(1));

    @Test void completesOnlyAfterMachineEvaluationAndPersistsAudit() throws Exception {
        var f = new Fixture(); var c = f.run();
        assertEquals(StopReason.COMPLETED, c.outcome().reason());
        assertEquals(2, c.observations().size()); assertEquals(1, f.executions);
        assertEquals(c, new DiskStore().load("run"));
        assertEquals(c.version(), f.store.read().event().version());
        assertEquals(StopReason.COMPLETED, f.store.read().event().stopReason());
        assertEquals(c, f.runtime.advance("run")); assertEquals(1, f.executions);
    }
    @Test void reservesInvocationBeforeSideEffectAndRestartsWithSameKey() throws Exception {
        var f = new Fixture(); f.toPending();
        assertEquals(0, f.executions); String key = f.store.load("run").pending().id();
        f.runtime = f.runtime(new DiskStore()); f.runtime.advance("run");
        assertEquals(key, f.lastInvocation); assertEquals(1, f.executions);
        assertEquals(key, f.store.read().event().invocationId());
    }
    @Test void resultCommitFailureReplaysIdempotentToolWithoutDuplicateEffect() throws Exception {
        var f = new Fixture(); f.toPending(); f.store.failNext = true;
        assertThrows(IllegalStateException.class, () -> f.runtime.advance("run"));
        assertNotNull(f.store.load("run").pending()); assertEquals(1, f.executions);
        f.runtime = f.runtime(new DiskStore()); f.runtime.advance("run");
        assertEquals(1, f.executions); assertNull(f.store.load("run").pending());
    }
    @Test void failedReservationNeverCallsTool() throws Exception {
        var f = new Fixture(); f.start(); f.runtime.advance("run"); f.runtime.advance("run");
        f.store.failNext = true; assertThrows(IllegalStateException.class, () -> f.runtime.advance("run"));
        assertEquals(0, f.executions); assertEquals(0, f.store.load("run").invocations());
    }
    @Test void duplicateCreateAndStaleCasCannotOverwriteRun() throws Exception {
        var f = new Fixture(); var initial = f.start();
        assertThrows(IllegalStateException.class, () -> f.start());
        var next = f.runtime.advance("run");
        assertThrows(IllegalStateException.class, () -> f.store.commit(initial.version(), initial,
                new AgentEvent("run", 0, Phase.OBSERVE, null, null)));
        assertEquals(next, f.store.load("run"));
    }
    @Test void currentAuthorizationIsRequiredEvenWithHistoricalCheckpoint() throws Exception {
        var f = new Fixture(); f.toPending(); f.allowed = false;
        assertEquals(StopReason.PERMISSION_BLOCKED, f.runtime.advance("run").outcome().reason());
        assertEquals(0, f.executions);
    }
    @Test void revocationDuringToolDiscardsResult() throws Exception {
        var f = new Fixture(); f.toPending(); f.revokeDuringTool = true;
        var c = f.runtime.advance("run"); assertEquals(StopReason.PERMISSION_BLOCKED, c.outcome().reason());
        assertEquals(1, c.observations().size());
    }
    @Test void allowedSkillCannotInvokeOtherAgentsTool() throws Exception {
        var f = new Fixture(); f.plan = new Plan("bad", List.of(new Step("x", "organize", "official-write", "input")));
        assertEquals(StopReason.PERMISSION_BLOCKED, f.run().outcome().reason()); assertEquals(0, f.executions);
    }
    @Test void permittedToolUnderWrongSkillIsRejected() throws Exception {
        var f = new Fixture(); f.plan = new Plan("bad", List.of(new Step("x", "foreign-skill", "file-read", "input")));
        assertEquals(StopReason.PERMISSION_BLOCKED, f.run().outcome().reason());
    }
    @Test void machineEvaluatorCanRequireHumanInsteadOfCompletion() throws Exception {
        var f = new Fixture(); f.verdict = Verdict.NEED_HUMAN;
        assertEquals(StopReason.NEED_HUMAN, f.run().outcome().reason());
    }
    @Test void missingEvidenceNeverCompletes() throws Exception {
        var f = new Fixture(); f.verdict = Verdict.INSUFFICIENT_EVIDENCE;
        assertEquals(StopReason.INSUFFICIENT_EVIDENCE, f.run().outcome().reason());
    }
    @Test void replanCanCompleteAndKeepsPriorEvidenceAndUniqueInvocation() throws Exception {
        var f = new Fixture(); f.replanOnce = true; var c = f.run();
        assertEquals(StopReason.COMPLETED, c.outcome().reason()); assertEquals(1, c.replans());
        assertEquals(2, c.invocations()); assertEquals(3, c.observations().size()); assertEquals(2, f.executions);
    }
    @Test void repeatedReplanStopsAtBudget() throws Exception {
        var f = new Fixture(); f.verdict = Verdict.REPLAN;
        assertEquals(StopReason.BUDGET_EXCEEDED, f.run().outcome().reason()); assertEquals(2, f.executions);
    }
    @Test void invocationBudgetStopsBeforeNextSideEffect() throws Exception {
        var f = new Fixture(); f.limit = new Budget(1, 2, Duration.ofMinutes(1)); f.verdict = Verdict.REPLAN;
        var c = f.run(); assertEquals(StopReason.BUDGET_EXCEEDED, c.outcome().reason()); assertEquals(1, f.executions);
    }
    @Test void elapsedRuntimeStopsBeforeInvocation() throws Exception {
        var f = new Fixture(); f.toPending(); f.clock.instant = now.plusSeconds(60);
        assertEquals(StopReason.BUDGET_EXCEEDED, f.runtime.advance("run").outcome().reason()); assertEquals(0, f.executions);
    }
    @Test void revocationDuringObservationDiscardsNewEvidence() throws Exception {
        var f = new Fixture(); f.start(); f.revokeDuringObserve = true;
        var c = f.runtime.advance("run");
        assertEquals(StopReason.PERMISSION_BLOCKED, c.outcome().reason()); assertTrue(c.observations().isEmpty());
    }
    @Test void completionCannotPublishAfterAuthorizationChanges() throws Exception {
        var f = new Fixture(); f.toPending(); f.runtime.advance("run"); f.revokeDuringEvaluation = true;
        assertEquals(StopReason.PERMISSION_BLOCKED, f.runtime.advance("run").outcome().reason());
    }
    @Test void completionCannotPublishAfterEvaluationExceedsTimeBudget() throws Exception {
        var f = new Fixture(); f.toPending(); f.runtime.advance("run"); f.timeoutDuringEvaluation = true;
        assertEquals(StopReason.BUDGET_EXCEEDED, f.runtime.advance("run").outcome().reason());
    }
    @Test void definitionVersionMismatchFailsClosedWithoutUpdatingHistory() throws Exception {
        var f = new Fixture(); var c = f.start();
        var other = new Definition("other-agent", "v1", definition.taskTypes(), definition.skillTools(), "p", "p", "e");
        var runtime = new AgentRuntime(other, f.store, f.skills, (x,s) -> true, x -> new Evaluation(Verdict.SATISFIED,"x"), f.clock);
        assertThrows(IllegalStateException.class, () -> runtime.advance("run")); assertEquals(c, f.store.load("run"));
    }
    @ParameterizedTest @EnumSource(value = StopReason.class, names = {"SOURCE_UNAVAILABLE", "NEED_HUMAN", "INSUFFICIENT_EVIDENCE", "PERMISSION_BLOCKED", "ERROR"})
    void classifiedToolFailuresArePreserved(StopReason reason) throws Exception {
        var f = new Fixture(); f.failure = new ToolFailure(reason);
        assertEquals(reason, f.run().outcome().reason());
    }
    @Test void unknownFailureIsErrorWithoutLeakingExceptionMessage() throws Exception {
        var f = new Fixture(); f.failure = new IllegalStateException("secret-token");
        var c = f.run(); assertEquals(StopReason.ERROR, c.outcome().reason());
        assertFalse(Files.readString(directory.resolve("checkpoint.json")).contains("secret-token"));
    }
    @Test void crossRunInvocationKeyAndImpossiblePositionsAreRejected() {
        var plan = new Plan("v1", List.of(step));
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint("run", definition, task,
                budget, now, 3, Phase.ACT, plan, 0, 1, 0, List.of(), new Invocation("other-run/1", step), null));
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint("run", definition, task,
                budget, now, 3, Phase.ACT, plan, 1, 1, 0, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint("run", definition, task,
                budget, now, 3, Phase.EVALUATE, plan, 0, 1, 0, List.of(), null, null));
    }
    @Test void immutableContractsRejectMalformedInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Budget(0, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new Plan("v1", List.of(step, step)));
        assertThrows(IllegalArgumentException.class, () -> new Observation("source", "fabricated"));
        assertThrows(UnsupportedOperationException.class, () -> definition.skillTools().get("organize").add("write"));
        assertThrows(IllegalArgumentException.class, () -> new Checkpoint("run", definition,
                new Task("x", "foreign-type", "scope", task.goal()), budget, now, 0, Phase.OBSERVE,
                null, 0, 0, 0, List.of(), null, null));
    }

    @Test void recoverableFailurePersistsFailedCursorThenReplansAcrossRestart() throws Exception {
        var f = new Fixture(); f.toPending();
        f.failure = new ToolFailure(StopReason.SOURCE_UNAVAILABLE, true);
        var failed = f.runtime.advance("run");
        assertEquals(Phase.EVALUATE, failed.phase()); assertEquals(0, failed.cursor());
        assertNull(failed.pending()); assertEquals(1, failed.invocations());
        assertTrue(failed.observations().getLast().reference().startsWith("runtime-failure/SOURCE_UNAVAILABLE/"));
        assertEquals("run/1", f.store.read().event().invocationId());
        f.runtime = f.runtime(new DiskStore());
        // Even a SATISFIED evaluator cannot turn a failed tool into completed work.
        assertEquals(Phase.REPLAN, f.runtime.advance("run").phase());
        f.failure = null;
        var c = f.runtime.advance("run");
        for (int i=0;i<10 && c.phase()!=Phase.STOP;i++) c=f.runtime.advance("run");
        assertEquals(StopReason.COMPLETED, c.outcome().reason());
        assertEquals(1, c.replans()); assertEquals(2, c.invocations()); assertEquals(1, f.executions);
        assertEquals("run/2", f.lastInvocation);
    }
    @Test void repeatedRecoverableFailuresStopAtReplanBudgetWithoutSuccess() throws Exception {
        var f = new Fixture(); f.failure = new ToolFailure(StopReason.INSUFFICIENT_EVIDENCE,true);
        var c=f.run(); assertEquals(StopReason.BUDGET_EXCEEDED,c.outcome().reason());
        assertEquals(0,c.cursor()); assertEquals(0,f.executions);
    }
    @Test void permissionFailureCannotBeDeclaredRetryable() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new ToolFailure(StopReason.PERMISSION_BLOCKED,true));
        var f=new Fixture();f.failure=new SecurityException("current source revoked");
        assertEquals(StopReason.PERMISSION_BLOCKED,f.run().outcome().reason());assertEquals(0,f.executions);
    }

    class Fixture {
        DiskStore store = new DiskStore(); MutableClock clock = new MutableClock();
        Plan plan = new Plan("plan/v1", List.of(step)); Budget limit = budget;
        Verdict verdict = Verdict.SATISFIED; boolean allowed = true, revokeDuringTool, replanOnce, revokeDuringObserve, revokeDuringEvaluation, timeoutDuringEvaluation;
        int executions; String lastInvocation; RuntimeException failure;
        final Skills skills = new Skills() {
            public Observation observe(Checkpoint c) {
                if (revokeDuringObserve) allowed = false;
                return evidence(directory.resolve("input.txt"));
            }
            public Plan plan(Checkpoint c) { return plan; }
            public Observation invoke(Checkpoint c, Invocation invocation) {
                if (failure != null) throw failure;
                lastInvocation = invocation.id();
                Path result = directory.resolve("result-" + c.invocations() + ".txt");
                try {
                    if (!Files.exists(result)) {
                        Files.writeString(result, Files.readString(directory.resolve("input.txt")), StandardOpenOption.CREATE_NEW);
                        executions++;
                    }
                } catch (Exception e) { throw new IllegalStateException(e); }
                if (revokeDuringTool) allowed = false;
                return evidence(result);
            }
        };
        AgentRuntime runtime = runtime(store);
        Fixture() throws Exception { Files.writeString(directory.resolve("input.txt"), "actual file tool input"); }
        AgentRuntime runtime(DiskStore disk) {
            return new AgentRuntime(definition, disk, skills, (c,s) -> allowed, c -> {
                if (revokeDuringEvaluation) allowed = false;
                if (timeoutDuringEvaluation) clock.instant = now.plusSeconds(60);
                return new Evaluation(replanOnce && c.replans() == 0 ? Verdict.REPLAN : verdict, "evaluation/v1");
            }, clock);
        }
        Checkpoint start() { return runtime.start("run", task, limit); }
        void toPending() { start(); for (int i=0;i<3;i++) runtime.advance("run"); }
        Checkpoint run() {
            var c = start(); for (int i=0;i<30 && c.phase()!=Phase.STOP;i++) c=runtime.advance("run");
            assertEquals(Phase.STOP, c.phase()); return c;
        }
    }
    Observation evidence(Path file) {
        try { return new Observation(file.toString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    record Persisted(Checkpoint checkpoint, AgentEvent event) { }
    /** Test-only disk adapter: checkpoint/event share one atomic file; not the production DM8 port. */
    class DiskStore implements CheckpointStore {
        final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()); boolean failNext;
        Persisted read() {
            try { return json.readValue(directory.resolve("checkpoint.json").toFile(), Persisted.class); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        public Checkpoint load(String id) {
            if (!Files.exists(directory.resolve("checkpoint.json"))) return null;
            Checkpoint c = read().checkpoint(); return c.runId().equals(id) ? c : null;
        }
        public void commit(long expected, Checkpoint next, AgentEvent event) {
            synchronized (AgentRuntimeTest.this) {
                if (failNext) { failNext=false; throw new IllegalStateException("DISK_WRITE_FAILED"); }
                var current = load(next.runId());
                if ((current == null ? -1 : current.version()) != expected) throw new IllegalStateException("STALE_CHECKPOINT");
                try {
                    var temp = directory.resolve("checkpoint.tmp");
                    json.writeValue(temp.toFile(), new Persisted(next, event));
                    Files.move(temp, directory.resolve("checkpoint.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
        }
    }
    class MutableClock extends Clock {
        Instant instant = now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
