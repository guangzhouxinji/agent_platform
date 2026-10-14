package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;

/** Implementations belong to the business-data service or independently deployed domain Agent. */
public final class AgentPorts {
    private AgentPorts() { }
    public interface CheckpointStore {
        Checkpoint load(String runId);
        /** Atomic CAS + immutable checkpoint revision + audit event; expectedVersion=-1 is create-only.
         * The production adapter must retain domain fencing and transaction-time current authorization.
         */
        void commit(long expectedVersion, Checkpoint next, AgentEvent event);
    }
    public record AgentEvent(String runId, long version, Phase phase, String invocationId, StopReason stopReason) { }
    public interface Skills {
        Observation observe(Checkpoint checkpoint);
        Plan plan(Checkpoint checkpoint);
        /** Must be idempotent for invocation.id; must retain domain fence/transaction checks. */
        Observation invoke(Checkpoint checkpoint, Invocation invocation);
    }
    public interface Authorization {
        /** Current identity, Task scope and Source rights; never trust historical snapshots. */
        boolean permits(Checkpoint checkpoint, Step step);
    }
    public interface CompletionEvaluator {
        /** Evaluate the goal's machine contract; model/planner opinions cannot satisfy it. */
        Evaluation evaluate(Checkpoint checkpoint);
    }
    public static final class ToolFailure extends RuntimeException {
        private final StopReason reason;
        private final boolean replannable;
        public ToolFailure(StopReason reason) { this(reason, false); }
        public ToolFailure(StopReason reason, boolean replannable) {
            super(reason.name());
            if (replannable && reason != StopReason.SOURCE_UNAVAILABLE && reason != StopReason.INSUFFICIENT_EVIDENCE)
                throw new IllegalArgumentException("UNSAFE_TOOL_RETRY");
            this.replannable = replannable;
            if (reason == StopReason.COMPLETED || reason == StopReason.BUDGET_EXCEEDED)
                throw new IllegalArgumentException("INVALID_TOOL_FAILURE");
            this.reason = reason;
        }
        public StopReason reason() { return reason; }
        public boolean replannable() { return replannable; }
    }
}
