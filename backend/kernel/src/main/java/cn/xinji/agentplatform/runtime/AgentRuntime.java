package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One durable transition per advance; no domain ownership, model provider or permission writes. */
public final class AgentRuntime {
    private final Definition definition;
    private final CheckpointStore store;
    private final Skills skills;
    private final Authorization authorization;
    private final CompletionEvaluator completion;
    private final Clock clock;

    public AgentRuntime(Definition definition, CheckpointStore store, Skills skills,
            Authorization authorization, CompletionEvaluator completion, Clock clock) {
        this.definition = Objects.requireNonNull(definition); this.store = Objects.requireNonNull(store);
        this.skills = Objects.requireNonNull(skills); this.authorization = Objects.requireNonNull(authorization);
        this.completion = Objects.requireNonNull(completion); this.clock = Objects.requireNonNull(clock);
    }
    public Checkpoint start(String runId, Task task, Budget budget) {
        var initial = new Checkpoint(runId, definition, task, budget, clock.instant(), 0,
                Phase.OBSERVE, null, 0, 0, 0, List.of(), null, null);
        return commit(-1, initial);
    }
    public Checkpoint advance(String runId) {
        var current = Objects.requireNonNull(store.load(runId), "RUN_NOT_FOUND");
        if (!definition.equals(current.definition())) throw new IllegalStateException("DEFINITION_VERSION_MISMATCH");
        if (current.phase() == Phase.STOP) return current;
        Checkpoint next;
        try {
            if (!authorization.permits(current, null)) next = stop(current, StopReason.PERMISSION_BLOCKED, "current-access");
            else if (!clock.instant().isBefore(current.startedAt().plus(current.budget().maxRuntime())))
                next = stop(current, StopReason.BUDGET_EXCEEDED, "runtime-budget");
            else {
                next = transition(current);
                // Observe/Plan/Evaluate may also take time or overlap with authorization revocation.
                if (next.outcome() == null || next.outcome().reason() == StopReason.COMPLETED
                        || next.outcome().reason() == StopReason.NEED_HUMAN) {
                    if (!authorization.permits(current, null))
                        next = stop(current, StopReason.PERMISSION_BLOCKED, "access-changed");
                    else if (!clock.instant().isBefore(current.startedAt().plus(current.budget().maxRuntime())))
                        next = stop(current, StopReason.BUDGET_EXCEEDED, "runtime-budget");
                }
            }
        } catch (ToolFailure failure) {
            if (failure.replannable() && current.pending() != null) {
                String ref = "runtime-failure/" + failure.reason().name() + "/" + current.pending().id();
                String sha;
                try { sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
                catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                next = copy(current, Phase.EVALUATE, current.plan(), current.cursor(),
                        current.invocations(), current.replans(), append(current.observations(),new Observation(ref,sha)),null,null);
            } else next = stop(current, failure.reason(), "tool-failure");
        } catch (SecurityException denied) {
            next = stop(current, StopReason.PERMISSION_BLOCKED, "current-tool-permission");
        } catch (RuntimeException failure) {
            // Persistence exceptions are outside this block and must not become false successful commits.
            next = stop(current, StopReason.ERROR, "execution-error");
        }
        return commit(current.version(), next, current.pending() == null ? null : current.pending().id());
    }
    private Checkpoint transition(Checkpoint c) {
        return switch (c.phase()) {
            case OBSERVE -> copy(c, Phase.PLAN, null, 0, c.invocations(), c.replans(),
                    append(c.observations(), Objects.requireNonNull(skills.observe(c))), null, null);
            case PLAN, REPLAN -> {
                Plan plan = Objects.requireNonNull(skills.plan(c));
                if (plan.steps().stream().anyMatch(step -> !definition.permits(step)))
                    yield stop(c, StopReason.PERMISSION_BLOCKED, "tool-not-allowed");
                yield copy(c, Phase.ACT, plan, 0, c.invocations(), c.replans(), c.observations(), null, null);
            }
            case ACT -> act(c);
            case EVALUATE -> evaluate(c);
            case STOP -> c;
        };
    }
    private Checkpoint act(Checkpoint c) {
        Step step = c.plan().steps().get(c.cursor());
        if (!authorization.permits(c, step)) return stop(c, StopReason.PERMISSION_BLOCKED, "tool-access");
        if (c.pending() == null) {
            if (c.invocations() >= c.budget().maxInvocations()) return stop(c, StopReason.BUDGET_EXCEEDED, "invocation-budget");
            // Durable reservation occurs before external side effects; replay always uses this exact key.
            var invocation = new Invocation(c.runId() + "/" + (c.invocations() + 1), step);
            return copy(c, Phase.ACT, c.plan(), c.cursor(), c.invocations() + 1, c.replans(),
                    c.observations(), invocation, null);
        }
        Observation result = Objects.requireNonNull(skills.invoke(c, c.pending()));
        if (!authorization.permits(c, step)) return stop(c, StopReason.PERMISSION_BLOCKED, "access-changed");
        if (!clock.instant().isBefore(c.startedAt().plus(c.budget().maxRuntime())))
            return stop(c, StopReason.BUDGET_EXCEEDED, "runtime-budget");
        int cursor = c.cursor() + 1;
        return copy(c, cursor == c.plan().steps().size() ? Phase.EVALUATE : Phase.ACT,
                c.plan(), cursor, c.invocations(), c.replans(), append(c.observations(), result), null, null);
    }
    private Checkpoint evaluate(Checkpoint c) {
        Evaluation evaluation = Objects.requireNonNull(completion.evaluate(c));
        boolean failed = !c.observations().isEmpty() && c.observations().getLast().reference().startsWith("runtime-failure/");
        if (failed && evaluation.verdict() == Verdict.SATISFIED)
            evaluation = new Evaluation(Verdict.REPLAN,"recoverable-tool-failure");
        return switch (evaluation.verdict()) {
            case SATISFIED -> stop(c, StopReason.COMPLETED, evaluation.outcomeRef());
            case NEED_HUMAN -> stop(c, StopReason.NEED_HUMAN, evaluation.outcomeRef());
            case INSUFFICIENT_EVIDENCE -> stop(c, StopReason.INSUFFICIENT_EVIDENCE, evaluation.outcomeRef());
            case REPLAN -> c.replans() >= c.budget().maxReplans()
                    ? stop(c, StopReason.BUDGET_EXCEEDED, "replan-budget")
                    : copy(c, Phase.REPLAN, c.plan(), c.cursor(), c.invocations(), c.replans() + 1,
                            c.observations(), null, null);
        };
    }
    private Checkpoint stop(Checkpoint c, StopReason reason, String ref) {
        return copy(c, Phase.STOP, c.plan(), c.cursor(), c.invocations(), c.replans(),
                c.observations(), null, new Outcome(reason, ref));
    }
    private Checkpoint copy(Checkpoint c, Phase phase, Plan plan, int cursor, int invocations,
            int replans, List<Observation> observations, Invocation pending, Outcome outcome) {
        return new Checkpoint(c.runId(), c.definition(), c.task(), c.budget(), c.startedAt(),
                c.version() + 1, phase, plan, cursor, invocations, replans, observations, pending, outcome);
    }
    private Checkpoint commit(long expected, Checkpoint next) { return commit(expected, next, null); }
    private Checkpoint commit(long expected, Checkpoint next, String priorInvocation) {
        String invocationId = next.pending() == null ? priorInvocation : next.pending().id();
        store.commit(expected, next, new AgentEvent(next.runId(), next.version(), next.phase(),
                invocationId, next.outcome() == null ? null : next.outcome().reason()));
        return next;
    }
    private List<Observation> append(List<Observation> values, Observation value) {
        var copy = new ArrayList<>(values); copy.add(value); return List.copyOf(copy);
    }
}
