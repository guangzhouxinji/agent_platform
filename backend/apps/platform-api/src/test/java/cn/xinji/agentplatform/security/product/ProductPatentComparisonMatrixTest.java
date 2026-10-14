package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patentcomparison.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix.*;

class ProductPatentComparisonMatrixTest {
    private ProductPatentClaimOriginal original(String ref, String text) throws Exception {
        byte[] raw = new ObjectMapper().writeValueAsString(Map.of("claimsText", text)).getBytes(StandardCharsets.UTF_8);
        String sha = ProductCapturedArchive.digest(raw);
        return new ProductPatentClaimOriginal(ref, raw, sha, "RAW_SHA256:" + sha, "claimsText", new ObjectMapper());
    }
    private Snapshot snapshot(ProductPatentClaimOriginal target, ProductPatentClaimOriginal candidate, List<PatentClaimGraph.Branch> branches) {
        return new Snapshot("task-current", 3, "goal-v3", "input-v3", "features-v1",
                target.evidence(0, target.text().length()).originalSha(), Purpose.TECHNICAL_SIMILARITY, "PATENT_FEATURE_MATRIX_V1",
                List.of(new Feature("F1", target.evidence(0, target.text().length()))),
                List.of(new Candidate("C1", candidate.evidence(0, candidate.text().length()).documentVersion(), branches)));
    }
    private Row row(List<Integer> branch, Relation relation, OriginalClaimText.Evidence claim,
                    List<String> same, List<String> differences, String uncertainty) {
        return new Row("C1", branch, "F1", relation, same, differences, "Only the supplied technical claim evidence.", uncertainty,
                claim == null ? List.of() : List.of(claim),
                relation == Relation.CORRESPONDING || relation == Relation.PARTIAL ? List.of(claim) : List.of());
    }
    @Test void exactEvidenceRowsAndExplicitUnknownOrUnanalyzedArePreserved() throws Exception {
        var target = original("target.json", "1. A valve."); var candidate = original("candidate.json", "1. A valve.");
        var graph = PatentClaimGraph.parse(candidate); var input = snapshot(target, candidate, graph.branches(1, 10));
        var evidence = graph.claims().iterator().next().original();
        OriginalEvidenceVerifier verifier = value -> target.matches(value) || candidate.matches(value);
        var matched = row(List.of(1), Relation.CORRESPONDING, evidence, List.of("Valve component"), List.of(), "Unreviewed machine suggestion");
        assertThat(validate(input, List.of(matched), verifier)).containsExactly(matched);
        var unknown = row(List.of(1), Relation.UNKNOWN, null, List.of(), List.of(), "Source context insufficient");
        assertThat(validate(input, List.of(unknown), verifier)).containsExactly(unknown);
        var pending = row(List.of(1), Relation.NOT_ANALYZED, null, List.of(), List.of(), "Execution cancelled");
        assertThat(validate(input, List.of(pending), verifier)).containsExactly(pending);
    }
    @Test void incompleteDuplicatePartialWithoutDifferenceAndUnknownWithoutReasonReject() throws Exception {
        var target = original("target.json", "1. A valve."); var candidate = original("candidate.json", "1. A valve.");
        var graph = PatentClaimGraph.parse(candidate); var input = snapshot(target, candidate, graph.branches(1, 10));
        var evidence = graph.claims().iterator().next().original();
        OriginalEvidenceVerifier verifier = value -> target.matches(value) || candidate.matches(value);
        var matched = row(List.of(1), Relation.CORRESPONDING, evidence, List.of("Valve"), List.of(), "Unreviewed");
        assertThatThrownBy(() -> validate(input, List.of(), verifier)).hasMessage("MATRIX_ROWS_INCOMPLETE");
        assertThatThrownBy(() -> validate(input, List.of(matched, matched), verifier)).hasMessage("MATRIX_ROW_BINDING_OR_DUPLICATE");
        assertThatThrownBy(() -> validate(input, List.of(row(List.of(1), Relation.PARTIAL, evidence, List.of("Valve"), List.of(), "")), verifier))
                .hasMessage("PARTIAL_DIFFERENCE_OR_EVIDENCE_MISSING");
        assertThatThrownBy(() -> validate(input, List.of(row(List.of(1), Relation.UNKNOWN, null, List.of(), List.of(), "")), verifier))
                .hasMessage("UNKNOWN_REASON_MISSING");
        var legalConclusion = new Row("C1", List.of(1), "F1", Relation.CORRESPONDING, List.of("Valve"), List.of(),
                "The product infringes the patent.", "", List.of(evidence), List.of(evidence));
        assertThatThrownBy(() -> validate(input, List.of(legalConclusion), verifier)).hasMessage("LEGAL_CONCLUSION_NOT_ALLOWED");
    }
    @Test void falseTextSpanHashVersionAndOtherAlternativeEvidenceReject() throws Exception {
        var target = original("target.json", "1. A valve.");
        var candidate = original("candidate.json", "1. A valve.\n2. Valve of claim 1 with a seal.\n3. Valve of any one of claims 1-2 with a handle.");
        var graph = PatentClaimGraph.parse(candidate); var input = snapshot(target, candidate, graph.branches(3, 10));
        var claims = graph.claims().stream().toList(); var claim2 = claims.get(1).original(); var claim3 = claims.get(2).original();
        OriginalEvidenceVerifier verifier = value -> target.matches(value) || candidate.matches(value);
        var legal = row(List.of(1, 2, 3), Relation.CORRESPONDING, claim3, List.of("Handle"), List.of(), "Unreviewed");
        var mixed = row(List.of(1, 3), Relation.CORRESPONDING, claim2, List.of("Seal"), List.of(), "Unreviewed");
        assertThatThrownBy(() -> validate(input, List.of(mixed, legal), verifier)).hasMessage("CITATION_CANDIDATE_MISMATCH");
        var forged = new OriginalClaimText.Evidence(claim3.documentVersion(), claim3.originalRef(), claim3.originalSha(),
                claim3.jsonPointer(), claim3.byteStart(), claim3.byteEnd(), "a".repeat(64), "Invented original");
        assertThat(candidate.matches(forged)).isFalse();
        assertThatThrownBy(() -> validate(input, List.of(row(List.of(1, 3), Relation.CORRESPONDING, forged,
                List.of("Invented"), List.of(), ""), legal), verifier)).hasMessage("ORIGINAL_EVIDENCE_INVALID");
    }
    @Test void omittedDependencyAndUnrelatedInheritedClaimCannotBecomeACompleteBranch() throws Exception {
        var target = original("target.json", "1. A valve.");
        var candidate = original("candidate.json", "1. A valve.\n2. Independent pump.\n3. Valve of claim 1 with seal.");
        var graph = PatentClaimGraph.parse(candidate); var claims = graph.claims().stream().toList();
        OriginalEvidenceVerifier verifier = value -> target.matches(value) || candidate.matches(value);
        for (var invalid : List.of(new PatentClaimGraph.Branch(3, List.of(claims.get(2))),
                new PatentClaimGraph.Branch(3, List.of(claims.get(1),claims.get(2))))) {
            var input = snapshot(target,candidate,List.of(invalid));
            assertThatThrownBy(() -> validate(input,List.of(),verifier)).hasMessage("CLAIM_BRANCH_INCOMPLETE");
        }
    }
}
