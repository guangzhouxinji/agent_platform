package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Captured source enters the physical restore through formal candidate and verification services. */
final class ProductTaskDm8EvidenceCopy implements AutoCloseable {
    private final Path restored;

    ProductTaskDm8EvidenceCopy(JdbcTemplate db, TransactionTemplate tx) throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        Path source = Path.of("data/patents").toAbsolutePath();
        String manifestRef = "public-pilot-v1/manifest.json";
        byte[] manifestBytes = Files.readAllBytes(source.resolve(manifestRef));
        var manifest = mapper.readTree(manifestBytes);
        String version = manifest.path("datasetId").asText() + ":" + manifest.path("generatedAt").asText();
        var candidates = new ProductSourceCandidates(db, tx, mapper,
                new ProductRetentionPolicy("source-metadata-policy", 1, "a".repeat(64),
                        Duration.ofHours(2)), Clock.systemUTC());
        var candidate = candidates.register(new ProductSourceCandidates.Declaration(
                "SURECHEMBL_PUBLIC_SEARCH", "capture-contract-1", version,
                "https://surechembl.org/api", "rights review pending", "rights-review", "b".repeat(64),
                Map.of("jurisdiction", List.of("CN")), List.of("TITLE"),
                Map.of("TITLE", List.of("QUERY")), List.of("TITLE"), false, false, true, true, true,
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600)),
                "register-restored-capture", "maintainer#certificate");
        var verification = new ProductSourceVerification(db, tx, candidates,
                new ProductSourceEvidenceFiles(source, 10_000_000), mapper, Clock.systemUTC());
        verification.verify(new ProductSourceVerification.Input(candidate.sourceId(),
                candidate.contractVersion(), candidate.version(), manifestRef,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)),
                "verify-restored-capture"), "maintainer#certificate");
        this.restored = Files.createTempDirectory("task-source-restore-").toAbsolutePath();
        try {
            try (var paths = Files.walk(source.resolve("public-pilot-v1"))) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    Path destination = restored.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination);
                }
            }
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    Path root() { return restored; }

    void removeOneOriginal() throws Exception {
        Files.delete(originalPath());
    }

    void restoreOneOriginal() throws Exception {
        Path original = originalPath();
        Path relative = restored.relativize(original);
        Files.copy(Path.of("data/patents").toAbsolutePath().resolve(relative), original);
    }

    private Path originalPath() throws Exception {
        var manifest = new ObjectMapper().readTree(Files.readAllBytes(
                restored.resolve("public-pilot-v1/manifest.json")));
        return restored.resolve("public-pilot-v1")
                .resolve(manifest.path("rawResponses").get(0).path("file").asText());
    }

    @Override public void close() throws Exception {
        try (var paths = Files.walk(restored)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
