package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

final class MaterialCollectionDm8Scenarios {
    private MaterialCollectionDm8Scenarios() { }

    static void run(JdbcTemplate db, JdbcTemplate authority, ProductGrantWatermark watermark,
                    ProductGrants grants, MaterialRepository repository, Path root, Jwt jwt,
                    String actor, String issuer, String org, String kid, Instant now, String materialId) throws Exception {
        String verifier = "named-enf-v2-verifier";
        String verifierSid = UUID.randomUUID().toString();
        String verifierJti = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                issuer, verifier, verifier, "encoded", "ACTIVE", 1);
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", verifierSid, verifierJti, issuer,
                verifier, "enf-client", kid, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)), "ACTIVE", null);
        Jwt verifierJwt = Jwt.withTokenValue("verified-verifier").header("alg", "RS256").issuer(issuer)
                .subject(verifier).claim("sid", verifierSid).claim("jti", verifierJti)
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        var collector = new MaterialCollectionService(repository, new LocalMaterialFiles(root),
                new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());
        var verification = new MaterialCollectionService(repository, new LocalMaterialFiles(root),
                new ProductEnforcementAccess(db, authority, watermark, verifierJwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());
        grant(grants, watermark, issuer, actor, org, materialId, 1, "COLLECT_ATTEST", "SAVE");
        grant(grants, watermark, issuer, verifier, org, materialId, 2, "VERIFY", "SAVE");
        grant(grants, watermark, issuer, verifier, org, materialId, 3, "READ", "DISPLAY");
        Path originalPath = root.resolve(repository.material(materialId).orElseThrow().fileKey() + ".blob");
        byte[] original = Files.readAllBytes(originalPath);
        byte[] changed = original.clone();
        changed[0] ^= 1;
        try {
            Files.write(originalPath, changed);
            assertThatThrownBy(() -> collector.attest(actor, "tampered-collect-dm8", materialId, 1,
                    "OBS-001/original-M-01", "received original file"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material_action WHERE material_id=?",
                    Integer.class, materialId)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='tampered-collect-dm8'",
                    Integer.class)).isZero();
        } finally { Files.write(originalPath, original); }
        var collected = collector.attest(actor, "collection-1", materialId, 1,
                "OBS-001/original-M-01", "received original file");
        assertThat(collector.attest(actor, "collection-1", materialId, 1,
                "OBS-001/original-M-01", "received original file")).isEqualTo(collected);
        assertThatThrownBy(() -> collector.verify(actor, "self-verification", materialId, 2,
                "file digest checked")).isInstanceOf(SecurityException.class);
        assertThat(verification.verify(verifier, "verification-1", materialId, 2,
                "file digest checked").resultVersion()).isEqualTo(3);
        assertThat(verification.actions(verifier, materialId)).extracting(MaterialWork.MaterialAction::action)
                .containsExactly("UPLOAD", "COLLECT_ATTEST", "VERIFY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='self-verification'",
                Integer.class)).isZero();
        assertThat(repository.material(materialId).orElseThrow().state()).isEqualTo("VERIFIED");
        System.out.println("ENF_V2_M03_DM8 upload=UNVERIFIED tampered=DENIED collection=ATTESTED "
                + "verifier=INDEPENDENT actions=3 forbiddenReceipt=0");
    }

    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String issuer,
                              String actor, String org, String id, long version, String action, String purpose) {
        var scope = new ProductGrants.Scope(issuer, actor, org, "ENFORCEMENT", "ENF-V2-MATERIAL",
                "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1", "MATERIAL", id, version, action, purpose);
        watermark.change(() -> grants.issue(scope, Instant.now().plusSeconds(3600), 0,
                "enf-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }
}
