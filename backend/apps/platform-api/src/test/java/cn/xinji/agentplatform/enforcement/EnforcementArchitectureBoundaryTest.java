package cn.xinji.agentplatform.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EnforcementArchitectureBoundaryTest {
    @Test
    void domainAndApplicationDoNotDependOnAdaptersSpringJdbcOrVendorSdks() throws IOException {
        Path root = Path.of("backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement");
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

    @Test
    void localSourceAdaptersCannotWriteOutsideTheirInputCatalogOrCallModels() throws IOException {
        String reader = Files.readString(Path.of(
                "backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/Dm8EnforcementSourceAdapter.java"));
        String importer = Files.readString(Path.of(
                "backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/EnforcementLocalSourceImporter.java"));
        String authorizedImporter = Files.readString(Path.of(
                "backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/AuthorizedEnforcementPackageImporter.java"));
        String publicReader = Files.readString(Path.of(
                "backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/Dm8PublicEnforcementReferenceCatalogAdapter.java"));
        String publicImporter = Files.readString(Path.of(
                "backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/enforcement/adapter/out/dm8/PublicEnforcementReferenceImporter.java"));

        assertFalse(reader.contains("executeUpdate"), "local source reader must remain read-only");
        assertFalse(reader.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*"),
                "local source reader contains a write statement");
        assertFalse(importer.matches("(?s).*\\b(UPDATE|DELETE|MERGE)\\b.*"),
                "local source importer must not mutate existing records");

        var inserts = Pattern.compile("INSERT INTO ([a-z_]+)").matcher(importer);
        while (inserts.find()) {
            assertTrue(inserts.group(1).startsWith("enf_local_"),
                    "source importer writes outside the local input catalog: " + inserts.group(1));
        }
        var authorizedInserts = Pattern.compile("INSERT INTO ([a-z_]+)").matcher(authorizedImporter);
        while (authorizedInserts.find()) {
            assertTrue(authorizedInserts.group(1).startsWith("enf_local_"),
                    "authorized importer writes outside the isolated local input catalog: " + authorizedInserts.group(1));
        }
        var authorizedUpdates = Pattern.compile("UPDATE ([a-z_]+)").matcher(authorizedImporter);
        while (authorizedUpdates.find()) {
            assertTrue(java.util.Set.of("enf_local_clue", "enf_local_evidence", "enf_local_right_snapshot",
                            "enf_local_rule_release").contains(authorizedUpdates.group(1)),
                    "authorized importer may only withdraw its local input records");
        }

        assertFalse(publicReader.contains("executeUpdate"), "public reference reader must remain read-only");
        assertFalse(publicReader.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE)\\b.*"),
                "public reference reader contains a write statement");
        var publicInserts = Pattern.compile("INSERT INTO ([a-z_]+)").matcher(publicImporter);
        while (publicInserts.find()) {
            assertTrue(publicInserts.group(1).startsWith("enf_public_"),
                    "public importer writes outside the public reference catalog: " + publicInserts.group(1));
        }
        var publicUpdates = Pattern.compile("UPDATE ([a-z_]+)").matcher(publicImporter);
        while (publicUpdates.find()) {
            assertTrue(java.util.Set.of("enf_public_reference_snapshot", "enf_public_source_registration")
                            .contains(publicUpdates.group(1)),
                    "public importer may only record withdrawal or source revocation in its isolated catalog");
        }

        for (String source : new String[] {reader, importer, authorizedImporter, publicReader, publicImporter}) {
            assertFalse(source.contains("RestClient"));
            assertFalse(source.contains("WebClient"));
            assertFalse(source.contains("HttpClient"));
            assertFalse(source.contains("ChatClient"));
            assertFalse(source.contains("OpenAI"));
            assertFalse(source.contains("permission_"));
            assertFalse(source.contains("center_"));
            assertFalse(source.contains("enf_review_record"));
            assertFalse(source.contains("enf_work_item"));
            assertFalse(source.contains("official_state"));
        }
        for (String source : new String[] {publicReader, publicImporter}) {
            assertFalse(source.contains("enf_local_clue"));
            assertFalse(source.contains("enf_local_evidence"));
        }
    }
}
