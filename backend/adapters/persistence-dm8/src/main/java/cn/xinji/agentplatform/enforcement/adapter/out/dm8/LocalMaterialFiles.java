package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Private volume; only opaque keys are accepted and no static file URL is exposed. */
public final class LocalMaterialFiles implements MaterialFiles {
    private final Path root;

    public LocalMaterialFiles(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
            if (Files.isSymbolicLink(this.root)) throw new IllegalArgumentException("File root must not be a link");
        } catch (IOException failed) { throw new IllegalStateException("Material volume unavailable", failed); }
    }

    @Override public Staged stage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("File bytes required");
        String key = UUID.randomUUID().toString();
        Path temporary = root.resolve(key + ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE);
            return new Staged(key, sha(bytes), bytes.length);
        } catch (IOException failed) { throw new IllegalStateException("Material stage failed", failed); }
    }

    @Override public byte[] read(String key) {
        try {
            Path file = path(key);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Material unavailable");
            return Files.readAllBytes(file);
        } catch (IOException failed) { throw new IllegalStateException("Material unavailable", failed); }
    }

    @Override public List<String> stagedKeys() {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(path -> path.getFileName().toString().matches("[0-9a-f-]{36}\\.blob"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> expired(path))
                    .map(path -> path.getFileName().toString().substring(0, 36)).toList();
        } catch (IOException failed) { throw new IllegalStateException("Material volume unavailable", failed); }
    }

    @Override public void discard(String key) {
        try {
            Path file = path(key);
            if (Files.isSymbolicLink(file)) throw new IllegalStateException("File link refused");
            Files.deleteIfExists(file);
        } catch (IOException failed) { throw new IllegalStateException("Orphan cleanup failed", failed); }
    }

    private Path path(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid file key");
        return root.resolve(key + ".blob");
    }

    private static boolean expired(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                .isBefore(Instant.now().minus(Duration.ofMinutes(10))); }
        catch (IOException failed) { throw new IllegalStateException("Material age unavailable", failed); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
