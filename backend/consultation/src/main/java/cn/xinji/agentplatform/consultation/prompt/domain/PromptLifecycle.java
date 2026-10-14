package cn.xinji.agentplatform.consultation.prompt.domain;

import java.time.Instant;
import java.util.Objects;

public record PromptLifecycle(PromptRevision.Reference revision, Status status, long generation,
                              Instant publishedAt, Instant disabledAt, Instant revokedAt) {
    public enum Status { DRAFT, PUBLISHED, DISABLED, ARCHIVED }
    public enum Action { NEW_BINDING, GUIDE_DISPLAY, FIXED_SYSTEM_CALL }
    public record BindingProof(String configurationRevisionRef, PromptRevision.Reference revision,
                               Instant boundAt, Instant validUntil) {
        public BindingProof {
            if (configurationRevisionRef == null || configurationRevisionRef.isBlank())
                throw new IllegalArgumentException("configuration revision");
            Objects.requireNonNull(revision); Objects.requireNonNull(boundAt); Objects.requireNonNull(validUntil);
            if (!boundAt.isBefore(validUntil)) throw new IllegalArgumentException("binding deadline");
        }
    }

    public PromptLifecycle {
        Objects.requireNonNull(revision); Objects.requireNonNull(status);
        if (generation < 0 || status == Status.DRAFT && (publishedAt != null || disabledAt != null || revokedAt != null)
                || status == Status.PUBLISHED && publishedAt == null
                || status == Status.DISABLED && (publishedAt == null || disabledAt == null)
                || disabledAt != null && (publishedAt == null || disabledAt.isBefore(publishedAt))
                || revokedAt != null && (publishedAt == null || revokedAt.isBefore(publishedAt)))
            throw new IllegalArgumentException("lifecycle history");
    }

    public static PromptLifecycle draft(PromptRevision.Reference reference) {
        return new PromptLifecycle(reference, Status.DRAFT, 0, null, null, null);
    }

    public PromptLifecycle publish(long expectedGeneration, Instant at) {
        checkGeneration(expectedGeneration); Objects.requireNonNull(at);
        if (status != Status.DRAFT) throw new PromptFailure("VERSION_CONFLICT");
        return new PromptLifecycle(revision, Status.PUBLISHED, generation + 1, at, null, null);
    }

    public PromptLifecycle disable(long expectedGeneration, Instant at) {
        checkGeneration(expectedGeneration); checkAfterPublication(at);
        if (status != Status.PUBLISHED || revokedAt != null) throw new PromptFailure("TEMPLATE_UNAVAILABLE");
        return new PromptLifecycle(revision, Status.DISABLED, generation + 1, publishedAt, at, revokedAt);
    }

    public PromptLifecycle emergencyRevoke(long expectedGeneration, Instant at) {
        checkGeneration(expectedGeneration); checkAfterPublication(at);
        if (revokedAt != null) throw new PromptFailure("VERSION_CONFLICT");
        return new PromptLifecycle(revision, status, generation + 1, publishedAt, disabledAt, at);
    }

    public PromptLifecycle archive(long expectedGeneration) {
        checkGeneration(expectedGeneration);
        if (status == Status.ARCHIVED) throw new PromptFailure("VERSION_CONFLICT");
        return new PromptLifecycle(revision, Status.ARCHIVED, generation + 1, publishedAt, disabledAt, revokedAt);
    }

    public void requireAvailable(PromptRevision.Kind kind, Action action, BindingProof binding, Instant now) {
        Objects.requireNonNull(kind); Objects.requireNonNull(action); Objects.requireNonNull(now);
        if (revokedAt != null || publishedAt == null || now.isBefore(publishedAt))
            throw new PromptFailure("TEMPLATE_UNAVAILABLE");
        if (action == Action.GUIDE_DISPLAY && kind != PromptRevision.Kind.GUIDE
                || action == Action.FIXED_SYSTEM_CALL && kind != PromptRevision.Kind.SYSTEM)
            throw new PromptFailure("ACCESS_DENIED");
        if (action != Action.FIXED_SYSTEM_CALL) {
            if (status != Status.PUBLISHED) throw new PromptFailure("TEMPLATE_UNAVAILABLE");
            return;
        }
        if (binding == null || !revision.equals(binding.revision()) || binding.boundAt().isBefore(publishedAt)
                || now.isBefore(binding.boundAt()) || !now.isBefore(binding.validUntil())
                || disabledAt != null && !binding.boundAt().isBefore(disabledAt))
            throw new PromptFailure("TEMPLATE_UNAVAILABLE");
        // Archiving preserves the prior publication/disable evidence; it cannot create a new binding.
        if (status != Status.PUBLISHED && status != Status.DISABLED && status != Status.ARCHIVED)
            throw new PromptFailure("TEMPLATE_UNAVAILABLE");
    }

    private void checkGeneration(long expected) {
        if (generation != expected) throw new PromptFailure("VERSION_CONFLICT");
    }

    private void checkAfterPublication(Instant at) {
        Objects.requireNonNull(at);
        if (publishedAt == null || at.isBefore(publishedAt) || disabledAt != null && at.isBefore(disabledAt))
            throw new PromptFailure("TEMPLATE_UNAVAILABLE");
    }
}
