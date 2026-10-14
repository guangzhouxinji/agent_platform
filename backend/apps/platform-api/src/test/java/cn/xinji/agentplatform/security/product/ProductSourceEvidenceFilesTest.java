package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled integrity bytes only, never product source admission or patent data. */
class ProductSourceEvidenceFilesTest {
    @TempDir Path root;
    @Test void exactBytesStillRequireCorrectHashRegularFileAndSize() throws Exception {
        byte[] bytes="完整性𠮷".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Files.write(root.resolve("original.bin"),bytes);
        var files=new ProductSourceEvidenceFiles(root,bytes.length);
        assertThat(files.verified("original.bin",sha.toUpperCase())).containsExactly(bytes);
        assertThatThrownBy(() -> files.verified("original.bin","0".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductSourceEvidenceFiles(root,bytes.length-1)
                .verified("original.bin",sha)).isInstanceOf(IllegalStateException.class);
        Files.writeString(root.resolve("original.bin"),"changed");
        assertThatThrownBy(() -> files.verified("original.bin",sha))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void directoriesMissingFilesAndTraversalRemainRejected() throws Exception {
        Files.createDirectory(root.resolve("directory"));
        var files=new ProductSourceEvidenceFiles(root,1024);
        for(String path:new String[]{"directory","missing.bin"})
            assertThatThrownBy(() -> files.verified(path,"a".repeat(64)))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> files.verified("../outside.bin","a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
