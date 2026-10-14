package cn.xinji.agentplatform.consultation.patentcomparison;

import java.util.*;
import java.util.regex.Pattern;

/** Final evidence matrix contract. Trusted confirmation and current authorization belong to application ports. */
public final class PatentComparisonMatrix {
    public enum Relation { CORRESPONDING, PARTIAL, NOT_FOUND_IN_ANALYZED_RANGE, UNKNOWN, NOT_ANALYZED }
    public enum Purpose { TECHNICAL_SIMILARITY, CLAIM_ALIGNMENT }
    public record Feature(String id, OriginalClaimText.Evidence original) { }
    public record Candidate(String id, String documentVersion, List<PatentClaimGraph.Branch> branches) {
        public Candidate { branches = List.copyOf(branches); }
    }
    public record Snapshot(String taskId, long taskVersion, String goalRevision, String inputRevision,
                           String featureRevision, String targetSha, Purpose purpose, String methodVersion,
                           List<Feature> confirmedFeatures, List<Candidate> candidates) {
        public Snapshot { confirmedFeatures = List.copyOf(confirmedFeatures); candidates = List.copyOf(candidates); }
    }
    public record Row(String candidateId, List<Integer> claimBranch, String featureId, Relation relation,
                      List<String> sameAspects, List<String> differences, String reasoning, String uncertainty,
                      List<OriginalClaimText.Evidence> analyzedRange, List<OriginalClaimText.Evidence> citations) {
        public Row {
            claimBranch = List.copyOf(claimBranch); sameAspects = List.copyOf(sameAspects);
            differences = List.copyOf(differences); analyzedRange = List.copyOf(analyzedRange);
            citations = List.copyOf(citations);
        }
    }
    @FunctionalInterface public interface OriginalEvidenceVerifier {
        boolean verify(OriginalClaimText.Evidence evidence);
    }
    private record Key(String candidate, List<Integer> branch, String feature) { }
    private static final Pattern LEGAL_CONCLUSION = Pattern.compile(
            "构成侵权|具有新颖性|具备新颖性|具有创造性|应予授权|应当驳回|(?i:\\binfringes\\b|\\bis\\s+(?:novel|inventive)\\b|\\bpatent\\s+is\\s+(?:valid|invalid)\\b|\\bmust\\s+be\\s+(?:granted|rejected)\\b)");
    private PatentComparisonMatrix() { }

    public static List<Row> validate(Snapshot input, List<Row> rows, OriginalEvidenceVerifier originals) {
        if (input == null || rows == null || originals == null || blank(input.taskId())
                || input.taskVersion() < 1 || blank(input.goalRevision()) || blank(input.inputRevision())
                || blank(input.featureRevision()) || input.targetSha() == null
                || !input.targetSha().matches("[a-f0-9]{64}") || input.purpose() == null
                || !"PATENT_FEATURE_MATRIX_V1".equals(input.methodVersion())
                || input.confirmedFeatures().isEmpty() || input.confirmedFeatures().size() > 200
                || input.candidates().isEmpty() || input.candidates().size() > 20 || rows.size() > 4000)
            fail("MATRIX_INPUT_INVALID");
        Set<String> features = new HashSet<>(), candidates = new HashSet<>();
        for (Feature feature : input.confirmedFeatures()) {
            if (feature == null || blank(feature.id()) || !features.add(feature.id())) fail("FEATURE_ID_INVALID");
            evidence(feature.original(), originals);
            if (!input.targetSha().equals(feature.original().originalSha())) fail("FEATURE_TARGET_VERSION_MISMATCH");
        }
        Map<Key, Candidate> expected = new HashMap<>();
        for (Candidate candidate : input.candidates()) {
            if (candidate == null || blank(candidate.id()) || blank(candidate.documentVersion())
                    || !candidates.add(candidate.id()) || candidate.branches().isEmpty()) fail("CANDIDATE_INVALID");
            Set<List<Integer>> branches = new HashSet<>();
            for (PatentClaimGraph.Branch branch : candidate.branches()) {
                if (branch.inheritedClaims().isEmpty()) fail("CLAIM_BRANCH_INVALID");
                List<Integer> path = branch.inheritedClaims().stream().map(PatentClaimGraph.Claim::number).toList();
                if (!branches.add(path) || new HashSet<>(path).size() != path.size()
                        || path.getLast() != branch.selectedClaim()) fail("CLAIM_BRANCH_INVALID");
                for (int i = 0; i < branch.inheritedClaims().size(); i++) {
                    var claim = branch.inheritedClaims().get(i);
                    if (claim.number() < 1 || (i == 0 && !claim.alternatives().isEmpty())
                            || (i > 0 && (path.get(i - 1) >= claim.number()
                            || !claim.alternatives().contains(path.get(i - 1))))) fail("CLAIM_BRANCH_INCOMPLETE");
                }
                for (var claim : branch.inheritedClaims()) {
                    evidence(claim.original(), originals);
                    if (!candidate.documentVersion().equals(claim.original().documentVersion())) fail("CLAIM_VERSION_MISMATCH");
                }
                for (String feature : features) {
                    if (expected.size() >= 4000) fail("MATRIX_BUDGET_EXCEEDED");
                    expected.put(new Key(candidate.id(), path, feature), candidate);
                }
            }
        }
        Set<Key> seen = new HashSet<>();
        for (Row row : rows) {
            if (row == null || row.relation() == null || blank(row.reasoning()) || row.uncertainty() == null)
                fail("MATRIX_ROW_INVALID");
            Key key = new Key(row.candidateId(), row.claimBranch(), row.featureId());
            Candidate candidate = expected.get(key);
            if (candidate == null || !seen.add(key)) fail("MATRIX_ROW_BINDING_OR_DUPLICATE");
            if (row.sameAspects().stream().anyMatch(PatentComparisonMatrix::blank)
                    || row.differences().stream().anyMatch(PatentComparisonMatrix::blank)) fail("MATRIX_ROW_INVALID");
            if (LEGAL_CONCLUSION.matcher(row.reasoning() + " " + row.uncertainty()
                    + " " + String.join(" ", row.sameAspects()) + " " + String.join(" ", row.differences())).find())
                fail("LEGAL_CONCLUSION_NOT_ALLOWED");
            for (var item : row.analyzedRange()) candidateEvidence(candidate, row.claimBranch(), item, originals);
            for (var item : row.citations()) {
                candidateEvidence(candidate, row.claimBranch(), item, originals);
                boolean covered = row.analyzedRange().stream().anyMatch(range ->
                        range.originalRef().equals(item.originalRef()) && range.originalSha().equals(item.originalSha())
                        && range.jsonPointer().equals(item.jsonPointer()) && range.byteStart() <= item.byteStart()
                        && range.byteEnd() >= item.byteEnd());
                if (!covered) fail("CITATION_OUTSIDE_ANALYZED_RANGE");
            }
            switch (row.relation()) {
                case CORRESPONDING -> {
                    if (row.citations().isEmpty() || row.sameAspects().isEmpty() || row.analyzedRange().isEmpty())
                        fail("CORRESPONDENCE_EVIDENCE_MISSING");
                }
                case PARTIAL -> {
                    if (row.citations().isEmpty() || row.sameAspects().isEmpty() || row.differences().isEmpty())
                        fail("PARTIAL_DIFFERENCE_OR_EVIDENCE_MISSING");
                }
                case NOT_FOUND_IN_ANALYZED_RANGE -> {
                    if (row.analyzedRange().isEmpty() || !row.sameAspects().isEmpty() || !row.citations().isEmpty())
                        fail("NOT_FOUND_SCOPE_INVALID");
                }
                case UNKNOWN, NOT_ANALYZED -> {
                    if (blank(row.uncertainty()) || !row.sameAspects().isEmpty()) fail("UNKNOWN_REASON_MISSING");
                    if (row.relation() == Relation.NOT_ANALYZED
                            && (!row.analyzedRange().isEmpty() || !row.citations().isEmpty())) fail("UNANALYZED_FALSE_EVIDENCE");
                }
            }
        }
        if (!seen.equals(expected.keySet())) fail("MATRIX_ROWS_INCOMPLETE");
        return List.copyOf(rows);
    }

    private static void candidateEvidence(Candidate candidate, List<Integer> selectedBranch, OriginalClaimText.Evidence item,
                                          OriginalEvidenceVerifier originals) {
        evidence(item, originals);
        if (!candidate.documentVersion().equals(item.documentVersion())) fail("CITATION_VERSION_MISMATCH");
        boolean belongs = candidate.branches().stream()
                .filter(branch -> branch.inheritedClaims().stream().map(PatentClaimGraph.Claim::number).toList().equals(selectedBranch))
                .flatMap(branch -> branch.inheritedClaims().stream())
                .anyMatch(claim -> claim.original().originalRef().equals(item.originalRef())
                        && claim.original().originalSha().equals(item.originalSha())
                        && claim.original().jsonPointer().equals(item.jsonPointer())
                        && claim.original().byteStart() <= item.byteStart() && claim.original().byteEnd() >= item.byteEnd());
        if (!belongs) fail("CITATION_CANDIDATE_MISMATCH");
    }
    private static void evidence(OriginalClaimText.Evidence item, OriginalEvidenceVerifier originals) {
        if (item == null || blank(item.documentVersion()) || blank(item.originalRef()) || blank(item.jsonPointer())
                || item.originalSha() == null || !item.originalSha().matches("[a-f0-9]{64}")
                || item.spanSha() == null || !item.spanSha().matches("[a-f0-9]{64}") || blank(item.text())
                || item.byteStart() < 0 || item.byteEnd() <= item.byteStart() || !originals.verify(item))
            fail("ORIGINAL_EVIDENCE_INVALID");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void fail(String reason) { throw new IllegalArgumentException(reason); }
}
