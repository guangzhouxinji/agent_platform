package cn.xinji.agentplatform.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceTestPaths {
    private WorkspaceTestPaths() { }

    public static Path root() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("backend")) && Files.isDirectory(current.resolve("db"))) {
            return current;
        }
        if ("backend".equals(current.getFileName().toString())
                && Files.isDirectory(current.resolve("..").resolve("db"))) {
            return current.getParent();
        }
        throw new IllegalStateException("Cannot resolve project workspace for integration tests");
    }
}
