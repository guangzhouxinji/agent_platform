package cn.xinji.agentplatform.consultation.patenttask.authorization;

import java.time.Instant;
import java.util.Optional;

/** Consultation owns its persisted task and metadata scope; authorization remains separate. */
public interface ProductTaskDirectory {
    record Task(String id, String organization, String ownerIssuer, String ownerSubject,
                String state, long version, long authorizationVersion, String scopeId, long scopeVersion,
                Instant expiresAt) {
        public String dataScope() { return scopeId + ":" + scopeVersion; }
    }
    record Initial(String organization, String name, String issuer, String subject,
                   String retentionRef, long retentionVersion, String retentionEvidenceSha, Instant expiresAt,
                   String actor, String requestId, String requestSha) { }
    record Creation(String organization, String issuer, String subject, String retentionRef,
                    long retentionVersion, String retentionEvidenceSha, Instant expiresAt,
                    String requestId, String requestSha) { }
    record Receipt(String requestSha, Task task, String grantId, boolean current) { }

    Optional<Task> find(String id);
    Optional<Receipt> receipt(String requestId);
    Task insertInitial(Initial initial);
    void recordReceipt(Initial initial, Task task, String grantId);
    Task insert(Creation creation);
    Optional<Receipt> creationReceipt(String requestId);
    void recordCreationReceipt(Creation creation, Task task, String grantId);
}
