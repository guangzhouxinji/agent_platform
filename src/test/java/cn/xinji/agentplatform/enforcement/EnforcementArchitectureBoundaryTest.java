package cn.xinji.agentplatform.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnforcementArchitectureBoundaryTest {
    @Test
    void domainAndApplicationDoNotDependOnAdaptersSpringJdbcOrVendorSdks() throws IOException {
        Path root = Path.of("src/main/java/cn/xinji/agentplatform/enforcement");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (!normalized.contains("/domain/") && !normalized.contains("/application/")) continue;
                String source = Files.readString(file);
                assertFalse(source.contains("org.springframework"), file + " imports Spring");
                assertFalse(source.contains("java.sql"), file + " imports JDBC");
                assertFalse(source.contains("adapter.out"), file + " depends on an adapter");
                assertFalse(source.contains("dm.jdbc"), file + " imports the DM8 driver");
            }
        }
    }
}
