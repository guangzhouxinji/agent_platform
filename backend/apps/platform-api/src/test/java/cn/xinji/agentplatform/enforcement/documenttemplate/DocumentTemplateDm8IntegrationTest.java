package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentTemplateDm8IntegrationTest {
    @TempDir Path files;

    @Test void realDm8AndOriginalFilesCloseTemplateTransactionAndFailureSemantics() throws Exception {
        String adminUrl = required("AGENT_ENF_ENGINEERING_ADMIN_URL");
        String admin = required("AGENT_ENF_ENGINEERING_ADMIN_USER");
        String password = required("AGENT_ENF_ENGINEERING_ADMIN_PASSWORD");
        String schema = random("DT");
        String schemaPassword = "T9a" + schema.toLowerCase() + "K7z";
        create(adminUrl, admin, password, schema, schemaPassword);
        try {
            var source = new DriverManagerDataSource(adminUrl, schema, schemaPassword);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration()));
            }
            var db = new JdbcTemplate(source);
            var store = new Dm8DocumentTemplateStore(db,
                    new TransactionTemplate(new DataSourceTransactionManager(source)),
                    JsonMapper.builder().findAndAddModules().build(), Clock.systemUTC());
            var service = new DocumentTemplateService(store, new LocalDocumentTemplateFiles(files),
                    (actor, organization, resource, version, action) -> { }, Clock.systemUTC(), Set.of("SimSun"));
            byte[] original = Files.readAllBytes(workspace().resolve(
                    "data/enforcement/document-template-v2-d-input-v1/project-functional-template.docx"));
            var input = new DraftInput("PROJECT-DM8-TEMPLATE", "项目执法事项核对记录", "REVIEW",
                    new Scope("ORG-ENF-PROJECT", "PROJECT_TEST", "PROJECT_DOCUMENT_REVIEW"), null,
                    List.of(new Field("task_ref", "STRING", true, false, null),
                            new Field("party_name", "STRING", true, false, null),
                            new Field("recorded_date", "DATE", true, false, null),
                            new Field("summary", "LONG_TEXT", true, false, null),
                            new Field("materials[].ref", "STRING", true, true, null),
                            new Field("materials[].note", "LONG_TEXT", false, true, null)), List.of("SimSun"));

            Receipt created = service.create("tester", "dm8-template-create", input,
                    "project-functional-template.docx", original);
            assertThat(service.create("tester", "dm8-template-create", input,
                    "project-functional-template.docx", original)).isEqualTo(created);
            var preview = service.preview("tester", "dm8-template-preview", created.templateId(), 1,
                    new PreviewInput(1, Map.of("task_ref", "PROJECT-TASK-1", "party_name", "测试主体",
                            "recorded_date", "2026-09-29", "summary", "真实 DM8 功能验证",
                            "materials", List.of(Map.of("ref", "MATERIAL-1", "note", "定位")))));
            assertThat(service.downloadPreview("tester", created.templateId(), preview.previewId())).isNotEmpty();
            assertThat(service.publish("tester", "dm8-template-publish", created.templateId(), 1,
                    new TransitionInput(1, "PROJECT-FUNCTIONAL-APPROVAL" )).state()).isEqualTo(State.PUBLISHED);
            assertThat(service.download("tester", created.templateId(), 1)).isEqualTo(original);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_receipt", Integer.class))
                    .isEqualTo(3);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_event", Integer.class))
                    .isEqualTo(3);

            assertThatThrownBy(() -> service.preview("tester", "dm8-template-stale", created.templateId(), 1,
                    new PreviewInput(2, Map.of()))).hasMessageContaining("Stale template revision");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_receipt", Integer.class))
                    .isEqualTo(3);
        } finally {
            remove(adminUrl, admin, password, schema);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
    private static String random(String prefix) {
        byte[] value = new byte[7]; new SecureRandom().nextBytes(value);
        return prefix + HexFormat.of().formatHex(value).toUpperCase();
    }
    private static Path migration() { return workspace().resolve("db/dm8/V074__enforcement_document_template.sql"); }
    private static Path workspace() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("db/dm8"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace unavailable");
        return root;
    }
    private static void create(String url, String user, String password, String schema, String secret) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
            command.execute("GRANT RESOURCE TO " + schema);
        }
    }
    private static void remove(String url, String user, String password, String schema) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password); var command = connection.createStatement()) {
            command.execute("DROP USER " + schema + " CASCADE");
        }
    }
}

