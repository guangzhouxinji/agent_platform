package cn.xinji.agentplatform.enforcement.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class EnforcementEvidenceService {
    private final Store store;
    private final Clock clock;
    private final Policy policy;

    public EnforcementEvidenceService(Store store, Clock clock, Policy policy) {
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
    }

    public EvidencePage list(String actor, String organization, String leadId, String type, String status, int page, int pageSize) {
        context(actor, organization);
        if (page < 1 || pageSize < 1 || pageSize > 100) throw failure("INVALID_INPUT", "page");
        return store().list(organization, normalized(leadId), normalized(type), normalized(status), page, pageSize);
    }

    public EvidenceDetail detail(String actor, String organization, String evidenceId) {
        context(actor, organization);
        return store().detail(organization, required(evidenceId, "evidenceId"));
    }

    public EvidenceDetail create(String actor, String organization, CreateCommand command, String requestId) {
        context(actor, organization);
        if (command == null) throw failure("INVALID_INPUT", "body");
        String leadId = required(command.relatedLeadId(), "relatedLeadId");
        String name = required(command.name(), "name");
        String type = required(command.type(), "type");
        CollectionInfo collection = collection(command.collector(), command.collectionTime(), command.collectionMissingReason());
        byte[] bytes = decode(command.contentBase64());
        String sourceRef = normalized(command.controlledResourceRef());
        if ((bytes == null) == (sourceRef == null)) throw failure("CONTENT_SOURCE_REQUIRED", "contentBase64");
        if (bytes != null) validateContent(command.mediaType(), bytes);
        Instant now = clock.instant();
        ContentInput content = bytes == null
                ? new ContentInput(null, null, sourceRef, null, null, null, false)
                : new ContentInput(required(command.fileName(), "fileName"), required(command.mediaType(), "mediaType"), null,
                        bytes, "SHA-256", sha256(bytes), policy.previewableMediaTypes().contains(command.mediaType()));
        return store().create(organization, actor, requestId, now,
                new EvidenceInput(name, type, leadId, normalized(command.relatedCaseRef()),
                        bytes == null ? "AUTHORIZED_LOCAL" : "AUTHORIZED_UPLOAD", collection,
                        normalized(command.physicalDescription()), normalized(command.custodyInformation())), content);
    }

    public EvidenceDetail revise(String actor, String organization, String evidenceId, RevisionCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() < 1 || blank(command.reason())) throw failure("INVALID_INPUT", "body");
        CollectionInfo collection = collection(command.collector(), command.collectionTime(), command.collectionMissingReason());
        return store().revise(organization, required(evidenceId, "evidenceId"), command.expectedVersion(), actor,
                requestId, clock.instant(), new MetadataInput(required(command.name(), "name"), required(command.type(), "type"),
                        collection, normalized(command.physicalDescription()), normalized(command.custodyInformation())), command.reason().trim());
    }

    public EvidenceDetail addDerivedVersion(String actor, String organization, String evidenceId,
                                            DerivedCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.originalVersion() < 1 || !Set.of("OCR_TEXT", "TRANSCODE", "PREVIEW", "OTHER").contains(command.derivedKind())) {
            throw failure("INVALID_INPUT", "body");
        }
        byte[] bytes = decode(command.contentBase64());
        validateContent(command.mediaType(), bytes);
        ContentInput input = new ContentInput(required(command.fileName(), "fileName"), required(command.mediaType(), "mediaType"),
                null, bytes, "SHA-256", sha256(bytes), policy.previewableMediaTypes().contains(command.mediaType()));
        return store().addDerived(organization, required(evidenceId, "evidenceId"), command.originalVersion(), command.derivedKind(),
                actor, requestId, clock.instant(), input);
    }

    public EvidenceDetail changeStatus(String actor, String organization, String evidenceId,
                                       StatusCommand command, String requestId) {
        context(actor, organization);
        if (command == null || command.expectedVersion() < 1 || blank(command.reason())
                || !Set.of("VERIFIED", "EXCLUDED", "ARCHIVED").contains(command.status())) {
            throw failure("INVALID_INPUT", "body");
        }
        return store().changeStatus(organization, required(evidenceId, "evidenceId"), command.expectedVersion(), command.status(),
                command.reason().trim(), actor, requestId, clock.instant());
    }

    public ContentResource content(String actor, String organization, String evidenceId, int version,
                                   String action, String requestId) {
        context(actor, organization);
        if (version < 1 || !Set.of("PREVIEW", "DOWNLOAD").contains(action)) throw failure("INVALID_INPUT", "version");
        ContentResource content = store().content(organization, required(evidenceId, "evidenceId"), version,
                action, actor, requestId, clock.instant());
        if ("PREVIEW".equals(action) && !content.previewable()) throw failure("PREVIEW_UNAVAILABLE", "version");
        if (content.bytes() == null) throw failure("CONTENT_NOT_STORED", "version");
        return content;
    }

    private void validateContent(String mediaType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw failure("EMPTY_CONTENT", "contentBase64");
        if (bytes.length > policy.maxBytes()) throw failure("FILE_TOO_LARGE", "contentBase64");
        if (!policy.allowedMediaTypes().contains(required(mediaType, "mediaType"))) throw failure("MEDIA_TYPE_NOT_ALLOWED", "mediaType");
    }

    private static CollectionInfo collection(String collector, Instant time, String missingReason) {
        String who = normalized(collector);
        String reason = normalized(missingReason);
        if ((who == null || time == null) && reason == null) throw failure("COLLECTION_INFO_REQUIRED", "collectionMissingReason");
        if ((who == null) != (time == null)) throw failure("COLLECTION_INFO_INCOMPLETE", "collector");
        return new CollectionInfo(who, time, reason);
    }

    private static byte[] decode(String value) {
        if (blank(value)) return null;
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException error) { throw failure("INVALID_BASE64", "contentBase64"); }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private Store store() { if (store == null) throw failure("EVIDENCE_STORE_UNAVAILABLE", null); return store; }
    private static void context(String actor, String organization) { required(actor, "actor"); required(organization, "organization"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalized(String value) { return blank(value) ? null : value.trim(); }
    private static String required(String value, String field) { if (blank(value)) throw failure("INVALID_INPUT", field); return value.trim(); }
    public static EvidenceException failure(String code, String field) { return new EvidenceException(code, field); }

    public interface Store {
        EvidencePage list(String organization, String leadId, String type, String status, int page, int pageSize);
        EvidenceDetail detail(String organization, String evidenceId);
        EvidenceDetail create(String organization, String actor, String requestId, Instant now, EvidenceInput evidence, ContentInput content);
        EvidenceDetail revise(String organization, String evidenceId, int expectedVersion, String actor, String requestId,
                              Instant now, MetadataInput metadata, String reason);
        EvidenceDetail addDerived(String organization, String evidenceId, int originalVersion, String derivedKind,
                                  String actor, String requestId, Instant now, ContentInput content);
        EvidenceDetail changeStatus(String organization, String evidenceId, int expectedVersion, String status,
                                    String reason, String actor, String requestId, Instant now);
        ContentResource content(String organization, String evidenceId, int version, String action,
                                String actor, String requestId, Instant now);
    }

    public record Policy(Set<String> allowedMediaTypes, Set<String> previewableMediaTypes, long maxBytes) {
        public Policy { allowedMediaTypes = Set.copyOf(allowedMediaTypes); previewableMediaTypes = Set.copyOf(previewableMediaTypes); }
    }
    public record CreateCommand(String name, String type, String relatedLeadId, String relatedCaseRef, String collector,
                                Instant collectionTime, String collectionMissingReason, String physicalDescription,
                                String custodyInformation, String fileName, String mediaType, String contentBase64,
                                String controlledResourceRef) {}
    public record RevisionCommand(int expectedVersion, String name, String type, String collector, Instant collectionTime,
                                  String collectionMissingReason, String physicalDescription, String custodyInformation, String reason) {}
    public record DerivedCommand(int originalVersion, String derivedKind, String fileName, String mediaType, String contentBase64) {}
    public record StatusCommand(int expectedVersion, String status, String reason) {}
    public record EvidenceInput(String name, String type, String relatedLeadId, String relatedCaseRef, String sourceChannel,
                                CollectionInfo collection, String physicalDescription, String custodyInformation) {}
    public record MetadataInput(String name, String type, CollectionInfo collection, String physicalDescription, String custodyInformation) {}
    public record CollectionInfo(String collector, Instant collectionTime, String missingReason) {}
    public record ContentInput(String fileName, String mediaType, String controlledResourceRef, byte[] bytes,
                               String digestAlgorithm, String digest, boolean previewable) {}
    public record EvidenceSummary(String evidenceId, String name, String type, String status, String relatedLeadId,
                                  String sourceChannel, int recordVersion, int latestContentVersion, Instant updatedAt) {}
    public record EvidencePage(List<EvidenceSummary> items, long total, int page, int pageSize) { public EvidencePage { items = List.copyOf(items); } }
    public record EvidenceDetail(String evidenceId, String organizationRef, String name, String type, String status,
                                 String relatedLeadId, String relatedCaseRef, String sourceChannel, CollectionInfo collection,
                                 String physicalDescription, String custodyInformation, int recordVersion,
                                 List<ContentVersion> versions, List<MetadataHistory> metadataHistory,
                                 List<StatusEvent> statusHistory, Instant createdAt, Instant updatedAt) {
        public EvidenceDetail { versions = List.copyOf(versions); metadataHistory = List.copyOf(metadataHistory); statusHistory = List.copyOf(statusHistory); }
    }
    public record ContentVersion(int version, Integer originalVersion, String derivedKind, String controlledResourceRef,
                                 String fileName, String mediaType, Long size, String digestAlgorithm, String digest,
                                 boolean previewable, String createdBy, Instant createdAt) {}
    public record MetadataHistory(int recordVersion, String name, String type, String reason, String changedBy, Instant changedAt) {}
    public record StatusEvent(String fromStatus, String toStatus, String reason, String actor, Instant occurredAt) {}
    public record ContentResource(String fileName, String mediaType, boolean previewable, byte[] bytes) {}
    public static final class EvidenceException extends RuntimeException {
        private final String code; private final String field;
        public EvidenceException(String code, String field) { super(code); this.code = code; this.field = field; }
        public String code() { return code; } public String field() { return field; }
    }
}
