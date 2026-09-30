package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentTemplateStoreTest {
    private JdbcTemplate db;
    private Dm8DocumentTemplateStore store;

    @BeforeEach void setUp() throws Exception {
        var source = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("doc-template-" + System.nanoTime() + ";MODE=Oracle").build();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("db/dm8/V074__enforcement_document_template.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace migration unavailable");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(root.resolve("db/dm8/V074__enforcement_document_template.sql")));
        }
        db = new JdbcTemplate(source);
        store = new Dm8DocumentTemplateStore(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test void persistsRevisionPreviewTransitionAndAtomicIdempotencyEvidence() {
        Revision draft = revision();
        Receipt first = store.command("request-1", "a".repeat(64), "editor", "CREATE", () -> {
            store.add(draft); return new Receipt("TPL-1", 1, State.DRAFT, draft.fileSha256());
        }, Receipt.class);
        Receipt replay = store.command("request-1", "a".repeat(64), "editor", "CREATE",
                () -> { throw new AssertionError("must replay"); }, Receipt.class);
        assertThat(replay).isEqualTo(first);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_receipt", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_event", Integer.class)).isEqualTo(1);

        PreviewReceipt receipt = new PreviewReceipt("00000000-0000-0000-0000-000000000001", "TPL-1", 1,
                draft.fileSha256(), "2".repeat(64), DocumentTemplateService.RENDERER_VERSION, List.of("test"));
        store.addPreview(new PreviewArtifact(receipt, "previews/p.docx", "editor", draft.createdAt()));
        assertThat(store.preview("TPL-1", receipt.previewId())).contains(new PreviewArtifact(
                receipt, "previews/p.docx", "editor", draft.createdAt()));
        store.transition("TPL-1", 1, State.DRAFT, State.PUBLISHED, "PROJECT-APPROVAL");
        assertThat(store.revision("TPL-1", 1).orElseThrow().state()).isEqualTo(State.PUBLISHED);
        assertThat(store.list("ORG-1")).extracting(Summary::templateId).containsExactly("TPL-1");
    }

    @Test void rollsBackBusinessWriteWhenCommandFailsAndRejectsConflictingReplay() {
        assertThatThrownBy(() -> store.command("request-fail", "b".repeat(64), "editor", "CREATE", () -> {
            store.add(revision()); throw new IllegalStateException("fail after write");
        }, Receipt.class)).hasMessageContaining("fail after write");
        assertThat(store.latest("TPL-1")).isEmpty();
        store.command("request-ok", "c".repeat(64), "editor", "CREATE", () -> {
            store.add(revision()); return new Receipt("TPL-1", 1, State.DRAFT, "1".repeat(64));
        }, Receipt.class);
        assertThatThrownBy(() -> store.command("request-ok", "d".repeat(64), "editor", "CREATE",
                () -> null, Receipt.class)).hasMessageContaining("receipt conflict");
    }

    private static Revision revision() {
        return new Revision("TPL-1", 1, State.DRAFT, "Project template", "REVIEW",
                new Scope("ORG-1", "PROJECT", "PROJECT_REVIEW"), null,
                List.of(new Field("task_ref", "STRING", true, false, null)), List.of("SimSun"),
                "templates/t.docx", "t.docx", "1".repeat(64), "editor",
                Instant.parse("2026-09-29T12:00:00Z"), null);
    }
}
