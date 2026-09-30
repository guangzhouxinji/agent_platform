package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductSourceRecoveryFilesTest {
    @Test void realCapturedOriginalInventoryMustMatchRestoredRows() throws Exception {
        Path root = Path.of("data/patents").toAbsolutePath();
        String reference = "public-pilot-v1/manifest.json";
        byte[] bytes = Files.readAllBytes(root.resolve(reference));
        var manifest = new ObjectMapper().readTree(bytes);
        MessageDigest inventory = MessageDigest.getInstance("SHA-256");
        for (var raw : manifest.path("rawResponses")) {
            String name = raw.path("file").asText();
            String sha = raw.path("sha256").asText();
            inventory.update((name.length() + ":" + name + sha.toLowerCase(java.util.Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8));
        }
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-recovery-files;DB_CLOSE_DELAY=-1");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con4_source_verification(source_id VARCHAR(200),source_version VARCHAR(200),"
                + "manifest_ref VARCHAR(500),manifest_sha VARCHAR(64),records_sha VARCHAR(64),"
                + "inventory_sha VARCHAR(64),record_count BIGINT,raw_count BIGINT)");
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        db.update("INSERT INTO con4_source_verification VALUES(?,?,?,?,?,?,?,?)", "surechembl",
                manifest.path("datasetId").asText() + ":" + manifest.path("generatedAt").asText(),
                reference, manifestSha, manifest.path("recordsSha256").asText(),
                HexFormat.of().formatHex(inventory.digest()), manifest.path("recordCount").asLong(),
                manifest.path("rawResponses").size());
        var recovery = new ProductSourceRecoveryFiles(db, root, 10_000_000);
        recovery.verify();
        assertThat(recovery.references()).hasSize(49).contains(reference,
                "public-pilot-v1/records.json",
                "public-pilot-v1/raw/detail-US-20130157136-A1.json");
        db.update("UPDATE con4_source_verification SET inventory_sha=?", "0".repeat(64));
        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_verification SET inventory_sha=?,manifest_ref=?",
                "a".repeat(64), "public-pilot-v1/missing-original.json");
        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
    }
}
