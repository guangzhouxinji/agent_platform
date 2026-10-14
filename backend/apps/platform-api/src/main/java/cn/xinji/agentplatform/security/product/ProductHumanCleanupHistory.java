package cn.xinji.agentplatform.security.product;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Exact retained BODY_CLEAR history, never a cleanup authorization or a restore permission. */
final class ProductHumanCleanupHistory {
    record BodyIdentity(String packageId, long packageRevision, String manifestSha) { }
    record Operator(String issuer, String subject, String admissionSha256) { }
    record RetainedRequestScalars(String manifestSha, long taskVersion, String dataScope,
            String requesterIssuer, String requesterSubject, Instant createdAt, Instant bodyUntil,
            Instant auditUntil, long processingVersion, String handlingState, Instant lastChangeAt,
            long eventSequence, String requestSha, String requestId) { }
    record BodyClearBefore(RetainedRequestScalars retained, Instant bodyAccessUntil) { }
    record BodyClearAfter(String bodyState, Instant bodyClearedAt, long cleanupGeneration,
                          String retainedScalarsSha256) { }
    record BodyClearSelection(String packageId, long packageRevision, BodyClearBefore before,
                              BodyClearAfter after) { }
    record BodyClearEvent(String format, String operation, String scopeId, String organization,
            String taskId, long generation, long previousGeneration, String previousHistorySha,
            String intentId, ProductHumanCleanupScope.Policy policy, Operator operator,
            Instant firstAttemptAt, Instant occurredAt, Instant auditUntil, Instant scopeValidUntil,
            List<BodyClearSelection> selections, String selectionSha256) { }
    record BodyClearLink(String format, String scopeId, long generation, String previousHistorySha,
                         String eventSha) { }
    record StoredEvent(long generation, String eventJson, String eventSha, Instant occurredAt,
                       Instant auditUntil) { }
    record NativeBody(String packageId, long packageRevision, String taskId, String organization,
                      RetainedRequestScalars retained, Instant bodyAccessUntil, boolean manifestPresent) { }
    private record Cleared(BodyIdentity body, long generation) { }

    static final class Verified {
        private final ProductHumanCleanupScope.Snapshot scope;
        private final List<ProductHumanCleanupScope.Snapshot> ancestors;
        private final List<Cleared> cleared;
        private Verified(ProductHumanCleanupScope.Snapshot scope,
                List<ProductHumanCleanupScope.Snapshot> ancestors, List<Cleared> cleared) {
            this.scope = scope; this.ancestors = List.copyOf(ancestors); this.cleared = List.copyOf(cleared);
        }
        ProductHumanCleanupScope.Snapshot scope() { return scope; }
        void requireAncestorUnchanged(ProductHumanCleanupScope.Snapshot original, BodyIdentity body) {
            identity(body);
            if (!ancestors.contains(original) || cleared.stream().anyMatch(c -> sameKey(c.body(), body))) throw invalid();
        }
        void requireCleared(BodyIdentity body) {
            identity(body);
            if (cleared.stream().filter(c -> c.body().equals(body)).count() != 1) throw invalid();
        }
    }

    // Native projections are supplied by the scope's exact four-table reader, not by event JSON.
    static Verified verify(ProductHumanCleanupScope.Snapshot scope, List<StoredEvent> rows,
            ProductComparisonJson json, Clock clock, Function<BodyClearSelection, NativeBody> nativeBody) {
        Instant now = clock.instant();
        if (scope == null || rows == null || rows.isEmpty() || scope.generation() != rows.size()
                || !"READY".equals(scope.state()) || scope.intent() != null || !digest(scope.historySha())
                || scope.createdAt() == null || scope.createdAt().isAfter(now)
                || scope.validUntil() == null || !now.isBefore(scope.validUntil())) throw invalid();
        // Reject any expired row before decoding even the initialization JSON.
        for (int i = 0; i < rows.size(); i++) {
            StoredEvent row = rows.get(i);
            if (row == null || row.generation() != i + 1L || !digest(row.eventSha())
                    || row.occurredAt() == null || row.occurredAt().isAfter(now)
                    || row.auditUntil() == null || !now.isBefore(row.auditUntil())
                    || !row.auditUntil().isAfter(row.occurredAt())) throw invalid();
        }
        StoredEvent first = rows.getFirst();
        var initial = json.stored(first.eventJson(), ProductHumanCleanupScope.Initialization.class);
        if (initial == null || !"INITIALIZED".equals(initial.operation())
                || !Objects.equals(initial.scopeId(), scope.scopeId()) || !Objects.equals(initial.taskId(), scope.taskId())
                || !Objects.equals(initial.organization(), scope.organization()) || !Objects.equals(initial.policy(), scope.policy())
                || blank(initial.operator()) || !Objects.equals(initial.occurredAt(), scope.createdAt())
                || !Objects.equals(initial.occurredAt(), first.occurredAt()) || !Objects.equals(initial.auditUntil(), first.auditUntil())
                || initial.auditUntil().isAfter(initial.occurredAt().plus(Duration.ofDays(365)))
                || !Objects.equals(initial.validUntil(), scope.validUntil()) || initial.validUntil().isAfter(initial.auditUntil())
                || !json.sha(initial).equals(first.eventSha())) throw invalid();
        String history = json.sha(List.of(initial));
        var ancestors = new ArrayList<ProductHumanCleanupScope.Snapshot>();
        ancestors.add(snapshot(scope, 1, history));
        var cleared = new ArrayList<Cleared>();
        var keys = new HashSet<String>();
        var intents = new HashSet<String>();
        Instant previousAt = initial.occurredAt();
        for (int i = 1; i < rows.size(); i++) {
            StoredEvent row = rows.get(i);
            if (!clock.instant().isBefore(scope.validUntil()) || !clock.instant().isBefore(row.auditUntil())) throw invalid();
            var event = json.stored(row.eventJson(), BodyClearEvent.class);
            event(event, scope, row, history, previousAt, json);
            if (!intents.add(event.intentId())) throw invalid();
            BodyClearSelection previous = null;
            for (BodyClearSelection selection : event.selections()) {
                selection(selection, event, json);
                if (previous != null && compare(previous, selection) >= 0
                        || !keys.add(selection.packageId() + "/" + selection.packageRevision())) throw invalid();
                NativeBody actual = nativeBody.apply(selection);
                if (actual == null || !selection.packageId().equals(actual.packageId())
                        || selection.packageRevision() != actual.packageRevision() || !scope.taskId().equals(actual.taskId())
                        || !scope.organization().equals(actual.organization()) || actual.manifestPresent()
                        || actual.bodyAccessUntil() != null || !selection.before().retained().equals(actual.retained())) throw invalid();
                cleared.add(new Cleared(new BodyIdentity(selection.packageId(), selection.packageRevision(),
                        selection.before().retained().manifestSha()), row.generation()));
                previous = selection;
            }
            history = json.sha(new BodyClearLink("HUMAN_CLEANUP_CHAIN/v1", scope.scopeId(),
                    row.generation(), history, row.eventSha()));
            ancestors.add(snapshot(scope, row.generation(), history)); previousAt = event.occurredAt();
        }
        if (!history.equals(scope.historySha())) throw invalid();
        for (StoredEvent row : rows) if (!clock.instant().isBefore(row.auditUntil())) throw invalid();
        if (!clock.instant().isBefore(scope.validUntil())) throw invalid();
        return new Verified(scope, ancestors, cleared);
    }

    private static void event(BodyClearEvent e, ProductHumanCleanupScope.Snapshot scope, StoredEvent row,
            String previousHistory, Instant previousAt, ProductComparisonJson json) {
        if (e == null || !"HUMAN_BODY_CLEAR_EVENT/v1".equals(e.format()) || !"BODY_CLEAR".equals(e.operation())
                || !Objects.equals(scope.scopeId(), e.scopeId()) || !Objects.equals(scope.taskId(), e.taskId())
                || !Objects.equals(scope.organization(), e.organization()) || !Objects.equals(scope.policy(), e.policy())
                || e.generation() != row.generation() || e.previousGeneration() != row.generation() - 1
                || !previousHistory.equals(e.previousHistorySha()) || blank(e.intentId())
                || e.operator() == null || blank(e.operator().issuer()) || blank(e.operator().subject())
                || !digest(e.operator().admissionSha256()) || e.firstAttemptAt() == null || e.occurredAt() == null
                || e.firstAttemptAt().isBefore(previousAt) || e.firstAttemptAt().isAfter(e.occurredAt())
                || !e.occurredAt().equals(row.occurredAt()) || !Objects.equals(e.auditUntil(), row.auditUntil())
                || e.auditUntil().isAfter(e.occurredAt().plus(Duration.ofDays(365)))
                || !Objects.equals(e.scopeValidUntil(), scope.validUntil()) || e.auditUntil().isAfter(scope.validUntil())
                || e.selections() == null || e.selections().isEmpty()
                || !json.sha(e.selections()).equals(e.selectionSha256()) || !json.sha(e).equals(row.eventSha())) throw invalid();
    }

    private static void selection(BodyClearSelection s, BodyClearEvent e, ProductComparisonJson json) {
        if (s == null || blank(s.packageId()) || s.packageRevision() < 1 || s.before() == null
                || s.before().retained() == null || s.after() == null) throw invalid();
        var r = s.before().retained(); var a = s.after();
        if (!digest(r.manifestSha()) || r.taskVersion() < 1 || blank(r.dataScope())
                || blank(r.requesterIssuer()) || blank(r.requesterSubject()) || r.createdAt() == null
                || r.bodyUntil() == null || !r.bodyUntil().isAfter(r.createdAt())
                || r.bodyUntil().isAfter(r.createdAt().plus(Duration.ofDays(180)))
                || e.firstAttemptAt().isBefore(r.bodyUntil()) || e.occurredAt().isBefore(r.bodyUntil()) || r.auditUntil() == null
                || !r.auditUntil().isAfter(e.occurredAt()) || e.auditUntil().isAfter(r.auditUntil())
                || r.auditUntil().isAfter(r.createdAt().plus(Duration.ofDays(365)))
                || r.processingVersion() != 1 || !"WAITING".equals(r.handlingState())
                || !r.createdAt().equals(r.lastChangeAt()) || r.eventSequence() != 1
                || !digest(r.requestSha()) || blank(r.requestId()) || r.requestId().length() > 180
                || s.before().bodyAccessUntil() == null || !s.before().bodyAccessUntil().isAfter(r.createdAt())
                || s.before().bodyAccessUntil().isAfter(r.bodyUntil()) || !"CLEARED".equals(a.bodyState())
                || !e.occurredAt().equals(a.bodyClearedAt()) || a.cleanupGeneration() != e.generation()
                || !json.sha(r).equals(a.retainedScalarsSha256())) throw invalid();
    }

    private static ProductHumanCleanupScope.Snapshot snapshot(ProductHumanCleanupScope.Snapshot s, long g, String sha) {
        return new ProductHumanCleanupScope.Snapshot(s.scopeId(), s.organization(), s.taskId(), g, "READY", null,
                s.policy(), sha, s.createdAt(), s.validUntil());
    }
    private static int compare(BodyClearSelection a, BodyClearSelection b) {
        int key = a.packageId().compareTo(b.packageId());
        return key == 0 ? Long.compare(a.packageRevision(), b.packageRevision()) : key;
    }
    private static boolean sameKey(BodyIdentity a, BodyIdentity b) {
        return a.packageId().equals(b.packageId()) && a.packageRevision() == b.packageRevision();
    }
    private static void identity(BodyIdentity b) {
        if (b == null || blank(b.packageId()) || b.packageRevision() < 1 || !digest(b.manifestSha())) throw invalid();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalStateException invalid() { return new IllegalStateException("Human cleanup history unavailable"); }
}
