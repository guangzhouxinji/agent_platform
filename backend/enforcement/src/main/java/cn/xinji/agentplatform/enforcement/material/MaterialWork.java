package cn.xinji.agentplatform.enforcement.material;

import java.time.Instant;
import java.util.List;

/** Product contract. Input records are supplied through a rights-checked source port. */
public final class MaterialWork {
    private MaterialWork() { }

    public record Source(String id, String organization, String rightsRef, long epoch,
                         Instant expiresAt, List<String> uses, boolean active, String credentialRef,
                         long configVersion) { }
    public record Observation(String id, String sourceId, String recordId, String sourceVersion, String organization,
                              String text, String textSha256, String materialMention, long version) { }
    public record ActorMention(String observationId, int ordinal, String role, String literal,
                               String locator, String businessCode) { }
    public record ActorInput(String role, String literal, String businessCode) { }
    public record Rule(String id, long revision, String organization, String sourceId, String literal,
                       String basisRef, String state, String publisher) { }
    public record RuleReview(String positiveObservationId, String negativeObservationId,
                             String positiveSha256, String negativeSha256) { }
    public record Clue(String id, String observationId, String ruleId, long ruleRevision,
                       String organization, String state, long version) { }
    public record RuleRef(String ruleId, long revision) { }
    public record Revision(String clueId, long version, String actor, String reason,
                           String originalText, String correctedText, String materialGap) { }
    public record Material(String id, String clueId, String organization, String sha256,
                           String fileKey, String rightsRef, Instant expiresAt, List<String> uses,
                           String collector, String collectedAt,
                           String state, long version) { }
    public record MaterialAction(String materialId, long revision, String action, String actor,
                                 String sourceRef, String reason, String fileSha256, Instant occurredAt) { }
    public record Receipt(String requestId, String operation, String resultId, long resultVersion) { }
}
