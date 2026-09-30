package cn.xinji.agentplatform.enforcement.evidence;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class EvidencePartialResultDm8Test {
    @TempDir Path temporary;

    @Test void isolatedDm8AndOriginalFilesPreservePartialFailureBoundary() throws Exception {
        String url = System.getenv("ENF_DEV_DB_URL");
        String password = System.getenv("ENF_DEV_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && password != null, "Dedicated ENF DM8 required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EP" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
        try {
            var source = new DriverManagerDataSource(url, owner, secret);
            try (var connection = source.getConnection()) {
                for (String migration : List.of("V035__enforcement_v2_material.sql",
                        "V050__enforcement_v2_evidence_rules.sql",
                        "V051__enforcement_v2_evidence_publication.sql",
                        "V052__enforcement_v2_evidence_organizations.sql"))
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8", migration)));
            }
            try { EvidencePartialResultProductTest.exercise(source, temporary); }
            finally {
                try (var connection = source.getConnection()) {
                    for (String migration : List.of("U052__enforcement_v2_evidence_organizations.sql",
                            "U051__enforcement_v2_evidence_publication.sql",
                            "U050__enforcement_v2_evidence_rules.sql",
                            "U035__enforcement_v2_material.sql"))
                        ScriptUtils.executeSqlScript(connection,
                                new FileSystemResource(Path.of("db/dm8", migration)));
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP USER " + owner);
            }
        }
    }
}
