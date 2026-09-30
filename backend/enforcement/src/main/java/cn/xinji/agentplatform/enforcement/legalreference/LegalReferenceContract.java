package cn.xinji.agentplatform.enforcement.legalreference;

import java.time.Instant;
import java.util.List;

public final class LegalReferenceContract {
    private LegalReferenceContract() { }

    public enum SearchState { COMPLETED, PARTIAL, FAILED }
    public enum ReviewState { PENDING, CONFIRMED, RETURNED }
    public enum BindingState { ACTIVE, NEEDS_REVIEW, UNBOUND }

    public record SearchRequest(String organization, String targetType, String targetId,
            long targetRevision, String targetAnchor, String caseRef, String purpose,
            String region, Instant eventAt, String query, List<String> sourceRefs) { }
    public record Candidate(String candidateId, String sourceId, long sourceRevision, String sourceVersion,
            String articleId, String officialTitle, String locator, String verbatimText,
            String verbatimSha256, String effectStatus, String transitionStatus,
            String explanation, List<String> gaps, String reviewStatus) { }
    public record SourceResult(String sourceId, long sourceRevision, String sourceVersion, String status,
            List<Candidate> candidates, String failure) { }
    public record SearchResult(String searchId, long revision, SearchState status,
            String organization, String targetType, String targetId, long targetRevision,
            String targetAnchor, String targetDigest, String caseRef, String purpose,
            String region, Instant eventAt, String query, List<String> scope,
            List<String> failedSources, List<Candidate> members) { }
    public record Selection(String searchId, long searchRevision, String candidateId,
            String targetType, String targetId, long targetRevision, String targetAnchor) { }
    public record ReferenceRevision(String referenceId, long revision, String organization,
            String searchId, long searchRevision, String candidateId, String sourceId,
            long sourceRevision, String sourceVersion, String articleId, String officialTitle, String locator,
            String verbatimText, String verbatimSha256, String explanation,
            String effectStatus, String transitionStatus, List<String> gaps,
            String targetType, String targetId, long targetRevision,
            String targetAnchor, String targetDigest, ReviewState reviewState,
            BindingState bindingState, String reviewedBy, String reviewReason,
            long previousRevision) { }
    public record Receipt(String requestId, String operation, String resultId, long version) { }
}
