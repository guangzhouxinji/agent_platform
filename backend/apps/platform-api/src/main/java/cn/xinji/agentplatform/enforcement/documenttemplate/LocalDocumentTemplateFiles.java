package cn.xinji.agentplatform.enforcement.documenttemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

final class LocalDocumentTemplateFiles implements DocumentTemplateFiles {
    private final Path root;
    LocalDocumentTemplateFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public String put(String category, String sha256, byte[] content) {
        try {
            Path directory = root.resolve(category).normalize();
            if (!directory.startsWith(root)) throw new SecurityException("Invalid file category");
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + "-" + sha256 + ".docx";
            Path target = directory.resolve(name); Path temporary = directory.resolve(name + ".tmp");
            Files.write(temporary, content);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (Exception e) { throw new IllegalStateException("Document template file write failed", e); }
    }
    @Override public byte[] read(String key) {
        try { return Files.readAllBytes(resolve(key)); }
        catch (Exception e) { throw new IllegalStateException("Document template file unavailable", e); }
    }
    @Override public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (Exception e) { throw new IllegalStateException("Document template orphan cleanup failed", e); }
    }
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new SecurityException("Invalid document template file key"); return path;
    }
}
