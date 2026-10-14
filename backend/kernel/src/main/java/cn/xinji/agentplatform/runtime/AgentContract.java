package cn.xinji.agentplatform.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mechanical execution contracts; domain policy and business state stay outside the kernel. */
public final class AgentContract {
    private AgentContract() { }
    public enum Phase { OBSERVE, PLAN, ACT, EVALUATE, REPLAN, STOP }
    public enum StopReason { COMPLETED, NEED_HUMAN, INSUFFICIENT_EVIDENCE, PERMISSION_BLOCKED,
        SOURCE_UNAVAILABLE, BUDGET_EXCEEDED, ERROR }
    public enum Verdict { SATISFIED, REPLAN, NEED_HUMAN, INSUFFICIENT_EVIDENCE }
    public record Definition(String agentId, String version, Set<String> taskTypes,
            Map<String, Set<String>> skillTools, String promptVersion, String policyVersion,
            String evaluationVersion) {
        public Definition {
            text(agentId); text(version); text(promptVersion); text(policyVersion); text(evaluationVersion);
            taskTypes = Set.copyOf(taskTypes); skillTools = skillTools.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(e -> text(e.getKey()), e -> Set.copyOf(e.getValue())));
            if (taskTypes.isEmpty() || skillTools.isEmpty()) throw new IllegalArgumentException("EMPTY_DEFINITION");
            taskTypes.forEach(AgentContract::text);
            skillTools.values().forEach(tools -> tools.forEach(AgentContract::text));
        }
        public boolean permits(Step step) {
            return skillTools.getOrDefault(step.skill(), Set.of()).contains(step.tool());
        }
    }
    public record Goal(String id, String version, String description, String completionContract) {
        public Goal { text(id); text(version); text(description); text(completionContract); }
    }
    public record Task(String id, String type, String scope, Goal goal) {
        public Task { text(id); text(type); text(scope); Objects.requireNonNull(goal); }
    }
    public record Budget(int maxInvocations, int maxReplans, Duration maxRuntime) {
        public Budget {
            Objects.requireNonNull(maxRuntime);
            if (maxInvocations < 1 || maxReplans < 0 || maxRuntime.isNegative() || maxRuntime.isZero())
                throw new IllegalArgumentException("INVALID_BUDGET");
        }
    }
    public record Step(String id, String skill, String tool, String inputRef) {
        public Step { text(id); text(skill); text(tool); text(inputRef); }
    }
    public record Plan(String version, List<Step> steps) {
        public Plan {
            text(version); steps = List.copyOf(steps);
            if (steps.isEmpty() || steps.stream().map(Step::id).distinct().count() != steps.size())
                throw new IllegalArgumentException("INVALID_PLAN");
        }
    }
    /** Immutable versioned evidence reference, never an authoritative business decision. */
    public record Observation(String reference, String sha256) {
        public Observation {
            text(reference);
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("INVALID_SHA");
        }
    }
    public record Invocation(String id, Step step) {
        public Invocation { text(id); Objects.requireNonNull(step); }
    }
    public record Evaluation(Verdict verdict, String outcomeRef) {
        public Evaluation { Objects.requireNonNull(verdict); text(outcomeRef); }
    }
    public record Outcome(StopReason reason, String reference) {
        public Outcome { Objects.requireNonNull(reason); text(reference); }
    }
    /** Stored externally; version is used for optimistic concurrency, not current authorization. */
    public record Checkpoint(String runId, Definition definition, Task task, Budget budget, Instant startedAt,
            long version, Phase phase, Plan plan, int cursor, int invocations, int replans,
            List<Observation> observations, Invocation pending, Outcome outcome) {
        public Checkpoint {
            text(runId); Objects.requireNonNull(definition); Objects.requireNonNull(task);
            Objects.requireNonNull(budget); Objects.requireNonNull(startedAt); Objects.requireNonNull(phase);
            observations = List.copyOf(observations);
            if (version < 0 || cursor < 0 || invocations < 0 || replans < 0
                    || !definition.taskTypes().contains(task.type())
                    || (phase == Phase.STOP) != (outcome != null)
                    || (plan == null && cursor != 0) || (plan != null && cursor > plan.steps().size()))
                throw new IllegalArgumentException("INVALID_CHECKPOINT");
            if (plan != null && plan.steps().stream().anyMatch(step -> !definition.permits(step)))
                throw new IllegalArgumentException("PLAN_NOT_ALLOWED");
            if (pending != null && (phase != Phase.ACT || plan == null || cursor >= plan.steps().size()
                    || !pending.step().equals(plan.steps().get(cursor)) || invocations < 1
                    || !pending.id().equals(runId + "/" + invocations)))
                throw new IllegalArgumentException("INVALID_PENDING_INVOCATION");
            if ((phase == Phase.ACT || phase == Phase.EVALUATE) && plan == null)
                throw new IllegalArgumentException("PLAN_REQUIRED");
            if (invocations > budget.maxInvocations() || replans > budget.maxReplans()
                    || (phase == Phase.ACT && cursor >= plan.steps().size())
                    || (phase == Phase.EVALUATE && cursor != plan.steps().size()
                        && (observations.isEmpty() || !observations.getLast().reference().startsWith("runtime-failure/")))
                    || (phase == Phase.REPLAN && replans == 0))
                throw new IllegalArgumentException("INVALID_EXECUTION_POSITION");
        }
    }
    static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("REQUIRED_TEXT");
        return value;
    }
}
