package cn.xinji.agentplatform.adjudication.domain;

import java.time.Instant;
import java.util.List;

public record AdjudicationSnapshot(
        Work work,
        List<MaterialInspection> inspections,
        List<LegalCandidateSet> candidates,
        List<Citation> citations,
        List<Draft> drafts,
        List<TestReview> reviews,
        List<String> reviewImpacts) {

    public static AdjudicationSnapshot open(Work work) {
        return new AdjudicationSnapshot(work, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public record Source(String kind, String id, String version, String location) {}
    public record Work(String workItemId, Source caseRef, List<Source> materialRefs, String organizationRef, Instant createdAt, String scopeLabel) {}
    public record MaterialInspection(String inspectionId, int version, List<Source> inputs, List<String> partyStatements,
            List<String> unresolvedFacts, List<String> missingItems, List<String> conflicts, String previousRef, Instant createdAt) {}
    public record LegalCandidateSet(String candidateSetId, int version, String inspectionRef, List<LegalCandidate> entries,
            List<String> limitations, String previousRef, Instant createdAt) {}
    public record LegalCandidate(String code, Source source, String location, List<String> doubts) {}
    public record Citation(String citationId, int version, Source legalSource, String location, String reason, String action,
            String previousRef, Instant createdAt) {}
    public record Draft(String draftId, int version, String inspectionRef, List<String> citationRefs, Source templateRef,
            List<Section> sections, List<String> missingItems, List<String> conflicts, String previousRef, Instant createdAt, boolean isDraft) {}
    public record Section(String anchor, String content, List<Source> sources) {}
    public record TestReview(String reviewId, String draftId, int draftVersion, String action, String reason,
            String actorRef, Instant actedAt, String scopeLabel, boolean notOfficialDecision) {}
}
