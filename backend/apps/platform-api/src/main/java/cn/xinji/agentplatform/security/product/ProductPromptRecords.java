package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Persistence values are not evidence of identity, permission or policy approval. */
final class ProductPromptRecords {
    private ProductPromptRecords() { }
    enum Operation { CREATE, SAVE, COPY }
    record Workspace(String id, String organization, long scopeVersion, String state,
                     String policyRef, String policySha, String registrySha, Instant validUntil) {
        Workspace {
            uuid(id); text(organization); text(policyRef); sha(policySha); sha(registrySha); time(validUntil);
            if (scopeVersion < 1 || !("ACTIVE".equals(state) || "CLOSED".equals(state))) throw invalid();
        }
    }
    record Head(String promptId, String workspaceId, PromptRevision.Kind kind, String revisionId,
                long generation, String scalarSha, Instant containerUntil, Instant controlUntil) { }
    record Header(String revisionId, String promptId, long number, String contentSha, String recordSha,
                  Instant submittedAt, Instant bodyUntil, Instant auditUntil, String bodyState) { }
    record Receipt(String id, String workspaceId, String issuer, String subject, Operation operation,
                   String keySha, String requestSha, String promptId, String revisionId, long generation,
                   String contentSha, String resultSha, Instant submittedAt, Instant auditUntil) { }
    record Write(String workspaceId, PromptRevision revision, long expectedGeneration, Operation operation,
                 String issuer, String subject, String keySha, String requestSha,
                 Instant bodyUntil, Instant auditUntil) {
        Write {
            uuid(workspaceId); uuid(revision.reference().promptId()); uuid(revision.reference().revisionId());
            text(issuer); text(subject); sha(keySha); sha(requestSha); time(bodyUntil); time(auditUntil);
            Instant at = revision.provenance().modifiedAt(); time(at);
            if (operation == null || expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE
                    || revision.reference().version() != expectedGeneration + 1
                    || (operation == Operation.SAVE) != (expectedGeneration > 0)
                    || !subject.equals(revision.provenance().modifiedBy())
                    || !bodyUntil.isAfter(at) || auditUntil.isBefore(bodyUntil)
                    || bodyUntil.isAfter(at.plus(Duration.ofDays(90)))
                    || auditUntil.isAfter(at.plus(Duration.ofDays(180)))) throw invalid();
        }
    }
    record Control(String promptId, String workspaceId, PromptRevision.Kind kind, long generation,
                   String revisionId, String scalarSha, String state, String intentId, Operation operation,
                   String keySha, String requestSha, String beforeSha, String afterSha, Long targetGeneration,
                   String targetRevisionId, Instant intentUntil, Instant containerUntil, Instant controlUntil,
                   String cleanupRequestId) {
        Control {
            if("CLEANUP_PENDING".equals(state)) {
                uuid(cleanupRequestId);
                if(generation<1||revisionId==null||intentId!=null||operation!=null||keySha!=null||requestSha!=null
                        ||beforeSha!=null||afterSha!=null||targetGeneration!=null||targetRevisionId!=null||intentUntil!=null)throw unavailable();
            }else if(cleanupRequestId!=null||!("READY".equals(state)||"PENDING".equals(state)))throw unavailable();
        }
    }
    record PreparedWrite(Write write, Head before, Head after, Header header, Receipt receipt) { }
    record Pending(String promptId, String workspaceId, PromptRevision.Kind kind, long expectedGeneration,
                   String expectedRevisionId, String beforeSha, String afterSha, String targetRevisionId,
                   String intentId, Operation operation, String keySha, String requestSha,
                   Instant intentUntil, Instant containerUntil, Instant controlUntil) {
        Pending {
            uuid(promptId); uuid(workspaceId); uuid(targetRevisionId); uuid(intentId);
            sha(beforeSha); sha(afterSha); sha(keySha); sha(requestSha);
            time(intentUntil); time(containerUntil); time(controlUntil);
            if (kind == null || operation == null || expectedGeneration < 0 || expectedGeneration == Long.MAX_VALUE
                    || expectedGeneration == 0 && expectedRevisionId != null
                    || expectedGeneration > 0 && expectedRevisionId == null
                    || (operation == Operation.SAVE) != (expectedGeneration > 0)
                    || intentUntil.isAfter(controlUntil) || containerUntil.isAfter(controlUntil)) throw invalid();
            if (expectedRevisionId != null) uuid(expectedRevisionId);
        }
    }
    record Page(java.util.List<Header> revisions, long total) {
        Page { revisions = java.util.List.copyOf(revisions); }
    }
    static void uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw invalid();
    }
    static void sha(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    static void text(String value) { if (value == null || value.isBlank()) throw invalid(); }
    static void time(Instant value) {
        if (value == null || !value.equals(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))) throw invalid();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid prompt persistence value"); }
    static IllegalStateException unavailable() { return new IllegalStateException("Prompt persistence unavailable"); }
    static IllegalStateException conflict() { return new IllegalStateException("Prompt revision or original-key conflict"); }
}
