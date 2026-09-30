package cn.xinji.agentplatform.enforcement.jobpack;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class JobPackContract {
    private JobPackContract() { }

    public enum FactType { TASK, SOURCE_RIGHTS, ACTOR_POSITION, APPROVAL, HUMAN_RECEIPT, BUSINESS_EVALUATION }
    public enum State { DESIGN_READY, COLLECTING, WAITING_DATA, WAITING_REAL_HUMAN_ACTION, READY, RUNNING, PASSED, FAILED }
    public enum Verification { PENDING, VERIFIED, REJECTED }

    public record Evidence(String ref, String sha256, String fileKey, String originalName) { }
    public record Fact(FactType type, Map<String, String> fields, List<Evidence> evidence,
                       String submittedBy, Instant submittedAt, Verification verification,
                       String verifiedBy, Instant verifiedAt, String verificationReason) { }
    public record Trigger(String requestId, String taskId, long packageRevision,
                          String packageSha256, Instant requestedAt) { }
    public record Execution(String result, String receiptRef, String detail, String actor, Instant completedAt) { }
    public record PublicInput(String candidateId, String sourceUrl, String sourceName, String publishedAt,
                              String capturedAt, String rawSha256, String rawFileKey, String originalName,
                              String collectionBatchId, String collectionDecision,
                              Map<String, String> publicFacts, String claimedBy) { }
    public record Snapshot(String id, long revision, long contentRevision, String organization,
                           String title, String purpose, State state, List<Fact> facts,
                           List<String> findings, String sha256, Trigger trigger, Execution execution,
                           PublicInput publicInput) { }
    public record Receipt(String requestId, String operation, String packageId, long revision, State state) { }
    public record Design(String version, State state, List<String> requiredFacts,
                         List<String> evaluationMetrics, List<String> expectedOutputs,
                         List<String> fixedNegativeCases) { }
}
