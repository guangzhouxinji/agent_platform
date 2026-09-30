package cn.xinji.agentplatform.enforcement.material;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

/** Quiescent product transaction checkpoint for an external cold DB and file-volume copy. */
final class MaterialProductRecoveryCapture {
    private MaterialProductRecoveryCapture() { }

    static void pause(DriverManagerDataSource business, JdbcTemplate db, Path root,
                      MaterialWork.Receipt material, MaterialWork.Receipt clue, Jwt jwt,
                      Instant fileExpiry) throws Exception {
        String gate = System.getenv("ENF_PRODUCT_RECOVERY_GATE");
        if (gate == null || gate.isBlank()) return;
        Path marker = Path.of(gate).toAbsolutePath().normalize();
        if (!Files.isDirectory(marker.getParent()) || Files.exists(marker))
            throw new IllegalStateException("Recovery gate must be an unused file in an existing directory");
        var row = db.queryForMap("SELECT file_key,sha256,collector,collected_at,rights_ref,allowed_uses,state,revision "
                + "FROM enf3_material WHERE material_id=?", material.resultId());
        var verifier = db.queryForMap("SELECT sid,jti FROM id2_session WHERE subject_id='named-enf-v2-verifier'");
        Properties values = new Properties();
        values.setProperty("businessUser", business.getUsername());
        values.setProperty("businessPassword", business.getPassword());
        values.setProperty("fileRoot", root.toAbsolutePath().toString());
        values.setProperty("fileKey", row.get("FILE_KEY").toString());
        values.setProperty("sha256", row.get("SHA256").toString());
        values.setProperty("collector", row.get("COLLECTOR").toString());
        values.setProperty("collectedAt", row.get("COLLECTED_AT").toString());
        values.setProperty("rightsRef", row.get("RIGHTS_REF").toString());
        values.setProperty("uses", row.get("ALLOWED_USES").toString());
        values.setProperty("state", row.get("STATE").toString());
        values.setProperty("version", row.get("REVISION").toString());
        values.setProperty("materialId", material.resultId());
        values.setProperty("clueId", clue.resultId());
        values.setProperty("receiptId", material.requestId());
        values.setProperty("actor", jwt.getSubject());
        values.setProperty("issuer", jwt.getIssuer().toString());
        values.setProperty("sid", jwt.getClaimAsString("sid"));
        values.setProperty("jti", jwt.getId());
        values.setProperty("verifierSid", verifier.get("SID").toString());
        values.setProperty("verifierJti", verifier.get("JTI").toString());
        values.setProperty("expiresAt", fileExpiry.toString());
        values.setProperty("sourceId", "PROJECT-DEV-OBSERVATION");
        try (var output = Files.newOutputStream(marker)) { values.store(output, "ENF V2 product recovery checkpoint"); }
        Path resume = marker.resolveSibling(marker.getFileName() + ".resume");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(20));
        while (!Files.exists(resume) && Instant.now().isBefore(deadline)) Thread.sleep(250);
        if (!Files.exists(resume)) throw new IllegalStateException("Recovery checkpoint timed out");
    }
}
