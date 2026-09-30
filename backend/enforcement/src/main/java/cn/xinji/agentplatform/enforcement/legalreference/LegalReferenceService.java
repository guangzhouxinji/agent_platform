package cn.xinji.agentplatform.enforcement.legalreference;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

public final class LegalReferenceService {
    public interface Access {
        void require(String actor, String organization, String type, String id, long version,
                     String action, String purpose);
    }
    public interface Target {
        String require(String actor, String organization, String type, String id,
                       long revision, String anchor, String purpose);
    }
    public record Review(long expectedRevision, String decision, String reason) { }
    public record Change(long expectedRevision, String reason) { }

    private final LegalReferenceStore store;
    private final LegalKnowledgePort source;
    private final Access access;
    private final Target target;

    public LegalReferenceService(LegalReferenceStore store, LegalKnowledgePort source,
                                 Access access, Target target) {
        this.store = store;
        this.source = source;
        this.access = access;
        this.target = target;
    }

    public Receipt search(String actor, String key, SearchRequest request) {
        validateSearch(actor, key, request);
        access.require(actor, request.organization(), request.targetType(), request.targetId(),
                request.targetRevision(), "SEARCH_LEGAL_BASIS", request.purpose());
        String targetDigest = target.require(actor, request.organization(), request.targetType(),
                request.targetId(), request.targetRevision(), request.targetAnchor(), request.purpose());
        var receipt = store.command(key, hash(request.toString()), actor, "SEARCH_LEGAL_BASIS", () -> {
            access.require(actor, request.organization(), request.targetType(), request.targetId(),
                    request.targetRevision(), "SEARCH_LEGAL_BASIS", request.purpose());
            String currentTarget = target.require(actor, request.organization(), request.targetType(),
                    request.targetId(), request.targetRevision(), request.targetAnchor(), request.purpose());
            if (!targetDigest.equals(currentTarget)) throw new IllegalArgumentException("Target anchor changed");
            String id = UUID.randomUUID().toString();
            List<SourceResult> results = source.search(request);
            for (var item : results) access.require(actor, request.organization(), "LEGAL_SOURCE",
                    item.sourceId(), item.sourceRevision(), "SEARCH", request.purpose());
            store.addSearch(result(id, request, targetDigest, results));
            return new Receipt(key, "SEARCH_LEGAL_BASIS", id, 1);
        });
        readSearch(actor, receipt.resultId(), receipt.version());
        return receipt;
    }

    public SearchResult readSearch(String actor, String id, long version) {
        var found = store.search(id, version).orElseThrow(() -> new SecurityException("Search unavailable"));
        access.require(actor, found.organization(), found.targetType(), found.targetId(),
                found.targetRevision(), "READ_LEGAL_BASIS", found.purpose());
        String current = target.require(actor, found.organization(), found.targetType(), found.targetId(),
                found.targetRevision(), found.targetAnchor(), found.purpose());
        if (!found.targetDigest().equals(current)) throw new IllegalArgumentException("Target anchor changed");
        for (var item : found.members()) access.require(actor, found.organization(), "LEGAL_SOURCE",
                item.sourceId(), item.sourceRevision(), "READ_VERBATIM", found.purpose());
        return found;
    }

    public Receipt bind(String actor, String key, Selection selection) {
        if (blank(actor) || blank(key) || selection == null || blank(selection.searchId()) ||
                selection.searchRevision() < 1 || blank(selection.candidateId()))
            throw new IllegalArgumentException("Invalid legal reference selection");
        var search = readSearch(actor, selection.searchId(), selection.searchRevision());
        exactTarget(search, selection);
        var candidate = candidate(search, selection.candidateId());
        access.require(actor, search.organization(), "LEGAL_SOURCE", candidate.sourceId(),
                candidate.sourceRevision(), "SELECT", search.purpose());
        verifyCandidate(candidate);
        String targetDigest = target.require(actor, search.organization(), search.targetType(),
                search.targetId(), search.targetRevision(), search.targetAnchor(), search.purpose());
        var receipt = store.command(key, hash(selection.toString()), actor, "BIND_LEGAL_REFERENCE", () -> {
            var current = readSearch(actor, selection.searchId(), selection.searchRevision());
            exactTarget(current, selection);
            if (!targetDigest.equals(target.require(actor, current.organization(), current.targetType(),
                    current.targetId(), current.targetRevision(), current.targetAnchor(), current.purpose())))
                throw new IllegalArgumentException("Target anchor changed");
            var exact = candidate(current, selection.candidateId());
            access.require(actor, current.organization(), "LEGAL_SOURCE", exact.sourceId(),
                    exact.sourceRevision(), "SELECT", current.purpose());
            verifyCandidate(exact);
            String id = UUID.randomUUID().toString();
            store.addReference(reference(id, current, exact));
            return new Receipt(key, "BIND_LEGAL_REFERENCE", id, 1);
        });
        return receipt;
    }

    public ReferenceRevision read(String actor, String id, long version) {
        if (blank(actor) || blank(id) || version < 1) throw new IllegalArgumentException("Invalid reference");
        var ref = store.reference(id, version).orElseThrow(() -> new SecurityException("Reference unavailable"));
        access.require(actor, ref.organization(), "LEGAL_REFERENCE", id, version,
                "READ", "LEGAL_BASIS");
        access.require(actor, ref.organization(), "LEGAL_SOURCE", ref.sourceId(), ref.sourceRevision(),
                "READ_VERBATIM", "LEGAL_BASIS");
        return ref;
    }

    public List<ReferenceRevision> history(String actor, String id) {
        var latest = read(actor, id, latest(id));
        access.require(actor, latest.organization(), "LEGAL_REFERENCE", id, latest.revision(),
                "READ_HISTORY", "LEGAL_BASIS");
        return store.history(id);
    }

    public Receipt review(String actor, String key, String id, Review input) {
        if (input == null || input.expectedRevision() < 1 || blank(input.reason()) ||
                !("CONFIRMED".equals(input.decision()) || "RETURNED".equals(input.decision())))
            throw new IllegalArgumentException("Invalid named legal review");
        var prior = read(actor, id, input.expectedRevision());
        String action = "CONFIRMED".equals(input.decision()) ? "CONFIRM" : "RETURN";
        access.require(actor, prior.organization(), "LEGAL_REFERENCE", id, prior.revision(),
                action, "HUMAN_LEGAL_REVIEW");
        if ("CONFIRMED".equals(input.decision()) && (!prior.gaps().isEmpty() ||
                !"VERIFIED".equals(prior.effectStatus()) ||
                !"CLEAR".equals(prior.transitionStatus())))
            throw new IllegalArgumentException("Legal gaps require review");
        return revise(actor, key, id, input.expectedRevision(), "REVIEW_LEGAL_REFERENCE", action,
                hash(id + input), prior, input.decision(), prior.bindingState(), input.reason(), false);
    }

    public Receipt unbind(String actor, String key, String id, Change input) {
        if (input == null || input.expectedRevision() < 1 || blank(input.reason()))
            throw new IllegalArgumentException("Invalid unbind request");
        var prior = read(actor, id, input.expectedRevision());
        access.require(actor, prior.organization(), "LEGAL_REFERENCE", id, prior.revision(),
                "UNBIND", "LEGAL_BASIS");
        return revise(actor, key, id, input.expectedRevision(), "UNBIND_LEGAL_REFERENCE", "UNBIND",
                hash(id + input), prior, prior.reviewState().name(), BindingState.UNBOUND,
                input.reason(), false);
    }

    public Receipt recheck(String actor, String key, String id, Change input) {
        if (input == null || input.expectedRevision() < 1)
            throw new IllegalArgumentException("Invalid recheck request");
        var prior = read(actor, id, input.expectedRevision());
        access.require(actor, prior.organization(), "LEGAL_REFERENCE", id, prior.revision(),
                "RECHECK", "LEGAL_BASIS");
        var receipt = store.command(key, hash(id + input), actor, "RECHECK_LEGAL_REFERENCE", () -> {
            if (store.latestReferenceVersion(id) != input.expectedRevision())
                throw new IllegalArgumentException("Legal reference version conflict");
            read(actor, id, input.expectedRevision());
            access.require(actor, prior.organization(), "LEGAL_REFERENCE", id, prior.revision(),
                    "RECHECK", "LEGAL_BASIS");
            Candidate latest = source.latest(prior.sourceId(), prior.articleId());
            access.require(actor, prior.organization(), "LEGAL_SOURCE", latest.sourceId(),
                    latest.sourceRevision(), "READ_VERBATIM", "LEGAL_BASIS");
            verifyCandidate(latest);
            boolean changed = !prior.sourceVersion().equals(latest.sourceVersion()) ||
                    !prior.verbatimSha256().equals(latest.verbatimSha256());
            ReferenceRevision base = changed ? updated(prior, latest) : prior;
            long next = prior.revision() + 1;
            store.addReference(copy(base, next, changed ? ReviewState.PENDING : prior.reviewState(),
                    changed ? BindingState.NEEDS_REVIEW : prior.bindingState(), actor,
                    changed ? "SOURCE_UPDATED_REVIEW_REQUIRED" : input.reason(), prior.revision()));
            return new Receipt(key, "RECHECK_LEGAL_REFERENCE", id, next);
        });
        if (store.reference(id, receipt.version()).isEmpty())
            throw new IllegalStateException("Legal reference revision unavailable");
        return receipt;
    }

    private Receipt revise(String actor, String key, String id, long expected, String operation, String action,
                           String fingerprint, ReferenceRevision prior, String review,
                           BindingState binding, String reason, boolean sourceChanged) {
        var receipt = store.command(key, fingerprint, actor, operation, () -> {
            if (store.latestReferenceVersion(id) != expected)
                throw new IllegalArgumentException("Legal reference version conflict");
            read(actor, id, expected);
            access.require(actor, prior.organization(), "LEGAL_REFERENCE", id, expected,
                    action, action.equals("CONFIRM") || action.equals("RETURN")
                            ? "HUMAN_LEGAL_REVIEW" : "LEGAL_BASIS");
            ReferenceRevision base = sourceChanged ? prior : store.reference(id, expected).orElseThrow();
            verifyReferenceSource(base);
            long next = expected + 1;
            store.addReference(copy(base, next, ReviewState.valueOf(review), binding, actor, reason, expected));
            return new Receipt(key, operation, id, next);
        });
        read(actor, id, expected);
        if (store.reference(id, receipt.version()).isEmpty())
            throw new IllegalStateException("Legal reference revision unavailable");
        return receipt;
    }

    private void verifyCandidate(Candidate candidate) {
        Candidate exact = source.exact(candidate.sourceId(), candidate.sourceVersion(), candidate.candidateId());
        if (!candidate.verbatimSha256().equals(exact.verbatimSha256()) ||
                !hash(candidate.verbatimText()).equals(candidate.verbatimSha256()))
            throw new IllegalArgumentException("Legal source digest changed");
    }

    private void verifyReferenceSource(ReferenceRevision reference) {
        Candidate exact = source.exact(reference.sourceId(), reference.sourceVersion(), reference.candidateId());
        if (!reference.verbatimSha256().equals(exact.verbatimSha256()))
            throw new IllegalArgumentException("Legal source digest changed");
    }

    private static SearchResult result(String id, SearchRequest request, String targetDigest,
                                       List<SourceResult> sources) {
        if (sources == null || sources.isEmpty()) throw new IllegalStateException("Legal source unavailable");
        var members = new ArrayList<Candidate>(); var failed = new ArrayList<String>(); var scope = new ArrayList<String>();
        for (var item : sources) {
            scope.add(item.sourceId() + "#" + item.sourceRevision() + "@" + item.sourceVersion());
            if (!"COMPLETED".equals(item.status())) failed.add(item.sourceId() + ":" + item.failure());
            if (item.candidates() != null) members.addAll(item.candidates());
        }
        SearchState state = failed.isEmpty() ? SearchState.COMPLETED :
                failed.size() == sources.size() ? SearchState.FAILED : SearchState.PARTIAL;
        return new SearchResult(id, 1, state, request.organization(), request.targetType(), request.targetId(),
                request.targetRevision(), request.targetAnchor(), targetDigest, request.caseRef(), request.purpose(),
                request.region(), request.eventAt(), request.query(), List.copyOf(scope), List.copyOf(failed),
                List.copyOf(members));
    }

    private static ReferenceRevision reference(String id, SearchResult search, Candidate c) {
        return new ReferenceRevision(id, 1, search.organization(), search.searchId(), search.revision(),
                c.candidateId(), c.sourceId(), c.sourceRevision(), c.sourceVersion(), c.articleId(),
                c.officialTitle(), c.locator(),
                c.verbatimText(), c.verbatimSha256(), c.explanation(), c.effectStatus(), c.transitionStatus(),
                List.copyOf(c.gaps()), search.targetType(),
                search.targetId(), search.targetRevision(), search.targetAnchor(), search.targetDigest(),
                ReviewState.PENDING, BindingState.ACTIVE, null, null, 0);
    }

    private static ReferenceRevision updated(ReferenceRevision r, Candidate c) {
        return new ReferenceRevision(r.referenceId(), r.revision(), r.organization(), r.searchId(), r.searchRevision(),
                c.candidateId(), c.sourceId(), c.sourceRevision(), c.sourceVersion(), c.articleId(),
                c.officialTitle(), c.locator(),
                c.verbatimText(), c.verbatimSha256(), c.explanation(), c.effectStatus(), c.transitionStatus(),
                List.copyOf(c.gaps()), r.targetType(),
                r.targetId(), r.targetRevision(), r.targetAnchor(), r.targetDigest(), r.reviewState(),
                r.bindingState(), r.reviewedBy(), r.reviewReason(), r.previousRevision());
    }

    private static ReferenceRevision copy(ReferenceRevision r, long version, ReviewState review,
            BindingState binding, String actor, String reason, long previous) {
        return new ReferenceRevision(r.referenceId(), version, r.organization(), r.searchId(), r.searchRevision(),
                r.candidateId(), r.sourceId(), r.sourceRevision(), r.sourceVersion(), r.articleId(),
                r.officialTitle(), r.locator(),
                r.verbatimText(), r.verbatimSha256(), r.explanation(), r.effectStatus(), r.transitionStatus(),
                r.gaps(), r.targetType(), r.targetId(),
                r.targetRevision(), r.targetAnchor(), r.targetDigest(), review, binding, actor, reason, previous);
    }

    private static Candidate candidate(SearchResult search, String id) {
        return search.members().stream().filter(c -> id.equals(c.candidateId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate unavailable"));
    }
    private static void exactTarget(SearchResult s, Selection x) {
        if (!s.targetType().equals(x.targetType()) || !s.targetId().equals(x.targetId()) ||
                s.targetRevision() != x.targetRevision() || !s.targetAnchor().equals(x.targetAnchor()))
            throw new IllegalArgumentException("Exact target required");
    }
    private long latest(String id) {
        long version = store.latestReferenceVersion(id);
        if (version < 1) throw new SecurityException("Reference unavailable");
        return version;
    }
    private static void validateSearch(String actor, String key, SearchRequest r) {
        if (blank(actor) || blank(key) || r == null || blank(r.organization()) || blank(r.targetType()) ||
                blank(r.targetId()) || r.targetRevision() < 1 || blank(r.targetAnchor()) || blank(r.caseRef()) ||
                blank(r.purpose()) || blank(r.region()) || r.eventAt() == null || blank(r.query()) ||
                r.sourceRefs() == null || r.sourceRefs().isEmpty())
            throw new IllegalArgumentException("Invalid legal search");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
