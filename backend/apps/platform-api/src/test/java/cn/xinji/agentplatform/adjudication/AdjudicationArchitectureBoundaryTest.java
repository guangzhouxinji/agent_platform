package cn.xinji.agentplatform.adjudication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Path root = Path.of("backend/adjudication/src/main/java/cn/xinji/agentplatform/adjudication");
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
    @Test void localSourceReaderCannotWriteWorkResultsOrCallModels() throws IOException {
        String reader = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/adjudication/adapter/out/dm8/Dm8AdjudicationSourceAdapter.java"));
        String importer = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/adjudication/adapter/out/dm8/AdjudicationLocalSourceImporter.java"));
        assertFalse(reader.contains("executeUpdate"), "local source reader must remain read-only");
        assertFalse(reader.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*"), "local source reader contains a write statement");
        assertFalse(importer.matches("(?s).*adj_(work|test_review|source_locator|draft|citation)\\b.*"), "local importer writes work results");
        assertFalse(reader.contains("model") || importer.contains("model"), "local source must not call a model");
        assertFalse(reader.contains("center") || importer.contains("center"), "local source must not call a center client");
    }

    @Test void publicReferenceCatalogCannotWriteOperationalInputsOrCallModelsOrCenter() throws IOException {
        String reader = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/adjudication/adapter/out/dm8/Dm8PublicAdjudicationReferenceCatalogAdapter.java"));
        String importer = Files.readString(Path.of("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/adjudication/adapter/out/dm8/PublicAdjudicationReferenceImporter.java"));
        assertFalse(reader.contains("executeUpdate"), "public reference reader must remain read-only");
        assertFalse(reader.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*"), "public reference reader contains a write statement");
        var writes = java.util.regex.Pattern.compile("(?:INSERT INTO|UPDATE|DELETE FROM|MERGE INTO)\\s+([a-z_]+)").matcher(importer);
        while (writes.find()) {
            assertTrue(java.util.Set.of("adj_public_source_registration", "adj_public_reference_batch",
                    "adj_public_reference_snapshot").contains(writes.group(1)),
                    "public importer writes outside its isolated catalog: " + writes.group(1));
        }
        assertFalse(reader.contains("AdjudicationApplicationService") || importer.contains("AdjudicationApplicationService"),
                "public reference catalog must not call the adjudication workflow");
        assertFalse(reader.matches("(?s).*(ModelClient|ModelPort|adapter\\.out\\.model).*"),
                "public reference reader must not call a model");
        assertFalse(importer.matches("(?s).*(ModelClient|ModelPort|adapter\\.out\\.model).*"),
                "public reference importer must not call a model");
        assertFalse(reader.matches("(?s).*(CenterClient|center\\.client|adapter\\.out\\.center).*"),
                "public reference reader must not call a center client");
        assertFalse(importer.matches("(?s).*(CenterClient|center\\.client|adapter\\.out\\.center).*"),
                "public reference importer must not call a center client");
    }
}
