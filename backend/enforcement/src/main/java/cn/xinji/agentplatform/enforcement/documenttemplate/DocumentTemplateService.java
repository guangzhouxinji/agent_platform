package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DocumentTemplateService {
    public static final String RENDERER_VERSION = "OOXML-DETERMINISTIC/v1";
    private final DocumentTemplateStore store;
    private final DocumentTemplateFiles files;
    private final DocumentTemplateAccess access;
    private final Clock clock;
    private final Set<String> availableFonts;
    private final DocumentTemplateDocx docx = new DocumentTemplateDocx();

    public DocumentTemplateService(DocumentTemplateStore store, DocumentTemplateFiles files,
                                   DocumentTemplateAccess access, Clock clock, Set<String> availableFonts) {
        this.store = store; this.files = files; this.access = access; this.clock = clock;
        this.availableFonts = Set.copyOf(availableFonts);
    }

    public Receipt create(String actor, String requestKey, DraftInput input, String fileName, byte[] content) {
        requireDraft(input, fileName); docx.validate(content, input.fields()); requireFonts(input.fontNames());
        String id = blank(input.templateId()) ? UUID.randomUUID().toString() : input.templateId();
        access.require(actor, input.scope().organization(), id, 1, "CREATE");
        String sha = DocumentTemplateDigests.sha256(content);
        String fingerprint = digest("CREATE", id, input, sha);
        String[] staged = new String[1];
        try {
            return store.command(requestKey, fingerprint, actor, "CREATE", () -> {
                if (store.latest(id).isPresent()) throw new IllegalStateException("Template already exists");
                String key = files.put("templates", sha, content); staged[0] = key;
                Revision revision = revision(id, 1, input, key, fileName, sha, actor);
                store.add(revision); return receipt(revision);
            }, Receipt.class);
        } catch (RuntimeException failure) { cleanup(staged[0], failure); throw failure; }
    }

    public Receipt revise(String actor, String requestKey, String id, DraftInput input,
                          String fileName, byte[] content) {
        Revision prior = requireRevision(id, 0); requireDraft(input, fileName);
        require(prior.scope().organization().equals(input.scope().organization()), "Organization cannot change");
        docx.validate(content, input.fields()); requireFonts(input.fontNames());
        access.require(actor, prior.scope().organization(), id, prior.revision(), "EDIT");
        String sha = DocumentTemplateDigests.sha256(content);
        String fingerprint = digest("REVISE", id, input, sha, prior.revision());
        String[] staged = new String[1];
        try {
            return store.command(requestKey, fingerprint, actor, "REVISE", () -> {
                Revision current = requireRevision(id, 0);
                require(current.revision() == prior.revision(), "Stale template revision");
                String key = files.put("templates", sha, content); staged[0] = key;
                Revision next = revision(id, current.revision() + 1, input, key, fileName, sha, actor);
                store.add(next); return receipt(next);
            }, Receipt.class);
        } catch (RuntimeException failure) { cleanup(staged[0], failure); throw failure; }
    }

    public PreviewReceipt preview(String actor, String requestKey, String id, long revision, PreviewInput input) {
        Revision template = requireRevision(id, revision);
        require(input != null && input.expectedRevision() == revision, "Stale template revision");
        access.require(actor, template.scope().organization(), id, revision, "PREVIEW");
        String fingerprint = digest("PREVIEW", id, revision, input.values(), template.fileSha256());
        String[] staged = new String[1];
        try {
            return store.command(requestKey, fingerprint, actor, "PREVIEW", () -> {
                byte[] original = checkedFile(template.fileKey(), template.fileSha256());
                requireFonts(template.fontNames());
                byte[] rendered = docx.render(original, template.fields(), input.values());
                String artifactSha = DocumentTemplateDigests.sha256(rendered);
                String fileKey = files.put("previews", artifactSha, rendered); staged[0] = fileKey;
                String previewId = UUID.randomUUID().toString();
                PreviewReceipt receipt = new PreviewReceipt(previewId, id, revision, template.fileSha256(),
                        artifactSha, RENDERER_VERSION, List.of("FUNCTIONAL_PREVIEW_NOT_FORMAL_DOCUMENT"));
                store.addPreview(new PreviewArtifact(receipt, fileKey, actor, clock.instant())); return receipt;
            }, PreviewReceipt.class);
        } catch (RuntimeException failure) { cleanup(staged[0], failure); throw failure; }
    }

    public Receipt publish(String actor, String requestKey, String id, long revision, TransitionInput input) {
        return transition(actor, requestKey, id, revision, input, State.DRAFT, State.PUBLISHED, "PUBLISH");
    }

    public Receipt disable(String actor, String requestKey, String id, long revision, TransitionInput input) {
        return transition(actor, requestKey, id, revision, input, State.PUBLISHED, State.DISABLED, "DISABLE");
    }

    public Revision read(String actor, String id, long revision) {
        Revision result = requireRevision(id, revision);
        access.require(actor, result.scope().organization(), id, result.revision(), "READ"); return result;
    }
    public List<Summary> list(String actor, String organization) {
        access.require(actor, organization, "*", 1, "READ"); return store.list(organization);
    }
    public byte[] download(String actor, String id, long revision) {
        Revision result = requireRevision(id, revision);
        access.require(actor, result.scope().organization(), id, revision, "DOWNLOAD");
        return checkedFile(result.fileKey(), result.fileSha256());
    }
    public byte[] downloadPreview(String actor, String id, String previewId) {
        PreviewArtifact artifact = store.preview(id, previewId).orElseThrow(() -> new IllegalArgumentException("Preview unavailable"));
        Revision template = requireRevision(id, artifact.receipt().revision());
        access.require(actor, template.scope().organization(), id, template.revision(), "DOWNLOAD");
        return checkedFile(artifact.fileKey(), artifact.receipt().artifactSha256());
    }

    private Receipt transition(String actor, String key, String id, long revision, TransitionInput input,
                               State expected, State target, String operation) {
        Revision before = requireRevision(id, revision);
        require(input != null && input.expectedRevision() == revision && !blank(input.evidenceRef()), "Transition evidence required");
        access.require(actor, before.scope().organization(), id, revision, operation);
        checkedFile(before.fileKey(), before.fileSha256()); docx.validate(downloadUnchecked(before), before.fields()); requireFonts(before.fontNames());
        return store.command(key, digest(operation, id, revision, input.evidenceRef()), actor, operation, () -> {
            Revision current = requireRevision(id, revision); require(current.state() == expected, "Template state conflict");
            store.transition(id, revision, expected, target, input.evidenceRef());
            return new Receipt(id, revision, target, current.fileSha256());
        }, Receipt.class);
    }

    private Revision requireRevision(String id, long revision) {
        return (revision == 0 ? store.latest(id) : store.revision(id, revision))
                .orElseThrow(() -> new IllegalArgumentException("Template unavailable"));
    }
    private byte[] downloadUnchecked(Revision revision) { return checkedFile(revision.fileKey(), revision.fileSha256()); }
    private byte[] checkedFile(String key, String expected) {
        byte[] bytes = files.read(key); require(expected.equals(DocumentTemplateDigests.sha256(bytes)), "Template file integrity failure"); return bytes;
    }
    private Revision revision(String id, long revision, DraftInput input, String key, String name, String sha, String actor) {
        return new Revision(id, revision, State.DRAFT, input.name(), input.stage(), input.scope(), input.approvalRef(),
                List.copyOf(input.fields()), List.copyOf(input.fontNames()), key, name, sha, actor, clock.instant(), null);
    }
    private void requireDraft(DraftInput input, String fileName) {
        require(input != null && !blank(input.name()) && !blank(input.stage()) && input.scope() != null
                && !blank(input.scope().organization()) && !blank(input.scope().region()) && !blank(input.scope().procedure()), "Template metadata required");
        require(fileName != null && fileName.toLowerCase().endsWith(".docx"), "DOCX file required");
        require(input.fields() != null && !input.fields().isEmpty() && input.fontNames() != null && !input.fontNames().isEmpty(), "Fields and fonts required");
    }
    private void requireFonts(List<String> fonts) { for (String font : fonts) require(availableFonts.contains(font), "Renderer font unavailable: " + font); }
    private void cleanup(String key, RuntimeException failure) {
        if (key == null) return;
        try { files.delete(key); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }
    private static Receipt receipt(Revision value) { return new Receipt(value.templateId(), value.revision(), value.state(), value.fileSha256()); }
    private static String digest(Object... parts) { return DocumentTemplateDigests.sha256(java.util.Arrays.deepToString(parts)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
