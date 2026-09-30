package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductSourceEvidenceFilesTest {
    @TempDir Path root;

    @Test void onlyExactUnchangedBytesWithinControlledRootCanBeRead() throws Exception {
        byte[] original = "source rights statement, version 1".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("rights"));
        Files.write(root.resolve("rights/source.txt"), original);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        ProductSourceEvidenceFiles store = new ProductSourceEvidenceFiles(root, 1024);

        assertThat(store.verified("rights/source.txt", sha)).isEqualTo(original);
        assertThatThrownBy(() -> store.verified("../other.txt", sha))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.verified("rights/missing.txt", sha))
                .isInstanceOf(IllegalStateException.class);
        Files.writeString(root.resolve("rights/source.txt"), "changed");
        assertThatThrownBy(() -> store.verified("rights/source.txt", sha))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductSourceEvidenceFiles(root, 4)
                .verified("rights/source.txt", sha)).isInstanceOf(IllegalStateException.class);
    }
}
