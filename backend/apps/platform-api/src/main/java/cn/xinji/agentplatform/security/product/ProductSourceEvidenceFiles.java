package cn.xinji.agentplatform.security.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reads deployment-controlled evidence bytes; declarations cannot supply their own authority. */
public final class ProductSourceEvidenceFiles {
    private final Path root;
    private final long maxBytes;

    public ProductSourceEvidenceFiles(Path root, long maxBytes) {
        if (root == null || !root.isAbsolute() || maxBytes <= 0)
            throw new IllegalArgumentException("Absolute evidence root and size limit are required");
        this.maxBytes = maxBytes;
        try {
            this.root = root.toRealPath();
            if (!Files.isDirectory(this.root)) throw new IllegalArgumentException("Evidence root is not a directory");
        } catch (IOException failure) {
            throw new IllegalStateException("Source evidence store is unavailable", failure);
        }
    }

    public byte[] verified(String reference, String expectedSha256) {
        if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")
                || reference.contains("..") || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Invalid source evidence reference");
        try {
            Path actual = root.resolve(reference).toRealPath();
            if (!actual.startsWith(root) || !Files.isRegularFile(actual))
                throw new IllegalStateException("Source evidence is outside the controlled store");
            if (Files.size(actual) > maxBytes)
                throw new IllegalStateException("Source evidence exceeds the approved size limit");
            byte[] bytes = Files.readAllBytes(actual);
            if (bytes.length > maxBytes)
                throw new IllegalStateException("Source evidence changed during reading");
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .equalsIgnoreCase(expectedSha256))
                throw new IllegalStateException("Source evidence digest changed");
            return bytes;
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Source evidence is unavailable", failure);
        }
    }
}
