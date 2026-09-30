package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductTaskBackupBundleTest {
    @TempDir Path root;

    @Test void realCapturedBytesAndExportBytesShareOneAuthenticatedBatch() throws Exception {
        Path export = root.resolve("primary.dmp");
        Files.copy(Path.of("db/dm8/V053__product_source_use_decision.sql"), export);
        Path evidence = Path.of("data/patents/public-pilot-v1").toAbsolutePath();
        List<String> references = List.of("manifest.json",
                "raw/detail-US-20130157136-A1.json");
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        ProductTaskBackupBundle bundle = new ProductTaskBackupBundle(
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        var snapshot = new ProductTaskBackupBundle.Snapshot("batch-1",
                "https://identity.example.org", "PRODUCT_DB", "current-key-1", 7,
                "a".repeat(64), Map.of("SURECHEMBL_PUBLIC_SEARCH",
                        new ProductTaskBackupBundle.SourceEpoch(1, "READY", "b".repeat(64))),
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(3600),
                "approved-retention", 1);
        Path sealed = root.resolve("batch.enc");
        var manifest = bundle.seal(export, evidence, references, sealed, key, snapshot);
        assertThat(manifest.entries()).hasSize(3);
        assertThat(Files.readAllBytes(sealed))
                .isNotEqualTo(Files.readAllBytes(evidence.resolve("manifest.json")));
        Path isolated = root.resolve("new-instance");
        assertThat(bundle.open(sealed, isolated, key, 10_000_000)).isEqualTo(manifest);
        assertThat(Files.readAllBytes(isolated.resolve("db/primary.dmp")))
                .isEqualTo(Files.readAllBytes(export));
        assertThat(Files.readAllBytes(isolated.resolve("files/" + references.get(1))))
                .isEqualTo(Files.readAllBytes(evidence.resolve(references.get(1))));

        byte[] wrongKey = key.clone();
        wrongKey[0] ^= 1;
        assertThatThrownBy(() -> bundle.open(sealed, root.resolve("wrong-key"), wrongKey,
                10_000_000)).isInstanceOf(Exception.class);
        assertThat(Files.exists(root.resolve("wrong-key"))).isFalse();
        byte[] tampered = Files.readAllBytes(sealed);
        tampered[tampered.length - 1] ^= 1;
        Path damaged = root.resolve("damaged.enc");
        Files.write(damaged, tampered);
        assertThatThrownBy(() -> bundle.open(damaged, root.resolve("tampered"), key,
                10_000_000)).isInstanceOf(Exception.class);
        assertThat(Files.exists(root.resolve("tampered"))).isFalse();
        assertThatThrownBy(() -> bundle.open(sealed, isolated, key, 10_000_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bundle.seal(export, evidence, List.of("../outside"),
                root.resolve("escape.enc"), key, snapshot))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bundle.seal(export, evidence, List.of("missing.json"),
                root.resolve("missing.enc"), key, snapshot))
                .isInstanceOf(Exception.class);
        assertThat(Files.exists(root.resolve("missing.enc"))).isFalse();
    }
}
