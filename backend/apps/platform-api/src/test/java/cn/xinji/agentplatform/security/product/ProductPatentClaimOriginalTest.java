package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentClaimGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductPatentClaimOriginalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProductPatentClaimOriginal original(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); String sha = ProductCapturedArchive.digest(bytes);
        return new ProductPatentClaimOriginal("records/patent.json", bytes, sha, "RAW_SHA256:" + sha, "claimsText", JSON);
    }
    private ProductPatentClaimOriginal claims(String text) throws Exception {
        return original(JSON.writeValueAsString(Map.of("claimsText", text)));
    }
    @Test void escapedUnicodeNewlineAndQuotesHaveExactOriginalByteEvidence() {
        String raw = "{\"claimsText\":\"1. \\u7535池\\n\\\"A\\\" 😀\"}";
        var text = original(raw); assertThat(text.text()).isEqualTo("1. 电池\n\"A\" 😀");
        var span = text.evidence(3, 5); byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        assertThat(new String(Arrays.copyOfRange(bytes, span.byteStart(), span.byteEnd()), StandardCharsets.UTF_8))
                .isEqualTo("\\u7535池");
        assertThat(span.text()).isEqualTo("电池");
        assertThat(span.spanSha()).isEqualTo(ProductCapturedArchive.digest(Arrays.copyOfRange(bytes, span.byteStart(), span.byteEnd())));
        int emoji = text.text().indexOf("😀");
        assertThatThrownBy(() -> text.evidence(emoji, emoji + 1)).hasMessage("ORIGINAL_SPAN_INVALID");
        assertThat(text.evidence(emoji, emoji + 2).text()).isEqualTo("😀");
    }
    @Test void unicodeSurrogateEscapeMapsAsOneCodePointAndInvalidPairsReject() {
        var text = original("{\"claimsText\":\"1. \\ud83d\\ude00\"}");
        assertThat(text.evidence(3, 5).byteEnd() - text.evidence(3, 5).byteStart()).isEqualTo(12);
        assertThatThrownBy(() -> text.evidence(3, 4)).hasMessage("ORIGINAL_SPAN_INVALID");
        assertThatThrownBy(() -> original("{\"claimsText\":\"1. \\ud83d\"}"))
                .hasMessage("ORIGINAL_UNICODE_INVALID");
    }
    @Test void wrongVersionHashFieldDuplicatesAndBoundsReject() {
        byte[] bytes = "{\"claimsText\":\"1. Valve\"}".getBytes(StandardCharsets.UTF_8);
        String sha = ProductCapturedArchive.digest(bytes);
        assertThatThrownBy(() -> new ProductPatentClaimOriginal("r.json", bytes, sha, "latest", "claimsText", JSON))
                .hasMessage("ORIGINAL_BINDING_INVALID");
        assertThatThrownBy(() -> new ProductPatentClaimOriginal("r.json", bytes, "a".repeat(64), "RAW_SHA256:" + sha, "claimsText", JSON))
                .hasMessage("ORIGINAL_BINDING_INVALID");
        assertThatThrownBy(() -> original("{\"claimsText\":\"a\",\"claimsText\":\"b\"}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> original("{\"title\":\"A\"}"))
                .hasMessage("CLAIMS_ORIGINAL_UNAVAILABLE");
        assertThatThrownBy(() -> original(new String(bytes, StandardCharsets.UTF_8)).evidence(-1, 2))
                .hasMessage("ORIGINAL_SPAN_INVALID");
    }
    @Test void dependentAndAlternativeClaimsKeepSeparateInheritedBranches() throws Exception {
        var graph = PatentClaimGraph.parse(claims("1. A valve.\n2. The valve of claim 1 having a seal.\n"
                + "3. The valve of any one of claims 1-2 having a handle."));
        var branches = graph.branches(3, 10);
        assertThat(branches).hasSize(2);
        assertThat(branches.get(0).inheritedClaims()).extracting(PatentClaimGraph.Claim::number).containsExactly(1, 3);
        assertThat(branches.get(1).inheritedClaims()).extracting(PatentClaimGraph.Claim::number).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> graph.branches(3, 1)).hasMessage("CLAIM_BRANCH_BUDGET_EXCEEDED");
    }
    @Test void missingCycleUnnumberedAmbiguousAndUnrelatedOrDoNotBecomeIndependentClaims() throws Exception {
        assertThatThrownBy(() -> PatentClaimGraph.parse(claims("A valve without explicit numbering")))
                .hasMessage("CLAIM_NUMBER_CONFIRMATION_REQUIRED");
        var missing = PatentClaimGraph.parse(claims("2. A valve of claim 1."));
        assertThatThrownBy(() -> missing.branches(2, 10)).hasMessage("CLAIM_DEPENDENCY_MISSING");
        var cycle = PatentClaimGraph.parse(claims("1. Valve of claim 2.\n2. Valve of claim 1."));
        assertThatThrownBy(() -> cycle.branches(1, 10)).hasMessage("CLAIM_DEPENDENCY_CYCLE");
        assertThatThrownBy(() -> PatentClaimGraph.parse(claims("1. Valve.\n2. Valve.\n3. Valve of claims 1 and 2 open or shut.")))
                .hasMessage("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> PatentClaimGraph.parse(claims("1. Valve.\n2. Valve of the preceding claim.")))
                .hasMessage("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
    }
    @Test void realCollectedClaimsHaveReplayableOriginalSpansWithoutProductAdmissionBypass() throws Exception {
        Path records = Path.of("data/patents/patentsview-real-candidate-20261002-v3/records");
        assertThat(Files.isDirectory(records)).isTrue(); int originals = 0, parsed = 0, gaps = 0;
        try (var paths = Files.list(records)) {
            for (Path record : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(record); String sha = ProductCapturedArchive.digest(bytes);
                var original = new ProductPatentClaimOriginal("records/" + record.getFileName(), bytes, sha,
                        "RAW_SHA256:" + sha, "claimsText", JSON);
                var full = original.evidence(0, original.text().length());
                assertThat(full.spanSha()).isEqualTo(ProductCapturedArchive.digest(Arrays.copyOfRange(bytes, full.byteStart(), full.byteEnd())));
                originals++;
                try { var graph = PatentClaimGraph.parse(original); for (var claim : graph.claims()) graph.branches(claim.number(), 4000); parsed++; }
                catch (IllegalArgumentException limitation) {
                    assertThat(limitation.getMessage()).startsWith("CLAIM_"); gaps++;
                    System.out.println("REAL_CLAIMS_GAP document=" + record.getFileName() + " reason=" + limitation.getMessage());
                }
            }
        }
        assertThat(originals).isEqualTo(40); assertThat(parsed).isGreaterThan(0);
        assertThat(parsed + gaps).isEqualTo(originals);
        System.out.println("REAL_COLLECTED_CLAIMS_MAPPING originals=" + originals + " completeGraphs=" + parsed
                + " confirmationOrDependencyGaps=" + gaps + " scope=OFFLINE_PARSE_NOT_AUTHORIZED_PRODUCT_REQUEST");
    }
    @Test void deepDependenciesDoNotUseTheJavaCallStack() throws Exception {
        StringBuilder text = new StringBuilder("1. A valve.\n");
        for (int i = 2; i <= 2500; i++) text.append(i).append(". Valve of claim ").append(i - 1).append(".\n");
        var graph = PatentClaimGraph.parse(claims(text.toString()));
        assertThat(graph.branches(2500, 1).getFirst().inheritedClaims()).hasSize(2500);
        var forward = PatentClaimGraph.parse(claims("1. Valve of claim 2.\n2. A valve."));
        assertThatThrownBy(() -> forward.branches(1, 10)).hasMessage("CLAIM_REFERENCE_NOT_PRIOR");
    }
    @Test void unsupportedChineseAndMixedDependenciesRequireConfirmation() throws Exception {
        for (String reference : List.of("根据权利要求1", "如權利要求1所述", "of claim 1 and 权利要求2")) {
            assertThatThrownBy(() -> PatentClaimGraph.parse(claims("1. A valve.\n2. " + reference + "的装置。")))
                    .hasMessage("CLAIM_REFERENCE_CONFIRMATION_REQUIRED");
        }
    }
}
