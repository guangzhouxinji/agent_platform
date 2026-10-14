package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonMatrix;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductComparisonLineageCodecTest {
    final ProductComparisonJson json = new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final ProductComparisonLineageCodec codec = new ProductComparisonLineageCodec(json);
    final String sha = "a".repeat(64), requestSha = "b".repeat(64);
    final RowKey key = new RowKey("candidate", List.of(1), "F1");
    final PatentComparisonMatrix.Row row = new PatentComparisonMatrix.Row("candidate", List.of(1), "F1",
            PatentComparisonMatrix.Relation.UNKNOWN, List.of(), List.of(), "gap", "not enough evidence", List.of(), List.of());

    @Test void originalReportBytesRemainUnchangedAndProofFeedsExistingPolicy() {
        var capture = captured(false); String raw = report(List.of(row)); String digest = ProductComparisonJson.hash(raw);
        var encoded = codec.encode(capture, raw, digest);
        assertThat(codec.encode(captured(true), raw, digest)).isEqualTo(encoded);
        var source = codec.decode(encoded, capture, raw, digest);
        assertThat(source.rows().getFirst().rowSha()).isEqualTo(json.sha(row));
        var next = new Snapshot("task", "next", "c".repeat(64), "comparison", capture.snapshot().facts(), Set.of(key));
        assertThat(plan(capture.snapshot(), next, List.of(source)).carried()).hasSize(1);
        assertThat(ProductComparisonJson.hash(raw)).isEqualTo(digest);
        assertThat(raw).doesNotContain("dependencyVersion", "dependencies", "carryForwardFrom");
    }
    @Test void proofHashAndReportBytesAreBothChecked() {
        var capture = captured(false); String raw = report(List.of(row)); String digest = ProductComparisonJson.hash(raw);
        var encoded = codec.encode(capture, raw, digest);
        assertThatThrownBy(() -> codec.decode(new ProductComparisonLineageCodec.Encoded(encoded.raw() + " ", encoded.sha()), capture, raw, digest))
                .hasMessage("LINEAGE_ORIGINAL_BINDING_INVALID");
        assertThatThrownBy(() -> codec.decode(encoded, capture, raw + " ", digest)).hasMessage("LINEAGE_ORIGINAL_BINDING_INVALID");
    }
    @Test void rehashedProofCannotChangeRequestFactsDependenciesOrVersion() {
        var capture = captured(false); String raw = report(List.of(row)); String digest = ProductComparisonJson.hash(raw);
        var encoded = codec.encode(capture, raw, digest);
        for (String changed : List.of(encoded.raw().replace(requestSha, "d".repeat(64)),
                encoded.raw().replace("DEPENDENCY_ORIGINAL_V1", "DEPENDENCY_ORIGINAL_V2"),
                encoded.raw().replace("\"TARGET\"", "\"METHOD\""),
                encoded.raw().replace("\"dependencies\":[", "\"dependencies\":[{\"type\":\"FEATURE\",\"id\":\"extra\"},"))) {
            assertThatThrownBy(() -> codec.decode(new ProductComparisonLineageCodec.Encoded(changed, ProductComparisonJson.hash(changed)), capture, raw, digest))
                    .hasMessage("LINEAGE_ORIGINAL_BINDING_INVALID");
        }
    }
    @Test void missingDuplicateAndWrongCandidateRowsCannotMintProof() {
        for (var rows : List.of(List.<PatentComparisonMatrix.Row>of(), List.of(row, row),
                List.of(new PatentComparisonMatrix.Row("other", List.of(1), "F1", row.relation(), row.sameAspects(), row.differences(),
                        row.reasoning(), row.uncertainty(), row.analyzedRange(), row.citations())))) {
            String raw = report(rows);
            assertThatThrownBy(() -> codec.encode(captured(false), raw, ProductComparisonJson.hash(raw))).hasMessage("LINEAGE_ORIGINAL_BINDING_INVALID");
        }
    }
    @Test void legacyReportAloneCannotBecomeDependencyEvidence() {
        String raw = report(List.of(row));
        assertThatThrownBy(() -> codec.decode(null, captured(false), raw, ProductComparisonJson.hash(raw)))
                .hasMessage("LINEAGE_ORIGINAL_BINDING_INVALID");
    }
    private ProductComparisonLineageFacts.Captured captured(boolean reverse) {
        List<FactKey> keys = new ArrayList<>();
        for (Type type : Type.values()) keys.add(new FactKey(type, type == Type.FEATURE ? "F1" : type == Type.CANDIDATE ? "candidate" : "global"));
        if (reverse) Collections.reverse(keys);
        Map<FactKey, String> facts = new LinkedHashMap<>(); keys.forEach(key -> facts.put(key, sha));
        return new ProductComparisonLineageFacts.Captured(new Snapshot("task", "comparison", sha, null, facts, Set.of(key)), requestSha);
    }
    private String report(List<PatentComparisonMatrix.Row> rows) {
        var at = Instant.parse("2026-10-11T00:00:00Z");
        return json.canonical(new ProductComparisonMatrixResults.Package("task", "comparison", "candidate", sha, "attempt", 1,
                "report", 1, "PATENT_FEATURE_MATRIX_V1", "worker", "configuration", sha, sha, at, at.plusSeconds(60), rows));
    }
}
