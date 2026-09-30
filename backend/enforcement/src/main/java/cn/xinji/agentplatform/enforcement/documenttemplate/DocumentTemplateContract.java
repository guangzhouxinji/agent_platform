package cn.xinji.agentplatform.enforcement.documenttemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DocumentTemplateContract {
    private DocumentTemplateContract() { }

    public enum State { DRAFT, PUBLISHED, DISABLED }

    public record Scope(String organization, String region, String procedure) { }
    public record Field(String id, String type, boolean required, boolean repeat, String format) { }
    public record DraftInput(String templateId, String name, String stage, Scope scope,
                             String approvalRef, List<Field> fields, List<String> fontNames) { }
    public record PreviewInput(long expectedRevision, Map<String, Object> values) { }
    public record TransitionInput(long expectedRevision, String evidenceRef) { }
    public record Revision(String templateId, long revision, State state, String name, String stage,
                           Scope scope, String approvalRef, List<Field> fields, List<String> fontNames,
                           String fileKey, String fileName, String fileSha256, String createdBy,
                           Instant createdAt, String transitionEvidenceRef) { }
    public record Summary(String templateId, long currentRevision, State state, String name,
                          String organization, String fileSha256) { }
    public record Receipt(String templateId, long revision, State state, String fileSha256) { }
    public record PreviewReceipt(String previewId, String templateId, long revision,
                                 String templateSha256, String artifactSha256,
                                 String rendererVersion, List<String> diagnostics) { }
    public record PreviewArtifact(PreviewReceipt receipt, String fileKey, String createdBy, Instant createdAt) { }
}
