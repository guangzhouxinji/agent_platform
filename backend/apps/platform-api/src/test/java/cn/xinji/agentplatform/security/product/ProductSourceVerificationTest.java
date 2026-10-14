package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSourceVerificationTest {
    @Test void capturedSourceBecomesVerifiedEvidenceButNeverActive() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-verification-command-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            for (String name : List.of("V036__product_source_candidates.sql", "V088__product_source_capture_identity.sql",
                    "V039__product_source_verification.sql"))
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8/" + name)));
        }
        JdbcTemplate db = new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var candidates = new ProductSourceCandidates(db, transaction, mapper,
                new ProductRetentionPolicy("approved-metadata-policy", 1, "a".repeat(64),
                        Duration.ofHours(2)), Clock.systemUTC());
        Path root = Path.of("data/patents").toAbsolutePath();
        String manifestRef = "public-pilot-v1/manifest.json";
        var manifest = mapper.readTree(Files.readAllBytes(root.resolve(manifestRef)));
        String sourceVersion = manifest.path("datasetId").asText() + ":"
                + manifest.path("generatedAt").asText();
        var candidate = candidates.register(new ProductSourceCandidates.Declaration(
                "SURECHEMBL_PUBLIC_SEARCH", "capture-contract-1", sourceVersion,
                "https://surechembl.org/api", "EMBL-EBI; rights review pending",
                "rights-review", "b".repeat(64), Map.of("jurisdiction", List.of("CN")),
                List.of("TITLE"), Map.of("TITLE", List.of("QUERY")), List.of("TITLE"),
                false, false, true, true, true, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600)), "register-real-capture", "maintainer#certificate");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root.resolve(manifestRef))));
        var verification = new ProductSourceVerification(db, transaction, candidates,
                new ProductSourceEvidenceFiles(root, 10_000_000), mapper, Clock.systemUTC());
        var input = new ProductSourceVerification.Input(candidate.sourceId(), candidate.contractVersion(),
                candidate.version(), manifestRef, sha, "verify-real-capture");
        var receipt = verification.verify(input, "maintainer#certificate");
        assertThat(receipt.recordCount()).isEqualTo(44);
        assertThat(receipt.rawCount()).isEqualTo(47);
        assertThat(verification.verify(input, "maintainer#certificate")).isEqualTo(receipt);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class))
                .isEqualTo(1);
        assertThat(candidates.read(candidate.sourceId(), candidate.contractVersion()).status())
                .isEqualTo("CANDIDATE");
        var unrelated = candidates.register(new ProductSourceCandidates.Declaration(
                "OTHER_SOURCE", "capture-contract-2", sourceVersion,
                "https://another-source.example/api", "rights review pending", "rights-review",
                "b".repeat(64), Map.of("jurisdiction", List.of("CN")), List.of("TITLE"),
                Map.of("TITLE", List.of("QUERY")), List.of("TITLE"), false, false, true, true,
                true, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
                "register-unrelated", "maintainer#certificate");
        assertThatThrownBy(() -> verification.verify(new ProductSourceVerification.Input(
                unrelated.sourceId(), unrelated.contractVersion(), unrelated.version(), manifestRef,
                sha, "verify-unrelated"), "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> verification.verify(new ProductSourceVerification.Input(
                candidate.sourceId(), candidate.contractVersion(), candidate.version(), manifestRef,
                "0".repeat(64), "tampered-manifest"), "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class);
        candidates.withdraw(candidate.sourceId(), candidate.contractVersion(), 1,
                "withdraw-real-capture", "maintainer#certificate");
        assertThatThrownBy(() -> verification.verify(input, "maintainer#certificate"))
                .isInstanceOf(IllegalStateException.class);
    }
}
