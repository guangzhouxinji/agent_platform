package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Registry transaction C with actual originals; H2/component actor do not constitute physical/product identity I. */
class ProductHupdRegistryVerificationTest {
    @Test void nativeCaptureUsesTheSameVerificationTransactionAndRecoveryClosureWithoutActivation() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:hupd-registry-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            for (String name : List.of("V036__product_source_candidates.sql", "V088__product_source_capture_identity.sql", "V039__product_source_verification.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/" + name)));
        }
        var db = new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var clock = Clock.systemUTC();
        var candidates = new ProductSourceCandidates(db, transaction, mapper,
                new ProductRetentionPolicy("component-policy", 1, "a".repeat(64), Duration.ofHours(2)), clock);
        Path root = Path.of("data/patents").toAbsolutePath();
        String reference = "hupd-real-candidate-20261002-v2/manifest.json";
        byte[] bytes = Files.readAllBytes(root.resolve(reference));
        var manifest = mapper.readTree(bytes);
        String version = manifest.path("sourceVersion").asText() + ":" + manifest.path("collectedAt").asText();
        var candidate = candidates.register(new ProductSourceCandidates.Declaration(
                manifest.path("sourceId").asText(), "native-capture-contract", version,
                manifest.path("originalUrl").asText(), "HUPD; contradictory license statements preserved",
                reference, ProductCapturedArchive.digest(bytes), Map.of("jurisdiction", List.of("US")),
                List.of("TITLE"), Map.of("TITLE", List.of("QUERY")), List.of("TITLE"), true, true, true, true, true,
                Instant.parse(manifest.path("collectedAt").asText()), clock.instant().plusSeconds(3600)),
                "register-hupd-component", "component-maintainer");
        var verification = new ProductSourceVerification(db, transaction, candidates,
                new ProductSourceEvidenceFiles(root, 10_000_000), mapper, clock);
        var request = new ProductSourceVerification.Input(candidate.sourceId(), candidate.contractVersion(),
                candidate.version(), reference, ProductCapturedArchive.digest(bytes), "verify-hupd-component");
        var receipt = verification.verify(request, "component-maintainer");
        assertThat(receipt.recordCount()).isEqualTo(20);
        assertThat(receipt.rawCount()).isEqualTo(27);
        assertThat(verification.verify(request, "component-maintainer")).isEqualTo(receipt);
        String originalDeclaration = db.queryForObject("SELECT declaration_json FROM con4_source_contract", String.class);
        var changedDeclaration = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(originalDeclaration);
        changedDeclaration.put("collectedAt", clock.instant().minusSeconds(1).toString());
        db.update("UPDATE con4_source_contract SET declaration_json=?", mapper.writeValueAsString(changedDeclaration));
        assertThatThrownBy(() -> verification.verify(request, "component-maintainer")).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_contract SET declaration_json=?", originalDeclaration);
        assertThat(candidates.read(candidate.sourceId(), candidate.contractVersion()).status()).isEqualTo("CANDIDATE");
        var recovery = new ProductSourceRecoveryFiles(db, root, 10_000_000);
        assertThat(recovery.references()).hasSize(28).contains(reference, "hupd-real-candidate-20261002-v2/raw/member-0020.json");
        db.update("UPDATE con4_source_verification SET inventory_sha=?", "0".repeat(64));
        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_verification SET inventory_sha=?", receipt.inventorySha());
        recovery.verify();
        db.update("UPDATE con4_source_verification SET source_version=?", "older-version");
        assertThatThrownBy(recovery::verify).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class)).isEqualTo(1);
    }
}
