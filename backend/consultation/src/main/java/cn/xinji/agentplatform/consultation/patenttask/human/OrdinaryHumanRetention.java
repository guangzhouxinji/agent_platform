package cn.xinji.agentplatform.consultation.patenttask.human;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;

/** V3 time bounds. The application must authenticate each policy and source bound. */
public final class OrdinaryHumanRetention {
    private static final Duration BODY = Duration.ofDays(180);
    private static final Duration AUDIT = Duration.ofDays(365);
    private static final Duration BACKUP = Duration.ofDays(30);

    public record Policy(String reference, long version, String evidenceSha256) {
        public Policy {
            if (reference == null || reference.isBlank() || version < 1
                    || evidenceSha256 == null || !evidenceSha256.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("An accountable ordinary-human retention policy is required");
        }
    }

    public record Bounds(Instant taskWriteUntil, Instant taskSaveUntil, Instant sourceUseUntil,
                         Instant sourceSaveUntil, Instant auditSaveUntil, Instant backupSaveUntil) {
        public Bounds {
            Objects.requireNonNull(taskWriteUntil); Objects.requireNonNull(taskSaveUntil);
            Objects.requireNonNull(sourceUseUntil); Objects.requireNonNull(sourceSaveUntil);
            Objects.requireNonNull(auditSaveUntil); Objects.requireNonNull(backupSaveUntil);
        }
    }

    private final Policy policy;

    public OrdinaryHumanRetention(Policy policy) { this.policy = Objects.requireNonNull(policy); }

    public Policy policy() { return policy; }

    public Instant bodyUntil(Instant lastCommittedChange, Instant endedAt, Bounds bounds) {
        Objects.requireNonNull(lastCommittedChange); Objects.requireNonNull(bounds);
        if (endedAt != null && endedAt.isAfter(lastCommittedChange))
            throw new IllegalArgumentException("Termination cannot be after the last committed change");
        Instant anchor = endedAt == null ? lastCommittedChange : endedAt;
        return earliest(anchor.plus(BODY), bounds.taskSaveUntil(), bounds.sourceSaveUntil());
    }

    public Instant auditUntil(Instant submittedAt, Bounds bounds) {
        return earliest(Objects.requireNonNull(submittedAt).plus(AUDIT),
                bounds.taskSaveUntil(), bounds.auditSaveUntil());
    }

    public void requireReceiptCompatibility(Instant submittedAt, Bounds bounds) {
        if (!bounds.taskWriteUntil().isAfter(submittedAt)
                || bounds.taskWriteUntil().isAfter(auditUntil(submittedAt, bounds)))
            throw new IllegalArgumentException("Task command lifetime exceeds its permitted receipt retention");
    }

    public void requireWritable(Instant now, Instant lastCommittedChange, Instant endedAt, Bounds bounds) {
        if (endedAt != null) throw new IllegalStateException("This human handling has ended");
        requireReadable(now, lastCommittedChange, null, bounds);
        requireReceiptCompatibility(now, bounds);
    }

    public void requireReadable(Instant now, Instant lastCommittedChange, Instant endedAt, Bounds bounds) {
        Objects.requireNonNull(now);
        if (lastCommittedChange.isAfter(now) || !now.isBefore(earliest(
                bodyUntil(lastCommittedChange, endedAt, bounds),
                bounds.taskWriteUntil(), bounds.sourceUseUntil())))
            throw new IllegalStateException("Human content is expired or its time is unverifiable");
    }

    /** Every still-stored row participates, including historical audit rows. No snapshot filtering. */
    public Instant backupUntil(Instant createdAt, Collection<Instant> storedObjectSaveUntil, Bounds bounds) {
        Objects.requireNonNull(createdAt); Objects.requireNonNull(storedObjectSaveUntil);
        if (storedObjectSaveUntil.isEmpty())
            throw new IllegalArgumentException("The native snapshot needs complete object retention coverage");
        Instant until = earliest(createdAt.plus(BACKUP), bounds.backupSaveUntil());
        for (Instant retainedUntil : storedObjectSaveUntil) until = earliest(until, retainedUntil);
        if (!until.isAfter(createdAt))
            throw new IllegalStateException("A still-stored object has no valid physical backup coverage");
        return until;
    }

    public void requireBackupCurrent(Instant now, Instant createdAt, Instant expiresAt) {
        if (createdAt.isAfter(now) || !expiresAt.isAfter(createdAt)
                || expiresAt.isAfter(createdAt.plus(BACKUP)) || !now.isBefore(expiresAt))
            throw new IllegalStateException("The human backup window is invalid or expired");
    }

    private static Instant earliest(Instant... values) {
        Instant result = Objects.requireNonNull(values[0]);
        for (Instant value : values) if (Objects.requireNonNull(value).isBefore(result)) result = value;
        return result;
    }
}
