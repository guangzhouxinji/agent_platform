package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentTemplateServiceTest {
    private MemoryStore store;
    private MemoryFiles files;
    private DocumentTemplateService service;
    private final List<String> actions = new ArrayList<>();
    private byte[] docx;

    @BeforeEach void setUp() throws Exception {
        store = new MemoryStore(); files = new MemoryFiles();
        service = new DocumentTemplateService(store, files,
                (actor, organization, resource, version, action) -> actions.add(action),
                Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC), java.util.Set.of("SimSun"));
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("data/enforcement/document-template-v2-d-input-v1"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace test data unavailable");
        root = root.resolve("data");
        docx = Files.readAllBytes(root.resolve("enforcement/document-template-v2-d-input-v1/project-functional-template.docx"));
    }

    @Test void uploadsRendersRepeatRowsPublishesDisablesAndKeepsHistory() {
        Receipt draft = service.create("editor", "create-1", input("TPL-1"), "template.docx", docx);
        assertThat(draft.state()).isEqualTo(State.DRAFT);
        PreviewReceipt preview = service.preview("editor", "preview-1", "TPL-1", 1,
                new PreviewInput(1, sample()));
        String rendered = unzipText(service.downloadPreview("editor", "TPL-1", preview.previewId()));
        assertThat(rendered).contains("PROJECT-MATERIAL-A@1", "PROJECT-MATERIAL-B@1", "项目测试主体甲")
                .doesNotContain("{{");
        assertThat(rendered.split("PROJECT-MATERIAL-", -1)).hasSize(3);

        assertThat(service.publish("publisher", "publish-1", "TPL-1", 1,
                new TransitionInput(1, "PROJECT-APPROVAL-ONLY" )).state()).isEqualTo(State.PUBLISHED);
        assertThat(service.publish("publisher", "publish-1", "TPL-1", 1,
                new TransitionInput(1, "PROJECT-APPROVAL-ONLY" )).state()).isEqualTo(State.PUBLISHED);
        Receipt second = service.revise("editor", "revise-2", "TPL-1", input("TPL-1"), "template-v2.docx", docx);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(service.read("reader", "TPL-1", 1).state()).isEqualTo(State.PUBLISHED);
        assertThat(service.disable("publisher", "disable-1", "TPL-1", 1,
                new TransitionInput(1, "RETIRED-BY-PROJECT" )).state()).isEqualTo(State.DISABLED);
        assertThat(service.download("reader", "TPL-1", 1)).isEqualTo(docx);
        assertThat(actions).contains("CREATE", "PREVIEW", "PUBLISH", "EDIT", "READ", "DISABLE", "DOWNLOAD");
    }

    @Test void rejectsUnknownFieldsMissingFontStaleVersionAndIdempotencyConflict() {
        DraftInput missing = new DraftInput("TPL-X", "name", "REVIEW", scope(), null,
                input("TPL-X").fields().stream().filter(f -> !f.id().equals("summary")).toList(), List.of("SimSun"));
        assertThatThrownBy(() -> service.create("editor", "bad-field", missing, "x.docx", docx))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown placeholder");
        DraftInput font = new DraftInput("TPL-X", "name", "REVIEW", scope(), null,
                input("TPL-X").fields(), List.of("MissingFont"));
        assertThatThrownBy(() -> service.create("editor", "bad-font", font, "x.docx", docx))
                .hasMessageContaining("font unavailable");
        service.create("editor", "create", input("TPL-X"), "x.docx", docx);
        assertThatThrownBy(() -> service.preview("editor", "stale", "TPL-X", 1, new PreviewInput(2, sample())))
                .hasMessageContaining("Stale");
        assertThatThrownBy(() -> service.create("editor", "create", input("OTHER"), "x.docx", docx))
                .hasMessageContaining("receipt conflict");
    }

    @Test void rejectsExternalRelationshipAndLeavesNoOrphan() throws Exception {
        byte[] unsafe = rewrite(docx, "word/_rels/document.xml.rels",
                xml -> xml.replace("</Relationships>", "<Relationship Id=\"bad\" Type=\"x\" Target=\"https://example.test\" TargetMode=\"External\"/></Relationships>"));
        assertThatThrownBy(() -> service.create("editor", "unsafe", input("TPL-X"), "x.docx", unsafe))
                .hasMessageContaining("External relationship forbidden");
        assertThat(files.values).isEmpty();
    }

    @Test void removesStagedFileWhenReceiptPersistenceFailsAfterBusinessWrite() {
        store.failAfterAction = true;
        assertThatThrownBy(() -> service.create("editor", "receipt-fails", input("TPL-Z"), "x.docx", docx))
                .hasMessageContaining("receipt persistence failed");
        assertThat(files.values).isEmpty();
    }

    @Test void frozenMacroCompressionAndRepeatStructureNegativesHaveNoArtifacts() throws Exception {
        byte[] macro = extraEntry(docx, "word/vbaProject.bin", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> service.create("editor", "macro", input("TPL-MACRO"), "x.docx", macro))
                .hasMessageContaining("Macro or executable");
        byte[] oversized = extraEntry(docx, "word/oversized.xml", new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.create("editor", "bomb", input("TPL-BOMB"), "x.docx", oversized))
                .hasMessageContaining("entry too large");
        byte[] missingRows = rewrite(docx, "word/document.xml", xml -> xml.replace("w:tr", "w:p"));
        assertThatThrownBy(() -> service.create("editor", "bad-row", input("TPL-ROW"), "x.docx", missingRows))
                .hasMessageContaining("one table row");
        var duplicateFields = new ArrayList<>(input("TPL-DUP").fields());
        duplicateFields.add(duplicateFields.getFirst());
        var duplicate = new DraftInput("TPL-DUP", "name", "REVIEW", scope(), null, duplicateFields, List.of("SimSun"));
        assertThatThrownBy(() -> service.create("editor", "duplicate", duplicate, "x.docx", docx))
                .hasMessageContaining("Duplicate field");
        assertThat(store.revisions).isEmpty();
        assertThat(store.receipts).isEmpty();
        assertThat(files.values).isEmpty();
    }

    @Test void frozenLongChineseRepeatAndHeaderContentRenderWithoutPlaceholders() {
        service.create("editor", "long-create", input("TPL-LONG"), "x.docx", docx);
        var values = new HashMap<String, Object>(sample());
        String longText = "长中文段落：项目验证，不是正式文书。".repeat(400);
        values.put("summary", longText);
        var preview = service.preview("editor", "long-preview", "TPL-LONG", 1, new PreviewInput(1, values));
        String xml = unzipText(service.downloadPreview("editor", "TPL-LONG", preview.previewId()));
        assertThat(xml).contains(longText, "PROJECT-MATERIAL-A@1", "PROJECT-MATERIAL-B@1").doesNotContain("{{");
    }

    private static byte[] extraEntry(byte[] value, String name, byte[] added) throws Exception {
        var result = new java.io.ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(value));
             var output = new java.util.zip.ZipOutputStream(result)) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                output.write(input.readAllBytes()); output.closeEntry();
            }
            output.putNextEntry(new java.util.zip.ZipEntry(name)); output.write(added); output.closeEntry();
        }
        return result.toByteArray();
    }


    private static DraftInput input(String id) {
        return new DraftInput(id, "项目执法事项核对记录", "DOCUMENT_REVIEW", scope(), null, List.of(
                new Field("task_ref", "STRING", true, false, null),
                new Field("party_name", "STRING", true, false, null),
                new Field("recorded_date", "DATE", true, false, "yyyy-MM-dd"),
                new Field("summary", "LONG_TEXT", true, false, null),
                new Field("materials[].ref", "STRING", true, true, null),
                new Field("materials[].note", "LONG_TEXT", false, true, null)), List.of("SimSun"));
    }
    private static Scope scope() { return new Scope("ORG-ENF-PROJECT", "PROJECT_TEST", "PROJECT_DOCUMENT_REVIEW"); }
    private static Map<String, Object> sample() {
        Map<String, Object> values = new HashMap<>(); values.put("task_ref", "PROJECT-TASK-DOCUMENT-01");
        values.put("party_name", "项目测试主体甲"); values.put("recorded_date", "2026-09-29");
        values.put("summary", "长中文文本，不是正式案件。");
        values.put("materials", List.of(Map.of("ref", "PROJECT-MATERIAL-A@1", "note", "说明一"),
                Map.of("ref", "PROJECT-MATERIAL-B@1", "note", "说明二"))); return values;
    }
    private static String unzipText(byte[] value) {
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(value))) {
            java.util.zip.ZipEntry entry; while ((entry = zip.getNextEntry()) != null)
                if (entry.getName().equals("word/document.xml")) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new AssertionError("document.xml missing");
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static byte[] rewrite(byte[] value, String target, java.util.function.UnaryOperator<String> change) throws Exception {
        var result = new java.io.ByteArrayOutputStream();
        try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(value));
             var output = new java.util.zip.ZipOutputStream(result)) {
            java.util.zip.ZipEntry entry; while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); byte[] bytes = input.readAllBytes();
                output.write(entry.getName().equals(target) ? change.apply(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes);
                output.closeEntry();
            }
        } return result.toByteArray();
    }

    private static final class MemoryFiles implements DocumentTemplateFiles {
        private final Map<String, byte[]> values = new HashMap<>();
        public String put(String category, String sha, byte[] content) { String key = category + "/" + sha; values.put(key, content.clone()); return key; }
        public byte[] read(String key) { byte[] value = values.get(key); if (value == null) throw new IllegalStateException("file unavailable"); return value.clone(); }
        public void delete(String key) { values.remove(key); }
    }
    private static final class MemoryStore implements DocumentTemplateStore {
        private final Map<String, List<Revision>> revisions = new HashMap<>();
        private final Map<String, PreviewArtifact> previews = new HashMap<>();
        private final Map<String, Stored> receipts = new HashMap<>();
        private boolean failAfterAction;
        public Optional<Revision> latest(String id) { var all = revisions.getOrDefault(id, List.of()); return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast()); }
        public Optional<Revision> revision(String id, long revision) { return revisions.getOrDefault(id, List.of()).stream().filter(v -> v.revision() == revision).findFirst(); }
        public List<Summary> list(String organization) { return revisions.entrySet().stream().map(e -> e.getValue().getLast()).filter(v -> v.scope().organization().equals(organization)).map(v -> new Summary(v.templateId(), v.revision(), v.state(), v.name(), organization, v.fileSha256())).toList(); }
        public void add(Revision revision) { revisions.computeIfAbsent(revision.templateId(), ignored -> new ArrayList<>()).add(revision); }
        public void transition(String id, long number, State expected, State target, String evidence) { var all = revisions.get(id); for (int i=0;i<all.size();i++) { Revision v=all.get(i); if(v.revision()==number) all.set(i,new Revision(v.templateId(),v.revision(),target,v.name(),v.stage(),v.scope(),v.approvalRef(),v.fields(),v.fontNames(),v.fileKey(),v.fileName(),v.fileSha256(),v.createdBy(),v.createdAt(),evidence)); } }
        public void addPreview(PreviewArtifact value) { previews.put(value.receipt().previewId(), value); }
        public Optional<PreviewArtifact> preview(String id, String previewId) { return Optional.ofNullable(previews.get(previewId)).filter(v -> v.receipt().templateId().equals(id)); }
        public <T> T command(String key,String fingerprint,String actor,String operation,Supplier<T> action,Class<T> type) { Stored prior=receipts.get(key); if(prior!=null){ if(!prior.fingerprint.equals(fingerprint)||!prior.actor.equals(actor)||!prior.operation.equals(operation)) throw new IllegalArgumentException("receipt conflict"); return type.cast(prior.result); } T result=action.get(); if(failAfterAction) throw new IllegalStateException("receipt persistence failed"); receipts.put(key,new Stored(fingerprint,actor,operation,result)); return result; }
        private record Stored(String fingerprint,String actor,String operation,Object result) { }
    }
}
