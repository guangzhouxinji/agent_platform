package cn.xinji.agentplatform.enforcement.evidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;

final class EvidenceIndependentRecoveryCapture {
    private EvidenceIndependentRecoveryCapture() { }

    static void beforeWithdrawal(Path files, JdbcTemplate db, String owner, String secret,
                                 String token, String body) throws Exception {
        String gate = System.getenv("ENF_EVIDENCE_HTTP_RECOVERY_GATE");
        if (gate == null) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        if (!Files.isDirectory(marker.getParent()) || Files.exists(marker))
            throw new IllegalStateException("Unused Evidence recovery marker required");
        var values = new Properties();
        values.setProperty("owner", owner);
        values.setProperty("secret", secret);
        values.setProperty("token", token);
        values.setProperty("body", body);
        values.setProperty("files", files.resolve("files").toAbsolutePath().toString());
        values.setProperty("caseFile", files.resolve("case-snapshot.json").toAbsolutePath().toString());
        values.setProperty("receipts", Integer.toString(db.queryForObject(
                "SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)));
        try (var output = Files.newOutputStream(marker)) { values.store(output, "Local sensitive Evidence test checkpoint"); }
        await(marker.resolveSibling(marker.getFileName() + ".resume"));
    }

    static void afterWithdrawal() throws Exception {
        String gate = System.getenv("ENF_EVIDENCE_HTTP_RECOVERY_GATE");
        if (gate == null) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        Files.writeString(marker.resolveSibling(marker.getFileName() + ".withdrawn"), "withdrawn");
        await(marker.resolveSibling(marker.getFileName() + ".verified"));
    }

    private static void await(Path marker) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        while (!Files.exists(marker) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(marker)) throw new IllegalStateException("Evidence recovery gate timed out");
    }
}
