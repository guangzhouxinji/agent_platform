package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText;
import java.time.Instant;
import java.util.List;

final class ProductComparisonContracts {
    private ProductComparisonContracts() { }
    record DocumentRef(String sourceId, String documentId, String documentVersion, List<Integer> claimNumbers,
                       String resultSetId, String selectionId, Long selectionRevision, String memberId) { }
    record Target(String messageId, DocumentRef patent) { }
    record Prepare(long expectedTaskVersion, String goalId, long goalRevision, String inputId,
                   String purpose, Target target, List<DocumentRef> candidates) { }
    record FeatureInput(String featureId, int characterStart, int characterEnd, String userText, String parentFeatureId) { }
    record Confirm(long expectedTaskVersion, String preparationId, String prepareSha,
                   long previousFeatureRevision, boolean confirmed, String userStatement, List<FeatureInput> features) { }
    record Span(String version, String ref, String pointer, String originalSha, int byteStart, int byteEnd, String spanSha) {
        static Span of(OriginalClaimText.Evidence e) {
            return new Span(e.documentVersion(),e.originalRef(),e.jsonPointer(),e.originalSha(),e.byteStart(),e.byteEnd(),e.spanSha());
        }
    }
    record Claim(int number, List<Integer> parents, Span span) { }
    record Branch(int selectedClaim, List<Claim> inheritedClaims) { }
    record MaterialDocument(String key, String sourceId, String documentId, String documentVersion,
                            String sourceVersion, String rawSha, String originalRef, String jsonPointer,
                            List<Branch> branches, String gap) { }
    record DocumentView(MaterialDocument binding, String claimsText) { }
    record PreparationView(String preparationId, String prepareSha, long taskVersion, String purpose,
                           Span target, String targetText, List<DocumentView> candidates, boolean targetReady,
                           List<String> featureSuggestions, Instant expiresAt, String taskId, String requestId, String requestSha) { }
    record Feature(String featureId, long ordinal, int characterStart, int characterEnd, String userText,
                   String parentFeatureId, Integer claimNumber, Span original) { }
    record Revision(String revisionId, String taskId, long revisionNumber, String previousRevisionId,
                    String preparationId, String prepareSha, String targetSha, String actorIssuer, String actorSubject,
                    String requestId, String requestSha, String userStatement, Instant confirmedAt, Instant expiresAt,
                    List<Feature> features) { }
    record FeatureView(Revision revision, String packageSha) { }
}
