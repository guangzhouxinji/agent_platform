package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductSigningKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
class MaterialProductDm8Test {
    @TempDir Path temporary;

    @Test void productLifecycleReceiptAndOrphansOnDedicatedEnforcementDbAndFiles() throws Exception {
        String businessUrl = System.getenv("ENF_DEV_DB_URL");
        String authorityUrl = System.getenv("ENF_DEV_AUTHORITY_URL");
        String businessPassword = System.getenv("ENF_DEV_DB_PASSWORD");
        String authorityPassword = System.getenv("ENF_DEV_AUTHORITY_PASSWORD");
        Assumptions.assumeTrue(businessUrl != null && authorityUrl != null && businessPassword != null
                && authorityPassword != null && !businessUrl.equals(authorityUrl), "Dedicated ENF DBs required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EM" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        createUser(businessUrl, businessPassword, owner, secret);
        try {
            createUser(authorityUrl, authorityPassword, owner, secret);
            try {
                var businessSource = new DriverManagerDataSource(businessUrl, owner, secret);
                var authoritySource = new DriverManagerDataSource(authorityUrl, owner, secret);
                try (var connection = businessSource.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8/V032__product_identity.sql")));
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8/V035__enforcement_v2_material.sql")));
                }
                try (var connection = authoritySource.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/identity-authority/V001__grant_watermark.sql")));
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/identity-authority/V002__enforcement_source_watermark.sql")));
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/identity-authority/V005__enforcement_delete_watermark.sql")));
                }
                try { exercise(businessSource, authoritySource); }
                finally {
                    try (var connection = businessSource.getConnection()) {
                        ScriptUtils.executeSqlScript(connection,
                                new FileSystemResource(Path.of("db/dm8/U035__enforcement_v2_material.sql")));
                    }
                    dropTables(businessUrl, owner, secret, List.of("id2_grant_event", "id2_current_epoch",
                            "id2_grant", "id2_session", "id2_key_event", "id2_signing_key",
                            "id2_key_control", "id2_subject_event", "id2_subject"));
                    dropTables(authorityUrl, owner, secret, List.of("enf3_delete_watermark",
                            "enf3_source_watermark", "id2_maintenance_audit", "id2_maintainer",
                            "id2_authority_watermark"));
                }
            } finally { dropUser(authorityUrl, authorityPassword, owner); }
        } finally { dropUser(businessUrl, businessPassword, owner); }
    }
    private void exercise(DriverManagerDataSource businessSource, DriverManagerDataSource authoritySource) throws Exception {
        var db = new JdbcTemplate(businessSource);
        var authority = new JdbcTemplate(authoritySource);
        String issuer = "https://enf-v2-test.example.org";
        String actor = "named-enf-v2-operator";
        String job = "named-enf-v2-cleanup";
        String org = "ENF-V2-DEV";
        String sourceId = "PROJECT-DEV-OBSERVATION";
        String ruleId = "ENF-RULE-V1-DEV-MARKING-01";
        String empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", issuer, empty);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                issuer, actor, actor, "encoded", "ACTIVE", 1);
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                        + "credential_version,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                issuer, job, job, "encoded", "ACTIVE", 1);
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(master);
        String kid = new ProductSigningKeys(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), issuer,
                Base64.getEncoder().encodeToString(master), Duration.ofMinutes(5), Clock.systemUTC())
                .rotate("enf-maintainer");
        Instant now = Instant.now();
        String sid = UUID.randomUUID().toString();
        String jti = UUID.randomUUID().toString();
        db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,?,?)", sid, jti, issuer,
                actor, "enf-client", kid, Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)), "ACTIVE", null);
        Jwt jwt = Jwt.withTokenValue("test-verified").header("alg", "RS256").issuer(issuer).subject(actor)
                .claim("sid", sid).claim("jti", jti).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        var watermark = new ProductGrantWatermark(db, authority, issuer);
        var grants = new ProductGrants(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        var repository = new Dm8MaterialRepository(db,
                new TransactionTemplate(new DataSourceTransactionManager(businessSource)), Clock.systemUTC());
        Path root = temporary.resolve("dedicated-enforcement-files");
        var service = new MaterialProductService(repository, new LocalMaterialFiles(root),
                new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC()),
                Clock.systemUTC());

        grant(grants, watermark, issuer, actor, org, "SOURCE", sourceId, "CONFIGURE", "SOURCE_MANAGEMENT");
        grant(grants, watermark, issuer, actor, org, "SOURCE", sourceId, "SYNC", "SAVE");
        service.registerSource(actor, "source-1", new MaterialProductService.SourceInput(sourceId, org,
                "docs/02#40", 1, now.plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY"),
                MaterialPageHttpsSource.CREDENTIAL_REF));
        assertThat(authority.queryForObject("SELECT state FROM enf3_source_watermark WHERE source_id=?",
                String.class, sourceId)).isEqualTo("READY");
        grant(grants, watermark, issuer, actor, org, "SOURCE", sourceId, "SYNC", "QUERY");
        try (var pageServer = new MaterialPageHttpsSource(temporary)) {
            var pages = pageServer.connector(sourceId);
            var sync = new MaterialSourceSync(repository, pages, service,
                    new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC()),
                    Clock.systemUTC());
            assertThat(sync.onePage(actor, sourceId).state()).isEqualTo("CONTINUE");
            assertThat(repository.syncCursor(sourceId)).isEqualTo("p1-1");
            var partial = sync.onePage(actor, sourceId);
            assertThat(partial.state()).isEqualTo("PARTIAL_FAILED");
            assertThat(partial.failureCode()).isEqualTo("SOURCE_RECORD_FAILURE");
            assertThat(repository.syncCursor(sourceId)).isEqualTo("p2-2");
            var restartedDb = new JdbcTemplate(new DriverManagerDataSource(
                    businessSource.getUrl(), businessSource.getUsername(), businessSource.getPassword()));
            var restartedAuthority = new JdbcTemplate(new DriverManagerDataSource(
                    authoritySource.getUrl(), authoritySource.getUsername(), authoritySource.getPassword()));
            var restartedWatermark = new ProductGrantWatermark(restartedDb, restartedAuthority, issuer);
            var restartedRepo = new Dm8MaterialRepository(restartedDb,
                    new TransactionTemplate(new DataSourceTransactionManager(restartedDb.getDataSource())),
                    Clock.systemUTC());
            var restartedAccess = new ProductEnforcementAccess(restartedDb, restartedAuthority,
                    restartedWatermark, jwt, issuer, Clock.systemUTC());
            var restarted = new MaterialSourceSync(restartedRepo, pages,
                    new MaterialProductService(restartedRepo, new LocalMaterialFiles(root), restartedAccess,
                            Clock.systemUTC()), restartedAccess, Clock.systemUTC());
            assertThatThrownBy(() -> restarted.onePage(actor, sourceId))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("rate limited");
            assertThat(restartedRepo.syncCursor(sourceId)).isEqualTo("p2-2");
            assertThat(restarted.onePage(actor, sourceId).state()).isEqualTo("SUCCEEDED");
            assertThat(restartedRepo.syncCursor(sourceId)).isEqualTo("p2-3");
            var emptyPage = restarted.onePage(actor, sourceId);
            assertThat(emptyPage.state()).isEqualTo("SUCCEEDED");
            assertThat(emptyPage.processed()).isZero();
            assertThat(restartedRepo.syncCursor(sourceId)).isEqualTo("p2-3");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_observation", Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt", Integer.class)).isEqualTo(5);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE state='PARTIAL_FAILED' "
                    + "AND end_cursor='p2-2' AND failure_cursor='p2-2' "
                    + "AND failure_code='SOURCE_RECORD_FAILURE'", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_sync_attempt WHERE state='FAILED' "
                    + "AND end_cursor='p2-2' AND failure_code='RATE_LIMITED'",
                    Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE operation='OBSERVE'",
                    Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event WHERE operation='OBSERVE'",
                    Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
            MaterialSyncNegativeDm8Scenarios.run(repository, service, db, authority, watermark, jwt,
                    issuer, actor, sourceId, pages.fetch(sourceId, "START",
                            MaterialPageHttpsSource.CREDENTIAL_REF).entries().getFirst().observation());
            MaterialSourceConfigurationDm8Scenarios.run(repository, service, db, restarted, actor, sourceId);
            MaterialSourceInFlightDm8Scenarios.run(pageServer, restarted, service, repository, db, actor, sourceId);
            System.out.println("ENF_V2_M01_HTTPS pages=2 partial=SOURCE_RECORD_FAILURE "
                    + "rateLimit=429 restartCursor=p2-2 finalCursor=p2-3 observations=2 receipts=2 events=2 clues=0");
        }
        var records = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/observations.json"))).get("records");
        var first = records.get(0);
        var observed = service.observe(actor, "SYNC:" + MaterialDigests.hash(sourceId,
                first.get("recordId").asText(), first.get("version").asText()), new MaterialProductService.ObservationInput(
                sourceId, first.get("recordId").asText(), first.get("version").asText(),
                first.get("sourceText").asText(), first.get("materialMention").asText(), List.of(
                new MaterialWork.ActorInput("OPERATOR_CANDIDATE", "运营方甲", null),
                new MaterialWork.ActorInput("SUPPLIER_CANDIDATE", "供货方乙", null))));
        var next = records.get(1);
        var newer = service.observe(actor, "SYNC:" + MaterialDigests.hash(sourceId,
                next.get("recordId").asText(), next.get("version").asText()), new MaterialProductService.ObservationInput(
                sourceId, next.get("recordId").asText(), next.get("version").asText(),
                next.get("sourceText").asText(), next.get("materialMention").asText(), List.of(
                new MaterialWork.ActorInput("OPERATOR_CANDIDATE", "运营方甲", null),
                new MaterialWork.ActorInput("SUPPLIER_CANDIDATE", "供货方乙", null))));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_actor_mention", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_actor_mention WHERE business_code IS NOT NULL",
                Integer.class)).isZero();
        grant(grants, watermark, issuer, actor, org, "RULE", ruleId, "EDIT_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, issuer, actor, org, "RULE", ruleId, "PUBLISH_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, issuer, actor, org, "RULE", ruleId, "DISABLE_RULE", "RULE_MANAGEMENT");
        service.draftRule(actor, "draft-1", new MaterialProductService.RuleInput(ruleId, sourceId,
                "专利产品", "docs/02#40"));
        grant(grants, watermark, issuer, actor, org, "OBSERVATION", observed.resultId(), "READ", "DISPLAY");
        grant(grants, watermark, issuer, actor, org, "OBSERVATION", newer.resultId(), "READ", "DISPLAY");
        int beforeTrial = db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class);
        assertThat(service.trialRule(actor, ruleId, observed.resultId()).outcome()).isEqualTo("MATCH");
        assertThat(service.trialRule(actor, ruleId, newer.resultId()).outcome()).isEqualTo("NO_MATCH");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class)).isEqualTo(beforeTrial);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
        grant(grants, watermark, issuer, actor, org, "OBSERVATION", observed.resultId(), "CREATE_CLUE", "QUERY");
        grant(grants, watermark, issuer, actor, org, "OBSERVATION", newer.resultId(), "CREATE_CLUE", "QUERY");
        assertThatThrownBy(() -> service.discover(actor, "trial-1", observed.resultId(), ruleId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ruleState(actor, "publish-invalid", ruleId, 1,
                "PUBLISH_RULE", newer.resultId(), observed.resultId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_rule_review", Integer.class)).isZero();
        service.ruleState(actor, "publish-1", ruleId, 1, "PUBLISH_RULE",
                observed.resultId(), newer.resultId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_rule_review", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT positive_observation_id FROM enf3_rule_review WHERE rule_id=?",
                String.class, ruleId)).isEqualTo(observed.resultId());
        assertThatThrownBy(() -> service.discover(actor, "no-match", newer.resultId(), ruleId))
                .isInstanceOf(IllegalArgumentException.class);
        var clue = service.discover(actor, "discover-1", observed.resultId(), ruleId);
        assertThat(service.discover(actor, "discover-1", observed.resultId(), ruleId)).isEqualTo(clue);
        String secondRule = "ENF-DEV-SECOND-MARKING";
        grant(grants, watermark, issuer, actor, org, "RULE", secondRule, "EDIT_RULE", "RULE_MANAGEMENT");
        grant(grants, watermark, issuer, actor, org, "RULE", secondRule, "PUBLISH_RULE", "RULE_MANAGEMENT");
        service.draftRule(actor, "draft-second", new MaterialProductService.RuleInput(secondRule, sourceId,
                "专利产品", "docs/02#40"));
        service.ruleState(actor, "publish-second", secondRule, 1, "PUBLISH_RULE",
                observed.resultId(), newer.resultId());
        assertThat(service.discover(actor, "discover-second", observed.resultId(), secondRule).resultId())
                .isEqualTo(clue.resultId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue_rule", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isEqualTo(1);
        grant(grants, watermark, issuer, actor, org, "CLUE", clue.resultId(), "REVISE", "SAVE");
        service.revise(actor, "revise-1", clue.resultId(), 1, first.get("sourceText").asText() + " 待核",
                "保留原文并追加人工修订");
        grant(grants, watermark, issuer, actor, org, "CLUE", clue.resultId(), 2,
                "REGISTER_MATERIAL", "SAVE");
        byte[] original = Files.readAllBytes(Path.of("data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        Instant fileExpiry = now.plusSeconds(3600);
        List<String> fileUses = List.of("SAVE", "PREVIEW", "DOWNLOAD");
        assertThatThrownBy(() -> service.registerMaterial(actor, "bind-failure",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, "X".repeat(201), "2026-09-27")))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='bind-failure'", Integer.class))
                .isZero();
        var material = service.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27"));
        assertThat(service.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27")))
                .isEqualTo(material);
        assertThatThrownBy(() -> service.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-28")))
                .isInstanceOf(IllegalArgumentException.class);
        grant(grants, watermark, issuer, actor, org, "MATERIAL", material.resultId(), "PREVIEW", "PREVIEW");
        assertThat(service.preview(actor, material.resultId())).isEqualTo(original);
        MaterialCollectionDm8Scenarios.run(db, authority, watermark, grants, repository, root,
                jwt, actor, issuer, org, kid, now, material.resultId());
        for (Path file : Files.list(root).toList())
            Files.setLastModifiedTime(file, FileTime.from(now.minus(11, ChronoUnit.MINUTES)));
        var cleanup = new EnforcementMaterialCleanupJob(repository, new LocalMaterialFiles(root), db,
                watermark, issuer, job, org, "ENF-V2-MATERIAL");
        assertThatThrownBy(cleanup::run).isInstanceOf(SecurityException.class);
        grant(grants, watermark, issuer, job, org, "MATERIAL_WORK", "ENF-V2-MATERIAL",
                "CLEANUP", "RETENTION_CLEANUP");
        cleanup.run();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_cleanup_event WHERE state='SUCCEEDED'",
                Integer.class)).isEqualTo(3);
        assertThat(Files.readAllBytes(root.resolve(repository.material(material.resultId()).orElseThrow().fileKey()
                + ".blob")))
                .isEqualTo(original);
        assertThatThrownBy(() -> service.preview(actor, material.resultId()))
                .isInstanceOf(SecurityException.class);
        var secondBusiness = new JdbcTemplate(new DriverManagerDataSource(
                businessSource.getUrl(), businessSource.getUsername(), businessSource.getPassword()));
        var secondAuthority = new JdbcTemplate(new DriverManagerDataSource(
                authoritySource.getUrl(), authoritySource.getUsername(), authoritySource.getPassword()));
        var secondWatermark = new ProductGrantWatermark(secondBusiness, secondAuthority, issuer);
        var second = new MaterialProductService(new Dm8MaterialRepository(secondBusiness,
                new TransactionTemplate(new DataSourceTransactionManager(secondBusiness.getDataSource())),
                Clock.systemUTC()), new LocalMaterialFiles(root),
                new ProductEnforcementAccess(secondBusiness, secondAuthority, secondWatermark, jwt, issuer,
                        Clock.systemUTC()), Clock.systemUTC());
        // The first response is discarded: a separate process reconstructs the committed receipt.
        var recovered = second.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27"));
        assertThat(recovered).isEqualTo(material);
        assertThatThrownBy(() -> second.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-28")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-1'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event WHERE request_id='material-1'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material WHERE material_id=?",
                Integer.class, material.resultId())).isEqualTo(1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var input = new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40",
                    fileExpiry, fileUses, actor, "2026-09-27");
            var firstCommit = pool.submit(() -> {
                ready.countDown(); start.await(); return service.registerMaterial(actor, "material-concurrent", input);
            });
            var secondCommit = pool.submit(() -> {
                ready.countDown(); start.await(); return second.registerMaterial(actor, "material-concurrent", input);
            });
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(firstCommit.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(secondCommit.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-concurrent'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event WHERE request_id='material-concurrent'",
                Integer.class)).isEqualTo(1);
        MaterialProductRecoveryCapture.pause(businessSource, db, root, material, clue, jwt, fileExpiry);
        if (System.getenv("ENF_PRODUCT_RECOVERY_GATE") != null) return;
        MaterialRuleNegativeDm8Scenarios.run(service, db, authority, grants, watermark, issuer,
                actor, org, ruleId, first.get("sourceText").asText());
        second.ruleState(actor, "disable-1", ruleId, 1, "DISABLE_RULE", null, null);
        assertThatThrownBy(() -> service.discover(actor, "new-discover", observed.resultId(), ruleId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isEqualTo(1);
        MaterialDeletionDm8Scenarios.expiredMaterial(db, repository,
                new ProductEnforcementAccess(db, authority, watermark, jwt, issuer, Clock.systemUTC()),
                actor, material.resultId(), root);
        MaterialDeletionDm8Scenarios.run(db, authority, second, actor, sourceId,
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40",
                        fileExpiry, fileUses, actor, "2026-09-27"), root);
        authority.update("UPDATE enf3_source_watermark SET state='WITHDRAWN' WHERE source_id=?", sourceId);
        assertThatThrownBy(() -> service.trialRule(actor, ruleId, observed.resultId()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.preview(actor, material.resultId())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> second.preview(actor, material.resultId())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> second.registerMaterial(actor, "material-1",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27"))).isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='material-1'",
                Integer.class)).isEqualTo(1);
        System.out.printf("ENF_V2_MATERIAL_DM8 source=2 rule=ACTIVE_DISABLED clue=1 receipt=CROSS_INSTANCE_REPLAY_CONFLICT "
                + "orphan=REMOVED referenced=RETAINED bytes=%d secondInstance=DENIED sourceWithdrawal=DENIED%n",
                original.length);
    }
    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String issuer,
                              String actor, String org, String type, String id, String action, String purpose) {
        grant(grants, watermark, issuer, actor, org, type, id, 1, action, purpose);
    }
    private static void grant(ProductGrants grants, ProductGrantWatermark watermark, String issuer,
                              String actor, String org, String type, String id, long version,
                              String action, String purpose) {
        var scope = new ProductGrants.Scope(issuer, actor, org, "ENFORCEMENT", "ENF-V2-MATERIAL",
                "ENF-MATERIAL-DEV-PRODUCT-CONTRACT-V1",
                "SOURCE".equals(type) ? "ENFORCEMENT_SOURCE" : type, id, version, action, purpose);
        watermark.change(() -> grants.issue(scope, Instant.now().plusSeconds(3600), 0,
                "enf-maintainer", UUID.randomUUID().toString(), "a".repeat(64)));
    }
    private static void createUser(String url, String password, String name, String secret) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + name + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + name);
        }
    }
    private static void dropUser(String url, String password, String name) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) { statement.execute("DROP USER " + name); }
    }

    private static void dropTables(String url, String name, String secret, List<String> tables) throws SQLException {
        try (var connection = DriverManager.getConnection(url, name, secret);
             var statement = connection.createStatement()) {
            for (String table : tables) {
                try { statement.execute("DROP TABLE " + table + " CASCADE"); }
                catch (SQLException absent) { /* A partial migration may leave some tables absent. */ }
            }
        }
    }
}
