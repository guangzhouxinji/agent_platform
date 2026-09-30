package cn.xinji.agentplatform.consultation.taskcore;

import java.time.Instant;
import java.util.List;

public interface DevTaskCoreStore {
    record Actor(String principal, String organization, long authorizationVersion, Instant credentialUntil) { }
    record CommandResult(String receiptId, String taskId, long revision, Instant expiresAt) { }
    record Message(long sequence, long revision, String body, Instant createdAt) { }
    record Correction(long oldRevision, long newRevision, String utterance, Instant createdAt) { }
    record Checkpoint(String attemptId, long sequence, long revision, String committedRef, Instant createdAt) { }
    record TaskView(String taskId, String state, long revision, long cancelEpoch, Instant expiresAt,
                    List<Message> messages, List<Checkpoint> checkpoints, List<Correction> corrections) { }
    record Lease(String attemptId, String taskId, long fencingToken, Instant leaseUntil) { }
    record ReconcileResult(int removed, int failed) { }
    enum ServiceRole { AGENT, JOB }

    interface Guard {
        boolean humanCurrent(Actor human, Instant now);
        boolean inputAllowed(Actor human, String kind, String text, Instant now);
        boolean inputDisplayAllowed(Actor human, String kind, String text, Instant now);
        default boolean correctionAllowed(Actor human, String field, String text, Instant now) { return false; }
        boolean delegationCurrent(String owner, String organization, long version,
                                  Instant delegatedUntil, Instant now);
        boolean serviceCurrent(Actor service, ServiceRole role, Instant now);
    }

    CommandResult create(Actor human, String key, String goal, Guard guard);
    CommandResult append(Actor human, String taskId, String key, long expectedVersion,
                         String message, Guard guard);
    TaskView read(Actor human, String taskId, Guard guard);
    CommandResult cancel(Actor human, String taskId, String key, long expectedVersion, Guard guard);
    Lease lease(Actor agent, String attemptId, Guard guard);
    Checkpoint checkpoint(Actor agent, String attemptId, long token, String committedRef, Guard guard);
    ReconcileResult reconcile(Actor job, Guard guard);
}
