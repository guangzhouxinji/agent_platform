package cn.xinji.agentplatform.enforcement.documentdraft;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract;

/** ENF-14 final-product contract. Ports resolve real versions and current authority. */
public final class DocumentDraftContract {
    private DocumentDraftContract() { }
    public enum Type { FACT_RECORD, CONDUCT_REVIEW, PENALTY_INPUT_REVIEW }
    public record ObjectVersion(String id, long revision, String sha256) {
        public ObjectVersion {
            if (id == null || id.isBlank() || revision < 1 || sha256 == null
                    || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Exact object version required");
        }
    }
    public record Input(String organization, Type type, ObjectVersion caseInput,
            ObjectVersion template, ObjectVersion rule, List<ObjectVersion> evidence, List<ObjectVersion> legalReferences,
            ObjectVersion conduct, ObjectVersion penalty) {
        public Input {
            if (organization == null || organization.isBlank() || type == null || caseInput == null
                    || template == null || rule == null || evidence == null || evidence.isEmpty()
                    || legalReferences == null || (type != Type.FACT_RECORD && legalReferences.isEmpty()))
                throw new IllegalArgumentException("Document input context required");
            evidence = List.copyOf(evidence);
            legalReferences = List.copyOf(legalReferences);
        }
    }
    public record Diagnostic(String field, String code, boolean blocking) { }
    public record Resolved(Input manifest, boolean templatePublished, boolean rulePublished,
            boolean exactTemplateRuleBinding, boolean legalConfirmed, boolean conductReviewed,
            boolean penaltyReviewed, DocumentRuleContract.Trial mapping, DocumentTemplateProfile profile,
            DocumentRuleContract.Rule ruleSnapshot, String region, String stage,
            java.util.Map<String,DocumentProfileEngine.SourceDatum> sources) {
        public Resolved { sources=java.util.Map.copyOf(sources); }
    }
    public record Preparation(Input manifest, String inputManifestHash, boolean ready, boolean reviewRequired,
            List<DocumentRuleContract.Mapped> fields, List<Diagnostic> diagnostics,DocumentProfileEngine.Content content) {
        public Preparation { fields=List.copyOf(fields); diagnostics=List.copyOf(diagnostics); }
    }
    public record Draft(String id, long revision, Input manifest, String contentJson,
            String contentSha256, String inputManifestHash, String reviewState, List<Diagnostic> missingItems,
            String author, boolean systemAssisted, String requestId, Instant createdAt) {
        public Draft { missingItems=List.copyOf(missingItems); }
    }
    public record Difference(String field,String beforeJson,String afterJson) { }
    public record Candidate(String id,String draftId,long baseRevision,String baseContentSha256,Input manifest,
            String inputManifestHash,String contentJson,String contentSha256,List<Diagnostic> diagnostics,
            List<Difference> differences,String actor,String requestId,Instant createdAt) {
        public Candidate {diagnostics=List.copyOf(diagnostics);differences=List.copyOf(differences);}
    }
    public record Review(String draftId,long revision,String contentSha256,String outcome,String reason,
            String actor,String requestId,Instant createdAt) { }
    public record ExportArtifact(String id,String draftId,long revision,Input manifest,String contentSha256,
            String rendererVersion,String artifactSha256,String actor,String requestId,Instant createdAt) { }
    public record Receipt(String requestId, String operation, String draftId, long revision,
            String contentSha256,String candidateId,String exportId) {
        public Receipt(String requestId,String operation,String draftId,long revision,String contentSha256) {
            this(requestId,operation,draftId,revision,contentSha256,null,null);
        }
        public Receipt(String requestId,String operation,String draftId,long revision,String contentSha256,String candidateId) {
            this(requestId,operation,draftId,revision,contentSha256,candidateId,null);
        }
    }
    public interface ExportRenderer {
        String version();
        byte[] render(String actor,Draft draft);
    }
    public interface Inputs {
        Resolved resolve(String actor, Input input);
        void assertCurrent(String actor, Input manifest);
        void assertReadable(String actor, Input manifest);
    }
    public interface Access {
        void require(String actor, String organization, String resourceId, long revision, String action);
    }
    public interface Store {
        Optional<Draft> read(String id, long revision);
        List<Draft> history(String id);
        void append(Draft draft, long expectedRevision);
        default Optional<Candidate> candidate(String id) {throw new IllegalStateException("Candidate store required");}
        default List<Candidate> candidates(String draftId) {throw new IllegalStateException("Candidate store required");}
        default void addCandidate(Candidate candidate) {throw new IllegalStateException("Candidate store required");}
        default List<Review> reviews(String draftId,long revision) {throw new IllegalStateException("Review store required");}
        default void addReview(Review review) {throw new IllegalStateException("Review store required");}
        default Optional<ExportArtifact> export(String id) {throw new IllegalStateException("Export store required");}
        default byte[] exportBytes(String id) {throw new IllegalStateException("Export store required");}
        default void addExport(ExportArtifact artifact,byte[] bytes) {throw new IllegalStateException("Export store required");}
        Receipt command(String key, String fingerprint, String actor, Supplier<Receipt> transaction);
    }
}
