package cn.xinji.agentplatform.enforcement.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Pauses only the dedicated DM8 test after its real Evidence transaction has committed. */
final class EvidenceOrganizationRecoveryCapture {
    private EvidenceOrganizationRecoveryCapture() { }

    static void pause(DriverManagerDataSource source, JdbcTemplate db, Path root,
                      List<EvidenceTrialSource.Ref> refs) throws Exception {
        String gate = System.getenv("ENF_EVIDENCE_RECOVERY_GATE");
        if (gate == null || gate.isBlank()) return;
        if (!source.getUrl().startsWith("jdbc:dm:"))
            throw new IllegalStateException("Evidence physical capture requires dedicated DM8");
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        if (!Files.isDirectory(marker.getParent()) || Files.exists(marker))
            throw new IllegalStateException("Recovery marker must be unused in an existing directory");
        Properties values = new Properties();
        values.setProperty("businessUser", source.getUsername());
        values.setProperty("businessPassword", source.getPassword());
        values.setProperty("fileRoot", root.resolve("files").toAbsolutePath().toString());
        values.setProperty("caseFile", root.resolve("case-snapshot.json").toAbsolutePath().toString());
        values.setProperty("organizationId", "ORG-CASE-001");
        values.setProperty("organizationRevision", "4");
        values.setProperty("receiptCount", "5");
        for (int index = 0; index < refs.size(); index++) {
            var ref = refs.get(index);
            values.setProperty("evidence." + index + ".id", ref.id());
            values.setProperty("evidence." + index + ".version", ref.version());
            values.setProperty("evidence." + index + ".sha256", ref.sha256());
            values.setProperty("evidence." + index + ".fileKey", db.queryForObject(
                    "SELECT file_key FROM enf3_material WHERE material_id=?", String.class, ref.id()));
        }
        values.setProperty("evidenceCount", Integer.toString(refs.size()));
        try (var output = Files.newOutputStream(marker)) {
            values.store(output, "Evidence V2 physical recovery checkpoint; local sensitive test credentials");
        }
        Path resume = marker.resolveSibling(marker.getFileName() + ".resume");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        while (!Files.exists(resume) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(resume)) throw new IllegalStateException("Evidence recovery capture timed out");
    }
}
