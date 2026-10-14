package cn.xinji.agentplatform.consultation.patenttask.human;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Domain proposals become acceptance facts only when the application transaction commits. */
public record OrdinaryHumanHandling(Packet packet, long processingVersion, State state,
                                    Actor claimant, Instant claimedAt, Instant lastCommittedChange,
                                    Instant endedAt, String endReason, List<Opinion> opinions) {
    public enum State { WAITING, CLAIMED, WITHDRAWN, RETURNED }
    public enum ActorKind { HUMAN, SERVICE }
    public enum Attribution { CURRENT_VERSION, HISTORICAL_VERSION }

    public record Actor(String issuer, String subject, ActorKind kind) {
        public Actor { required(issuer); required(subject); Objects.requireNonNull(kind); }
    }

    public record Binding(String goalRevision, String factRevision, String inputRevision, String reportRevision) {
        public Binding {
            required(goalRevision); required(factRevision);
            if (inputRevision != null) required(inputRevision);
            if (reportRevision != null) required(reportRevision);
        }
    }

    public record Packet(String taskId, String packageId, long revision, String manifestSha,
                         Actor requester, Binding binding, List<String> evidenceReferences) {
        public Packet {
            required(taskId); required(packageId); human(requester); Objects.requireNonNull(binding);
            if (revision < 1 || manifestSha == null || !manifestSha.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("An exact immutable package version is required");
            evidenceReferences = List.copyOf(evidenceReferences);
            evidenceReferences.forEach(OrdinaryHumanHandling::required);
            if (evidenceReferences.stream().distinct().count() != evidenceReferences.size())
                throw new IllegalArgumentException("Duplicate package evidence references");
        }
    }

    public record Opinion(String id, long revision, String previousId, Actor author, Instant submittedAt,
                          String body, List<String> evidenceReferences, String limitations,
                          Attribution attribution) {
        public Opinion {
            required(id); human(author); Objects.requireNonNull(submittedAt); required(body);
            required(limitations); Objects.requireNonNull(attribution);
            if (revision < 1) throw new IllegalArgumentException("A positive opinion revision is required");
            evidenceReferences = List.copyOf(evidenceReferences);
        }
        public boolean independentReview() { return false; }
    }

    public OrdinaryHumanHandling {
        Objects.requireNonNull(packet); Objects.requireNonNull(state); Objects.requireNonNull(lastCommittedChange);
        opinions = List.copyOf(opinions);
        boolean ended = state == State.WITHDRAWN || state == State.RETURNED;
        if (processingVersion < 1 || ended != (endedAt != null) || ended != (endReason != null)
                || state == State.WAITING && (claimant != null || claimedAt != null || !opinions.isEmpty())
                || state != State.WAITING && state != State.WITHDRAWN && (claimant == null || claimedAt == null)
                || (claimant == null) != (claimedAt == null))
            throw new IllegalArgumentException("Inconsistent human handling state");
        if (claimant != null) human(claimant);
        if (claimedAt != null && claimedAt.isAfter(lastCommittedChange)
                || endedAt != null && endedAt.isAfter(lastCommittedChange))
            throw new IllegalArgumentException("Human event time is inconsistent");
        if (endReason != null) required(endReason);
        if (processingVersion != 1L + (claimant == null ? 0 : 1) + opinions.size() + (ended ? 1 : 0))
            throw new IllegalArgumentException("Processing version does not match the human event history");
        String previous = null;
        Instant previousTime = claimedAt;
        for (int i = 0; i < opinions.size(); i++) {
            Opinion opinion = opinions.get(i);
            if (opinion.revision() != i + 1L || !Objects.equals(previous, opinion.previousId())
                    || !Objects.equals(claimant, opinion.author()) || claimedAt == null
                    || opinion.submittedAt().isBefore(previousTime)
                    || opinion.submittedAt().isAfter(lastCommittedChange)
                    || endedAt != null && opinion.submittedAt().isAfter(endedAt)
                    || !packet.evidenceReferences().containsAll(opinion.evidenceReferences()))
                throw new IllegalArgumentException("Opinion history does not bind the claimed package");
            previous = opinion.id();
            previousTime = opinion.submittedAt();
        }
        if (opinions.stream().map(Opinion::id).distinct().count() != opinions.size())
            throw new IllegalArgumentException("Duplicate opinion identity");
    }

    public static OrdinaryHumanHandling request(Packet packet, Instant now,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        retention.requireWritable(now, now, null, bounds);
        return new OrdinaryHumanHandling(packet, 1, State.WAITING, null, null, now, null, null, List.of());
    }

    public OrdinaryHumanHandling claim(long expectedVersion, Actor actor, Instant now,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        writable(expectedVersion, actor, now, retention, bounds);
        if (state != State.WAITING) throw new IllegalStateException("The package is not waiting for a claimant");
        return new OrdinaryHumanHandling(packet, processingVersion + 1, State.CLAIMED,
                actor, now, now, null, null, opinions);
    }

    public OrdinaryHumanHandling recordOpinion(long expectedVersion, String previousOpinionId, String id,
            Actor actor, Instant now, String body, List<String> evidence, String limitations, Binding current,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        writable(expectedVersion, actor, now, retention, bounds);
        if (state != State.CLAIMED || !actor.equals(claimant))
            throw new IllegalStateException("Only the actual claimant may record a human opinion");
        String previous = opinions.isEmpty() ? null : opinions.getLast().id();
        if (!Objects.equals(previous, previousOpinionId))
            throw new IllegalStateException("The previous opinion revision changed");
        Objects.requireNonNull(current);
        List<Opinion> next = new ArrayList<>(opinions);
        next.add(new Opinion(id, next.size() + 1L, previous, actor, now, body, evidence, limitations,
                packet.binding().equals(current) ? Attribution.CURRENT_VERSION : Attribution.HISTORICAL_VERSION));
        return new OrdinaryHumanHandling(packet, processingVersion + 1, state,
                claimant, claimedAt, now, null, null, next);
    }

    public OrdinaryHumanHandling withdraw(long expectedVersion, Actor actor, Instant now, String reason,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        writable(expectedVersion, actor, now, retention, bounds);
        if (!packet.requester().equals(actor)) throw new IllegalStateException("Only the requester may withdraw");
        return terminate(State.WITHDRAWN, now, reason);
    }

    public OrdinaryHumanHandling returnPackage(long expectedVersion, Actor actor, Instant now, String reason,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        writable(expectedVersion, actor, now, retention, bounds);
        if (state != State.CLAIMED || !actor.equals(claimant))
            throw new IllegalStateException("Only the actual claimant may return the package");
        return terminate(State.RETURNED, now, reason);
    }

    public boolean selfHandling() { return packet.requester().equals(claimant); }

    private OrdinaryHumanHandling terminate(State end, Instant now, String reason) {
        required(reason);
        return new OrdinaryHumanHandling(packet, processingVersion + 1, end,
                claimant, claimedAt, now, now, reason, opinions);
    }

    private void writable(long expectedVersion, Actor actor, Instant now,
            OrdinaryHumanRetention retention, OrdinaryHumanRetention.Bounds bounds) {
        human(actor);
        if (expectedVersion != processingVersion) throw new IllegalStateException("Human processing version changed");
        retention.requireWritable(now, lastCommittedChange, endedAt, bounds);
    }

    private static void human(Actor actor) {
        if (actor == null || actor.kind() != ActorKind.HUMAN)
            throw new IllegalArgumentException("A verified human actor is required");
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A human contract value is required");
    }
}
