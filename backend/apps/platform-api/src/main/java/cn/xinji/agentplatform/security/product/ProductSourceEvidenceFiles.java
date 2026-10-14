package cn.xinji.agentplatform.security.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reads deployment-controlled evidence bytes; declarations cannot supply their own authority. */
public final class ProductSourceEvidenceFiles {
    private static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}");
    private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}");
    @FunctionalInterface
    interface StreamReader { void read(java.io.InputStream stream) throws IOException; }
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
        if (reference == null || !REFERENCE.matcher(reference).matches()
                || reference.contains("..") || expectedSha256 == null
                || !SHA.matcher(expectedSha256).matches())
            throw new IllegalArgumentException("Invalid source evidence reference");
        try {
            Path actual = root.resolve(reference).toRealPath();
            if (!actual.startsWith(root))
                throw new IllegalStateException("Source evidence is outside the controlled store");
            var attributes = Files.readAttributes(actual, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile())
                throw new IllegalStateException("Source evidence is not a regular controlled file");
            if (attributes.size() > maxBytes)
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

    /** Whole-archive proof for maintenance/recovery, under the same root and configured byte limit. */
    void verifiedStream(String reference, String expectedSha256, long expectedBytes, StreamReader reader) {
        Path actual = boundedPath(reference, expectedSha256, expectedBytes);
        try {
            var before = Files.readAttributes(actual, java.nio.file.attribute.BasicFileAttributes.class);
            var hash = MessageDigest.getInstance("SHA-256");
            try (var stream = new LimitedStream(Files.newInputStream(actual), expectedBytes);
                 var digest = new java.security.DigestInputStream(stream, hash)) {
                reader.read(digest);
                byte[] buffer = new byte[65536];
                while (digest.read(buffer) != -1) { /* Include unread ZIP directory/trailing bytes. */ }
                if (stream.count != expectedBytes) throw new IOException("Archive length changed");
            }
            var after = Files.readAttributes(actual, java.nio.file.attribute.BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                    || !HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(expectedSha256))
                throw new IllegalStateException("Archive identity or digest changed");
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Source archive unavailable", failure);
        }
    }

    /** Existence/length only: current selected bytes have their own full digest checks. */
    void archivePresent(String reference, String expectedSha256, long expectedBytes) {
        boundedPath(reference, expectedSha256, expectedBytes);
    }

    private Path boundedPath(String reference, String sha, long size) {
        if (reference == null || !REFERENCE.matcher(reference).matches()
                || reference.contains("..") || sha == null || !SHA.matcher(sha).matches()
                || size <= 0 || size > maxBytes)
            throw new IllegalArgumentException("Archive reference or configured budget invalid");
        try {
            Path actual = root.resolve(reference).toRealPath();
            var attributes = Files.readAttributes(actual, java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!actual.startsWith(root) || !attributes.isRegularFile() || attributes.size() != size)
                throw new IllegalStateException("Archive outside controlled store or length differs");
            return actual;
        } catch (IOException failure) { throw new IllegalStateException("Archive missing", failure); }
    }

    private static final class LimitedStream extends java.io.FilterInputStream {
        private final long limit;
        private long count;
        LimitedStream(java.io.InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) account(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int value = in.read(bytes, offset, length);
            if (value > 0) account(value);
            return value;
        }
        private void account(int amount) throws IOException {
            count += amount;
            if (count > limit) throw new IOException("Archive grew beyond declared budget");
        }
    }
}
