package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.Observation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Verified bytes and projections do not establish current source-use admission. */
final class ProductTrademarkCapture {
    private ProductTrademarkCapture() { }
    enum Admission { NOT_CHECKED }
    enum ValueState { PRESENT, NULL, MISSING }
    record Artifact(String ref, String sha256, long byteLength, byte[] bytes) {
        Artifact { bytes = Objects.requireNonNull(bytes).clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    record Mapping(String id, String version, String sha256) { }
    record Input(String sourceId, String contractVersion, String registeredSourceVersion,
                 Artifact manifest, Artifact records, List<Artifact> originals, Mapping mapping,
                 Artifact natureEvidence) {
        Input { originals = List.copyOf(originals); }
    }
    record FieldEvidence(String field, String pointer, ValueState state, String rawRef, String rawSha256,
                         Integer tokenStart, Integer tokenEndExclusive, String tokenSha256) { }
    record NatureClaim(String code, String evidenceRef, String evidenceSha256, Admission admission) { }
    record ProjectedTrademarkRecord(Observation observation, String registeredSourceVersion,
            String rawRef, String rawSha256, long rawBytes, Instant capturedAt,
            List<FieldEvidence> fields, String normalizedSha256, String relationSha256) {
        ProjectedTrademarkRecord { fields = List.copyOf(fields); }
    }
    record VerifiedTrademarkCapture(String sourceId, String contractVersion, String registeredSourceVersion,
            String manifestRef, String manifestSha256, String recordsSha256, String inventorySha256,
            String bindingSetSha256, Mapping mapping, NatureClaim nature,
            List<ProjectedTrademarkRecord> records, Admission admission) {
        VerifiedTrademarkCapture { records = List.copyOf(records); }
    }
    static final class InvalidCapture extends IllegalArgumentException {
        InvalidCapture() { super("INVALID_TRADEMARK_CAPTURE"); }
    }
    static void require(boolean valid) { if (!valid) throw new InvalidCapture(); }
}
