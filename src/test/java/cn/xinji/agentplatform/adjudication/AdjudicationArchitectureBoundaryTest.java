package cn.xinji.agentplatform.adjudication;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import org.junit.jupiter.api.Test;

class AdjudicationArchitectureBoundaryTest {
    @Test void applicationDoesNotExposeOfficialDecisionSigningOrClosureOperations() {
        assertFalse(Arrays.stream(AdjudicationApplicationService.class.getMethods())
                .map(method -> method.getName().toLowerCase())
                .anyMatch(name -> name.contains("official") || name.contains("sign") || name.contains("close") || name.contains("archive")));
    }
    @Test void domainAndApplicationDoNotDependOnAdaptersSpringJdbcOrOtherBusinessDomains() throws IOException {
        Path root = Path.of("src/main/java/cn/xinji/agentplatform/adjudication");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String normalized = file.toString().replace('\\', '/');
                if (!normalized.contains("/domain/") && !normalized.contains("/application/")) continue;
                String source = Files.readString(file);
                assertFalse(source.contains("org.springframework"), file + " imports Spring");
                assertFalse(source.contains("java.sql"), file + " imports JDBC");
                assertFalse(source.contains("adapter.out"), file + " depends on an adapter");
                assertFalse(source.contains("enforcement") || source.contains("consultation"), file + " depends on another business domain");
            }
        }
    }
}
