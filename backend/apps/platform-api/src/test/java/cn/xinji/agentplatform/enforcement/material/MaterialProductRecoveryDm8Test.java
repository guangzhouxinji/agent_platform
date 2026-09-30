package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

class MaterialProductRecoveryDm8Test {
    @Test void restoredProductRejectsUnavailableIndependentAuthorityWithoutEffects() throws Exception {
        String gate = System.getenv("ENF_PRODUCT_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_PRODUCT_RESTORED_DB_URL");
        String restoredFiles = System.getenv("ENF_PRODUCT_RESTORED_FILES");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && restoredFiles != null,
                "Isolated product physical restore required");
        Properties checkpoint = new Properties();
        try (var input = Files.newInputStream(Path.of(gate))) { checkpoint.load(input); }
        var businessSource = new DriverManagerDataSource(restoredUrl,
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var missingAuthority = new DriverManagerDataSource("jdbc:dm://127.0.0.1:5299",
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(missingAuthority);
        String key = checkpoint.getProperty("receiptId");
        String materialId = checkpoint.getProperty("materialId");
        assertThat(db.queryForObject("SELECT state FROM enf3_material WHERE material_id=?", String.class,
                materialId)).isEqualTo("VERIFIED");
        assertThat(db.queryForObject("SELECT revision FROM enf3_material WHERE material_id=?", Long.class,
                materialId)).isEqualTo(3L);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material_action WHERE material_id=?",
                Integer.class, materialId)).isEqualTo(3);
        assertThat(MaterialDigests.hash(Files.readAllBytes(Path.of(restoredFiles,
                checkpoint.getProperty("fileKey") + ".blob")))).isEqualTo(checkpoint.getProperty("sha256"));
        int receipts = count(db, "enf3_receipt", key);
        int events = count(db, "enf3_event", key);
        int fileCount;
        try (var paths = Files.list(Path.of(restoredFiles))) { fileCount = (int) paths.count(); }
        assertThatThrownBy(() -> new ProductGrantWatermark(db, authority, checkpoint.getProperty("issuer")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count(db, "enf3_receipt", key)).isEqualTo(receipts);
        assertThat(count(db, "enf3_event", key)).isEqualTo(events);
        try (var paths = Files.list(Path.of(restoredFiles))) { assertThat(paths.count()).isEqualTo(fileCount); }
        System.out.printf("ENF_V2_PHYSICAL_RESTORE authority=UNAVAILABLE replay=DENIED "
                + "receipts=%d events=%d fileCount=%d%n", receipts, events, fileCount);
    }

    @Test void restoredProductReceiptFileAndIndependentSourceReconcile() throws Exception {
        String gate = System.getenv("ENF_PRODUCT_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_PRODUCT_RESTORED_DB_URL");
        String restoredFiles = System.getenv("ENF_PRODUCT_RESTORED_FILES");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && restoredFiles != null
                && authorityUrl != null, "Isolated product physical restore required");
        Properties checkpoint = new Properties();
        try (var input = Files.newInputStream(Path.of(gate))) { checkpoint.load(input); }
        var businessSource = new DriverManagerDataSource(restoredUrl,
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var authoritySource = new DriverManagerDataSource(authorityUrl,
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        String issuer = checkpoint.getProperty("issuer");
        String actor = checkpoint.getProperty("actor");
        String materialId = checkpoint.getProperty("materialId");
        String fileKey = checkpoint.getProperty("fileKey");
        String sourceId = checkpoint.getProperty("sourceId");
        Path root = Path.of(restoredFiles);
        byte[] original = Files.readAllBytes(root.resolve(fileKey + ".blob"));
        assertThat(MaterialDigests.hash(original)).isEqualTo(checkpoint.getProperty("sha256"));
        assertThat(original.length).isEqualTo(317);
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var repository = new Dm8MaterialRepository(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        var files = new LocalMaterialFiles(root);
        Instant now = Instant.now();
        Jwt operatorJwt = jwt(issuer, actor, checkpoint.getProperty("sid"), checkpoint.getProperty("jti"), now);
        var service = new MaterialProductService(repository, files,
                new ProductEnforcementAccess(db, authority, watermark, operatorJwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());
        Jwt verifierJwt = jwt(issuer, "named-enf-v2-verifier", checkpoint.getProperty("verifierSid"),
                checkpoint.getProperty("verifierJti"), now);
        var collection = new MaterialCollectionService(repository, files,
                new ProductEnforcementAccess(db, authority, watermark, verifierJwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());
        var material = repository.material(materialId).orElseThrow();
        assertThat(material.state()).isEqualTo("VERIFIED");
        assertThat(material.version()).isEqualTo(3);
        assertThat(material.sha256()).isEqualTo(checkpoint.getProperty("sha256"));
        assertThat(collection.actions(verifierJwt.getSubject(), materialId))
                .extracting(MaterialWork.MaterialAction::action)
                .containsExactly("UPLOAD", "COLLECT_ATTEST", "VERIFY");
        String receiptId = checkpoint.getProperty("receiptId");
        int beforeReceipt = count(db, "enf3_receipt", receiptId);
        int beforeEvent = count(db, "enf3_event", receiptId);
        assertThat(beforeReceipt).isEqualTo(1);
        assertThat(beforeEvent).isEqualTo(1);
        var input = new MaterialProductService.MaterialInput(checkpoint.getProperty("clueId"), original,
                checkpoint.getProperty("rightsRef"), Instant.parse(checkpoint.getProperty("expiresAt")),
                Arrays.asList(checkpoint.getProperty("uses").split(",")), checkpoint.getProperty("collector"),
                checkpoint.getProperty("collectedAt"));
        assertThat(service.registerMaterial(actor, receiptId, input).resultId()).isEqualTo(materialId);
        assertThat(count(db, "enf3_receipt", receiptId)).isEqualTo(beforeReceipt);
        assertThat(count(db, "enf3_event", receiptId)).isEqualTo(beforeEvent);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE material_id=?",
                Integer.class, materialId)).isEqualTo(1);

        var staged = files.stage(original);
        Files.setLastModifiedTime(root.resolve(staged.key() + ".blob"),
                FileTime.from(now.minus(11, ChronoUnit.MINUTES)));
        assertThat(service.cleanOrphans()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(root.resolve(staged.key() + ".blob"))).isFalse();
        assertThat(Files.readAllBytes(root.resolve(fileKey + ".blob"))).isEqualTo(original);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_cleanup_event WHERE file_key=? "
                + "AND state='SUCCEEDED'", Integer.class, staged.key())).isEqualTo(1);

        authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?", sourceId);
        int fileCount;
        try (var paths = Files.list(root)) { fileCount = (int) paths.count(); }
        assertThatThrownBy(() -> service.registerMaterial(actor, receiptId, input))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> collection.actions(verifierJwt.getSubject(), materialId))
                .isInstanceOf(SecurityException.class);
        assertThat(count(db, "enf3_receipt", receiptId)).isEqualTo(beforeReceipt);
        assertThat(count(db, "enf3_event", receiptId)).isEqualTo(beforeEvent);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(fileCount); }
        System.out.printf("ENF_V2_PHYSICAL_RESTORE receipt=%s state=VERIFIED actions=3 fileSha=%s "
                + "orphan=REMOVED referenced=RETAINED withdrawnReplay=DENIED receipts=%d events=%d%n",
                receiptId, material.sha256(), beforeReceipt, beforeEvent);
    }

    @Test void oldProductBackupCannotRestoreRightsAfterIndependentDeletionEpoch() throws Exception {
        String gate = System.getenv("ENF_PRODUCT_RECOVERY_GATE");
        String restoredUrl = System.getenv("ENF_PRODUCT_RESTORED_DB_URL");
        String restoredFiles = System.getenv("ENF_PRODUCT_RESTORED_FILES");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        Assumptions.assumeTrue(gate != null && restoredUrl != null && restoredFiles != null
                && authorityUrl != null, "Isolated old product backup and current authority required");
        Properties checkpoint = new Properties();
        try (var input = Files.newInputStream(Path.of(gate))) { checkpoint.load(input); }
        var businessSource = new DriverManagerDataSource(restoredUrl,
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var authoritySource = new DriverManagerDataSource(authorityUrl,
                checkpoint.getProperty("businessUser"), checkpoint.getProperty("businessPassword"));
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        String issuer = checkpoint.getProperty("issuer");
        String actor = checkpoint.getProperty("actor");
        String sourceId = checkpoint.getProperty("sourceId");
        String materialId = checkpoint.getProperty("materialId");
        String receiptId = checkpoint.getProperty("receiptId");
        Path root = Path.of(restoredFiles);
        byte[] original = Files.readAllBytes(root.resolve(checkpoint.getProperty("fileKey") + ".blob"));
        assertThat(MaterialDigests.hash(original)).isEqualTo(checkpoint.getProperty("sha256"));
        assertThat(authority.queryForObject("SELECT state FROM enf3_source_watermark WHERE source_id=?",
                String.class, sourceId)).isEqualTo("READY");
        assertThat(authority.queryForObject("SELECT state FROM enf3_delete_watermark WHERE source_id=?",
                String.class, sourceId)).isEqualTo("READY");
        long originalDeleteEpoch = authority.queryForObject(
                "SELECT delete_epoch FROM enf3_delete_watermark WHERE source_id=?", Long.class, sourceId);
        var input = new MaterialProductService.MaterialInput(checkpoint.getProperty("clueId"), original,
                checkpoint.getProperty("rightsRef"), Instant.parse(checkpoint.getProperty("expiresAt")),
                Arrays.asList(checkpoint.getProperty("uses").split(",")), checkpoint.getProperty("collector"),
                checkpoint.getProperty("collectedAt"));
        int receipts = count(db, "enf3_receipt", receiptId);
        int events = count(db, "enf3_event", receiptId);
        int materials = db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE material_id=?",
                Integer.class, materialId);
        Path checkpointRoot = Path.of(checkpoint.getProperty("fileRoot"));
        try (var paths = Files.list(checkpointRoot)) {
            for (Path originalFile : paths.toList()) {
                assertThat(Files.readAllBytes(root.resolve(originalFile.getFileName())))
                        .isEqualTo(Files.readAllBytes(originalFile));
            }
        }
        assertThat(receipts).isEqualTo(1);
        assertThat(events).isEqualTo(1);
        var first = restoredService(businessSource, authoritySource, checkpoint);
        var collection = restoredCollection(businessSource, authoritySource, checkpoint);
        assertThat(first.registerMaterial(actor, receiptId, input).resultId()).isEqualTo(materialId);
        assertThat(collection.actions("named-enf-v2-verifier", materialId))
                .extracting(MaterialWork.MaterialAction::action)
                .containsExactly("UPLOAD", "COLLECT_ATTEST", "VERIFY");
        long files;
        try (var paths = Files.list(root)) { files = paths.count(); }
        assertThat(authority.update("UPDATE enf3_delete_watermark SET state='DELETED', "
                + "delete_epoch=delete_epoch+1 WHERE source_id=? AND state='READY'", sourceId)).isEqualTo(1);
        assertThat(authority.queryForObject("SELECT state FROM enf3_source_watermark WHERE source_id=?",
                String.class, sourceId)).isEqualTo("READY");
        assertThat(authority.queryForObject("SELECT delete_epoch FROM enf3_delete_watermark WHERE source_id=?",
                Long.class, sourceId)).isEqualTo(originalDeleteEpoch + 1);
        var second = restoredService(businessSource, authoritySource, checkpoint);
        assertThatThrownBy(() -> first.registerMaterial(actor, receiptId, input))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> second.registerMaterial(actor, receiptId, input))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> collection.actions("named-enf-v2-verifier", materialId))
                .isInstanceOf(SecurityException.class);
        assertThat(count(db, "enf3_receipt", receiptId)).isEqualTo(receipts);
        assertThat(count(db, "enf3_event", receiptId)).isEqualTo(events);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE material_id=?",
                Integer.class, materialId)).isEqualTo(materials);
        try (var paths = Files.list(root)) { assertThat(paths.count()).isEqualTo(files); }
        assertThat(Files.readAllBytes(root.resolve(checkpoint.getProperty("fileKey") + ".blob")))
                .isEqualTo(original);
        System.out.printf("ENF_V2_OLD_BACKUP_DM8 source=READY deletion=DELETED oldReceipt=DENIED "
                + "instances=2 receipts=%d events=%d materials=%d files=%d%n",
                receipts, events, materials, files);
    }

    private static MaterialProductService restoredService(DriverManagerDataSource businessSource,
                                                          DriverManagerDataSource authoritySource,
                                                          Properties checkpoint) {
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        String issuer = checkpoint.getProperty("issuer");
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var repository = new Dm8MaterialRepository(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        Jwt jwt = jwt(issuer, checkpoint.getProperty("actor"), checkpoint.getProperty("sid"),
                checkpoint.getProperty("jti"), Instant.now());
        return new MaterialProductService(repository, new LocalMaterialFiles(Path.of(
                System.getenv("ENF_PRODUCT_RESTORED_FILES"))),
                new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());
    }

    private static MaterialCollectionService restoredCollection(DriverManagerDataSource businessSource,
                                                                DriverManagerDataSource authoritySource,
                                                                Properties checkpoint) {
        var db = new JdbcTemplate(businessSource);
        String issuer = checkpoint.getProperty("issuer");
        var authority = new JdbcTemplate(authoritySource);
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var repository = new Dm8MaterialRepository(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        Jwt verifier = jwt(issuer, "named-enf-v2-verifier", checkpoint.getProperty("verifierSid"),
                checkpoint.getProperty("verifierJti"), Instant.now());
        return new MaterialCollectionService(repository, new LocalMaterialFiles(Path.of(
                System.getenv("ENF_PRODUCT_RESTORED_FILES"))),
                new ProductEnforcementAccess(db, authority, watermark, verifier, issuer, Clock.systemUTC()),
                Clock.systemUTC());
    }

    private static int count(JdbcTemplate db, String table, String requestId) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE request_id=?", Integer.class, requestId);
    }

    private static Jwt jwt(String issuer, String actor, String sid, String jti, Instant now) {
        return Jwt.withTokenValue("physical-restore-verified").header("alg", "RS256")
                .issuer(issuer).subject(actor).claim("sid", sid).claim("jti", jti)
                .issuedAt(now).expiresAt(now.plusSeconds(1800)).build();
    }
}
