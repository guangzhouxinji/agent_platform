package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.PublicInput;

import java.time.Instant;

public record RealBusinessCollectionTask(String taskId, String candidateId, String packageId,
        String title, String collectionBatchId, PublicInput publicInput, String assignmentState,
        String workflowState, String claimedBy, String organization, Instant claimedAt,
        long version, Instant createdAt, Instant updatedAt) {
    public record Seed(String taskId, String candidateId, String packageId, String title,
                       String collectionBatchId, PublicInput publicInput) { }
    public record Claim(String organization) { }
}
