package cn.xinji.agentplatform.consultation.patenttask.authorization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A decision over current, trusted authorization facts; this class does not authenticate or issue grants. */
public final class ProductAccessPolicy {
    public enum Kind { HUMAN, SERVICE }
    public enum Action { READ, WRITE, CREATE, QUERY, DISPLAY, DOWNLOAD, COMPARE, HUMAN_ACCEPT, HUMAN_REVIEW, GRANT, RESTORE }
    public enum Purpose { TASK_WORK, SOURCE_QUERY, SOURCE_DISPLAY, SOURCE_DOWNLOAD, COMPARISON, MODEL_INPUT }

    public record Actor(String issuer, String id, Kind kind, String organization) { }
    public record Resource(String domain, String type, String id, String organization, long version) { }
    public record Source(String id, String version) { }
    public record Grant(Actor actor, Resource resource, Action action, Purpose purpose,
                        Instant validUntil, long authorizationEpoch) { }
    public record Delegation(Actor user, Actor service, Resource resource, Action action,
                             Purpose purpose, Instant validUntil, long authorizationEpoch) { }
    public record SourceUse(Source source, Purpose purpose, Instant validUntil, long sourceEpoch) { }
    public record CurrentState(long authorizationEpoch, long sourceEpoch, boolean available) { }
    public record Request(Actor actor, Resource resource, Action action, Purpose purpose,
                          Source source, Instant now) { }
    public record Evidence(CurrentState current, List<Grant> grants, Delegation delegation,
                           List<SourceUse> sourceUses) { }

    private ProductAccessPolicy() { }

    public static boolean permits(Request request, Evidence evidence) {
        if (request == null || evidence == null || evidence.current() == null
                || !evidence.current().available() || !valid(request.actor())
                || !valid(request.resource()) || request.action() == null
                || request.purpose() == null || request.now() == null
                || !request.actor().organization().equals(request.resource().organization())
                || evidence.current().authorizationEpoch() < 0 || evidence.current().sourceEpoch() < 0
                || evidence.grants() == null || evidence.sourceUses() == null) return false;

        if (request.actor().kind() == Kind.SERVICE && (request.action() == Action.HUMAN_ACCEPT
                || request.action() == Action.HUMAN_REVIEW || request.action() == Action.GRANT
                || request.action() == Action.RESTORE)) return false;

        boolean needsSource = request.purpose() != Purpose.TASK_WORK;
        if (needsSource && (!valid(request.source()) || evidence.sourceUses().stream().noneMatch(use ->
                use != null && Objects.equals(use.source(), request.source())
                        && use.purpose() == request.purpose()
                        && live(use.validUntil(), request.now())
                        && use.sourceEpoch() == evidence.current().sourceEpoch()))) return false;
        if (!needsSource && request.source() != null) return false;

        if (!hasGrant(evidence.grants(), request.actor(), request, evidence.current().authorizationEpoch())) {
            return false;
        }
        if (request.actor().kind() == Kind.HUMAN) return evidence.delegation() == null;

        Delegation delegation = evidence.delegation();
        return delegation != null && valid(delegation.user())
                && delegation.user().kind() == Kind.HUMAN
                && delegation.user().organization().equals(request.actor().organization())
                && Objects.equals(delegation.service(), request.actor())
                && Objects.equals(delegation.resource(), request.resource())
                && delegation.action() == request.action() && delegation.purpose() == request.purpose()
                && live(delegation.validUntil(), request.now())
                && delegation.authorizationEpoch() == evidence.current().authorizationEpoch()
                && hasGrant(evidence.grants(), delegation.user(), request,
                        evidence.current().authorizationEpoch());
    }

    private static boolean hasGrant(List<Grant> grants, Actor actor, Request request, long epoch) {
        return grants.stream().anyMatch(grant -> grant != null && Objects.equals(grant.actor(), actor)
                && Objects.equals(grant.resource(), request.resource())
                && grant.action() == request.action() && grant.purpose() == request.purpose()
                && grant.authorizationEpoch() == epoch && live(grant.validUntil(), request.now()));
    }

    private static boolean valid(Actor actor) {
        return actor != null && present(actor.issuer()) && present(actor.id())
                && actor.kind() != null && present(actor.organization());
    }

    private static boolean valid(Resource resource) {
        return resource != null && present(resource.domain()) && present(resource.type())
                && present(resource.id()) && present(resource.organization()) && resource.version() > 0;
    }

    private static boolean valid(Source source) {
        return source != null && present(source.id()) && present(source.version());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean live(Instant until, Instant now) {
        return until != null && until.isAfter(now);
    }
}
