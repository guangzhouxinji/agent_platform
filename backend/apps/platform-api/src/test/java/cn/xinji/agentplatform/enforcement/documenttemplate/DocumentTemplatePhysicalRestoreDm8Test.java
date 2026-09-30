package cn.xinji.agentplatform.enforcement.documenttemplate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentTemplatePhysicalRestoreDm8Test {
    @EnabledIfEnvironmentVariable(named = "ENF_DOCUMENT_RESTORED_DB_URL", matches = ".+")
    @Test void independentDbAndFileRestorePreservesTemplateReceiptsAndArtifacts() throws Exception {
        String sourceUrl = required("ENF_DOCUMENT_SOURCE_DB_URL");
        String restoredUrl = required("ENF_DOCUMENT_RESTORED_DB_URL");
        Path sourceRoot = Path.of(required("ENF_DOCUMENT_SOURCE_FILE_ROOT")).toRealPath();
        Path restoredRoot = Path.of(required("ENF_DOCUMENT_RESTORED_FILE_ROOT")).toRealPath();
        assertThat(restoredUrl).isNotEqualTo(sourceUrl);
        assertThat(restoredRoot).isNotEqualTo(sourceRoot);

        String user = required("ENF_DOCUMENT_DB_USER");
        String password = required("ENF_DOCUMENT_DB_PASSWORD");
        String templateId = required("ENF_DOCUMENT_TEMPLATE_ID");
        String expectedSha = required("ENF_DOCUMENT_EXPECTED_SHA256");
        JdbcTemplate source = jdbc(sourceUrl, user, password);
        JdbcTemplate restored = jdbc(restoredUrl, user, password);

        Snapshot before = snapshot(source, templateId);
        Snapshot after = snapshot(restored, templateId);
        assertThat(after).isEqualTo(before);
        assertThat(after.state()).isEqualTo("DISABLED");
        assertThat(after.originalSha()).isEqualTo(expectedSha);
        assertThat(after.receipts()).isEqualTo(4);
        assertThat(after.events()).isEqualTo(4);
        assertThat(after.operations()).containsExactlyInAnyOrder("CREATE", "PREVIEW", "PUBLISH", "DISABLE");

        assertThat(sha256(restoredRoot.resolve(after.originalKey()))).isEqualTo(expectedSha);
        assertThat(sha256(restoredRoot.resolve(after.previewKey()))).isEqualTo(after.previewSha());
        assertThat(sha256(sourceRoot.resolve(before.originalKey()))).isEqualTo(expectedSha);
        try (var paths = Files.walk(restoredRoot)) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList()).isEmpty();
        }
        System.out.printf("ENF_DOCUMENT_RESTORE template=%s state=%s receipts=%d events=%d originalSha=%s previewSha=%s%n",
                templateId, after.state(), after.receipts(), after.events(), after.originalSha(), after.previewSha());
    }

    private static Snapshot snapshot(JdbcTemplate db, String id) {
        var revision = db.queryForMap("SELECT state,file_sha256,file_key FROM enf9_document_template_revision "
                + "WHERE template_id=? AND revision=1", id);
        var preview = db.queryForMap("SELECT artifact_sha256,file_key FROM enf9_document_template_preview "
                + "WHERE template_id=? AND template_revision=1", id);
        Integer receipts = db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_receipt "
                + "WHERE result_payload LIKE ?", Integer.class, "%" + id + "%");
        Integer events = db.queryForObject("SELECT COUNT(*) FROM enf9_document_template_event e "
                + "JOIN enf9_document_template_receipt r ON r.request_key=e.request_key WHERE r.result_payload LIKE ?",
                Integer.class, "%" + id + "%");
        List<String> operations = db.query("SELECT operation FROM enf9_document_template_receipt "
                + "WHERE result_payload LIKE ?", (rs, row) -> rs.getString(1), "%" + id + "%");
        return new Snapshot(String.valueOf(revision.get("STATE")), String.valueOf(revision.get("FILE_SHA256")),
                String.valueOf(revision.get("FILE_KEY")), String.valueOf(preview.get("ARTIFACT_SHA256")),
                String.valueOf(preview.get("FILE_KEY")), receipts, events, operations);
    }

    private static JdbcTemplate jdbc(String url, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Snapshot(String state, String originalSha, String originalKey, String previewSha,
                            String previewKey, int receipts, int events, List<String> operations) { }
}
