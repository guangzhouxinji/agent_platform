package cn.xinji.agentplatform.enforcement;

import static cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.application.EnforcementEvidenceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnforcementEvidenceServiceTest {
    private final FakeStore store = new FakeStore();
    private final EnforcementEvidenceService service = new EnforcementEvidenceService(store,
            Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"), ZoneOffset.UTC),
            new Policy(Set.of("text/plain", "application/pdf"), Set.of("text/plain", "application/pdf"), 1024));

    @Test void computesDigestAndKeepsUnknownCollectionInformationExplicit() {
        byte[] bytes = "original evidence bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EvidenceDetail result = service.create("actor", "org", new CreateCommand("现场记录", "DOCUMENT", "lead-1", null,
                null, null, "原收集信息未随授权包提供", null, null, "record.txt", "text/plain",
                Base64.getEncoder().encodeToString(bytes), null), "request-1");

        assertThat(result.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(store.content.digestAlgorithm()).isEqualTo("SHA-256");
        assertThat(store.content.digest()).hasSize(64);
        assertThat(store.content.bytes()).containsExactly(bytes);
        assertThat(store.evidence.collection().collector()).isNull();
        assertThat(store.evidence.collection().missingReason()).isEqualTo("原收集信息未随授权包提供");
    }

    @Test void rejectsInventedCollectionDefaultsAndUnsupportedContent() {
        assertThatThrownBy(() -> service.create("actor", "org", new CreateCommand("记录", "DOCUMENT", "lead-1", null,
                null, null, null, null, null, "record.bin", "application/octet-stream", "YQ==", null), "request"))
                .isInstanceOf(EvidenceException.class).hasMessage("COLLECTION_INFO_REQUIRED");
        assertThatThrownBy(() -> service.create("actor", "org", new CreateCommand("记录", "DOCUMENT", "lead-1", null,
                null, null, "未知", null, null, "record.bin", "application/octet-stream", "YQ==", null), "request"))
                .isInstanceOf(EvidenceException.class).hasMessage("MEDIA_TYPE_NOT_ALLOWED");
    }

    private static final class FakeStore implements Store {
        EvidenceInput evidence; ContentInput content;
        @Override public EvidencePage list(String organization, String leadId, String type, String status, int page, int pageSize) { return new EvidencePage(List.of(), 0, page, pageSize); }
        @Override public EvidenceDetail detail(String organization, String evidenceId) { throw new UnsupportedOperationException(); }
        @Override public EvidenceDetail create(String organization, String actor, String requestId, Instant now, EvidenceInput evidence, ContentInput content) {
            this.evidence = evidence; this.content = content;
            return new EvidenceDetail("evidence-1", organization, evidence.name(), evidence.type(), "PENDING_VERIFICATION", evidence.relatedLeadId(), evidence.relatedCaseRef(), evidence.sourceChannel(), evidence.collection(), evidence.physicalDescription(), evidence.custodyInformation(), 1,
                    List.of(new ContentVersion(1, null, "ORIGINAL", "controlled://evidence-1", content.fileName(), content.mediaType(), (long) content.bytes().length, content.digestAlgorithm(), content.digest(), content.previewable(), actor, now)),
                    List.of(), List.of(), now, now);
        }
        @Override public EvidenceDetail revise(String organization, String evidenceId, int expectedVersion, String actor, String requestId, Instant now, MetadataInput metadata, String reason) { throw new UnsupportedOperationException(); }
        @Override public EvidenceDetail addDerived(String organization, String evidenceId, int originalVersion, String derivedKind, String actor, String requestId, Instant now, ContentInput content) { throw new UnsupportedOperationException(); }
        @Override public EvidenceDetail changeStatus(String organization, String evidenceId, int expectedVersion, String status, String reason, String actor, String requestId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public ContentResource content(String organization, String evidenceId, int version, String action, String actor, String requestId, Instant now) { throw new UnsupportedOperationException(); }
    }
}
