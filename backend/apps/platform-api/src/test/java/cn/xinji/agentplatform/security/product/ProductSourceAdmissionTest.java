package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductSourceAdmissionTest {
    private static final String HASH = "a".repeat(64);
    private static final String ISSUER = "https://identity.example.org";
    @TempDir Path root;

    @Test void realCaptureDecisionUsesProductRouteSemanticsAndIndependentCurrentWatermark()
            throws Exception {
        JdbcDataSource main = database();
        JdbcDataSource independent = database();
        for (String name : List.of("V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V039__product_source_verification.sql",
                "V041__product_task_authorization_version.sql",
                "V042__product_consultation_goal.sql", "V043__product_task_cancel_epoch.sql",
                "V044__product_task_session_resume.sql", "V045__product_task_execution_fence.sql",
                "V046__product_workload_subject.sql", "V047__product_task_worker_delegation.sql",
                "V048__product_task_execution_reconciliation.sql",
                "V053__product_source_use_decision.sql")) script(main, "db/dm8/" + name);
        script(independent, "db/identity-authority/V001__grant_watermark.sql");
        script(independent, "db/identity-authority/V003__consultation_source_watermark.sql");
        JdbcTemplate db = new JdbcTemplate(main);
        JdbcTemplate authority = new JdbcTemplate(independent);
        authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) "
                + "VALUES(?,0,'READY',NULL,?)", ISSUER,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                + "credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,CURRENT_TIMESTAMP)",
                ISSUER, "member", "member", "encoded");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(main));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var retention = new ProductRetentionPolicy("approved-retention", 1, HASH,
                Duration.ofHours(2));
        var grants = new ProductGrants(db, transaction, Clock.systemUTC());
        var task = new ProductInitialTaskService(new JdbcProductTaskDirectory(db, Clock.systemUTC()),
                grants, new ProductGrantWatermark(db, authority, ISSUER), transaction, mapper,
                Clock.systemUTC(), ISSUER, retention).create(
                new ProductInitialTaskService.Input("organization", "Patent work", "member",
                        "approved-retention", 1, Instant.now().plusSeconds(3600)),
                "initial-task", "maintainer#certificate", "organization");
        String scopeId = db.queryForObject("SELECT scope_id FROM con3_data_scope WHERE task_id=?",
                String.class, task.taskId());
        Path capture = Path.of("data/patents/public-pilot-v1");
        try (var paths = Files.walk(capture)) {
            for (Path path : paths.toList()) {
                Path destination = root.resolve("capture").resolve(capture.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        Path terms = Path.of("data/enforcement/source-review/surechembl-v1/raw/embl-ebi-terms.html");
        Files.copy(terms, root.resolve("terms.html"));
        var manifest = mapper.readTree(Files.readAllBytes(root.resolve("capture/manifest.json")));
        String sourceVersion = manifest.path("datasetId").asText() + ":"
                + manifest.path("generatedAt").asText();
        var candidates = new ProductSourceCandidates(db, transaction, mapper, retention,
                Clock.systemUTC());
        Instant until = Instant.now().plusSeconds(1800);
        var candidate = candidates.register(new ProductSourceCandidates.Declaration(
                "SURECHEMBL_PUBLIC_SEARCH", "capture-contract-1", sourceVersion,
                "https://surechembl.org/api", "EMBL-EBI", "terms.html",
                digest(root.resolve("terms.html")), Map.of("jurisdiction", List.of("CN")),
                List.of("TITLE"), Map.of("TITLE", List.of("QUERY")), List.of("TITLE"),
                false, false, true, true, true, Instant.now().minusSeconds(60), until),
                "register-capture", "maintainer#certificate");
        var files = new ProductSourceEvidenceFiles(root, 10_000_000);
        var verification = new ProductSourceVerification(db, transaction, candidates, files,
                mapper, Clock.systemUTC()).verify(new ProductSourceVerification.Input(
                candidate.sourceId(), candidate.contractVersion(), 1, "capture/manifest.json",
                digest(root.resolve("capture/manifest.json")), "verify-capture"),
                "maintainer#certificate");
        assertThat(verification.recordCount()).isEqualTo(44);
        var decision = new ProductSourceAdmission.Decision("1", candidate.sourceId(),
                candidate.contractVersion(), verification.verificationId(), "organization",
                task.taskId(), scopeId, "UNRESOLVED", "terms.html",
                digest(root.resolve("terms.html")), "capture/manifest.json",
                digest(root.resolve("capture/manifest.json")), "approved-retention", 1,
                HASH, "development-team", until.minusSeconds(10),
                Map.of("TITLE", List.of("QUERY")));
        Files.write(root.resolve("decision.json"), mapper.writeValueAsBytes(decision));
        var watermark = new ProductSourceWatermark(db, authority, transaction);
        var admission = new ProductSourceAdmission(db, candidates, files, watermark, mapper,
                Clock.systemUTC());
        byte[] certificateBytes = "project-source-maintainer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(certificateBytes);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(certificateBytes));
        authority.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,"
                + "certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,'SOURCES','ACTIVE',1)",
                "maintainer", "organization", fingerprint);
        var controller = new ProductSourceAdmissionController(admission,
                new ProductMaintenanceProof(authority,
                        new TransactionTemplate(new DataSourceTransactionManager(independent))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {certificate});
        var input = new ProductSourceAdmission.Input(candidate.sourceId(),
                candidate.contractVersion(), 1, "decision.json",
                digest(root.resolve("decision.json")), "enable-capture");
        assertThatThrownBy(() -> controller.enable(input.sourceId(), input.contractVersion(),
                new ProductSourceAdmissionController.Enable(1, input.decisionRef(),
                        input.decisionSha()), "no-certificate", new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class);
        authority.update("UPDATE id2_maintainer SET allowed_roles='GRANTS' WHERE singleton_key=1");
        assertThatThrownBy(() -> controller.enable(input.sourceId(), input.contractVersion(),
                new ProductSourceAdmissionController.Enable(1, input.decisionRef(),
                        input.decisionSha()), "wrong-role", request))
                .isInstanceOf(ResponseStatusException.class);
        authority.update("UPDATE id2_maintainer SET allowed_roles='SOURCES' WHERE singleton_key=1");
        assertThatThrownBy(() -> admission.enable(input, "maintainer#certificate", "other-org"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admission.enable(new ProductSourceAdmission.Input(
                input.sourceId(), input.contractVersion(), 1, input.decisionRef(),
                "0".repeat(64), "bad-sha"), "maintainer#certificate", "organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_current", Integer.class)).isZero();
        var enabled = controller.enable(input.sourceId(), input.contractVersion(),
                new ProductSourceAdmissionController.Enable(1, input.decisionRef(),
                        input.decisionSha()), input.requestId(), request);
        assertThat(enabled.productStatus()).isEqualTo("PRODUCT_ENABLED");
        assertThat(enabled.rightsEvidenceStatus()).isEqualTo("UNRESOLVED");
        var backupAuthority = new ProductTaskBackupAuthority(db, authority,
                new ProductGrantWatermark(db, authority, ISSUER), watermark, ISSUER,
                Clock.systemUTC());
        var snapshot = backupAuthority.capture("batch-1", "PRODUCT_DB", "key-1",
                Instant.now().plusSeconds(1200), "approved-retention", 1);
        assertThat(snapshot.sourceWatermarks().get(candidate.sourceId()).epoch()).isEqualTo(1);
        backupAuthority.assertUnchanged(snapshot);
        assertThat(admission.enable(input, "maintainer#" + fingerprint, "organization"))
                .isEqualTo(enabled);
        var availability = new ProductSourceAvailability(db, watermark, Clock.systemUTC());
        assertThat(availability.require(candidate.sourceId(), "TITLE", "QUERY").epoch())
                .isEqualTo(1);
        assertThatThrownBy(() -> availability.require(candidate.sourceId(), "CLAIMS", "DISPLAY"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_product_decision SET rights_evidence_status='WITHDRAWN' "
                + "WHERE decision_id=?", enabled.decisionId());
        assertThatThrownBy(() -> availability.require(candidate.sourceId(), "TITLE", "QUERY"))
                .isInstanceOf(IllegalStateException.class);
        db.update("UPDATE con4_source_product_decision SET rights_evidence_status='UNRESOLVED' "
                + "WHERE decision_id=?", enabled.decisionId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_authority_event",
                Integer.class)).isEqualTo(1);
        assertThat(authority.queryForObject("SELECT COUNT(*) FROM id2_maintenance_audit "
                + "WHERE role_code='SOURCES' AND decision='ALLOW'", Integer.class)).isEqualTo(2);
        var revoked = controller.withdraw(candidate.sourceId(),
                new ProductSourceAdmissionController.Withdraw(1), "withdraw-capture", request);
        assertThat(revoked.productStatus()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> backupAuthority.assertUnchanged(snapshot))
                .isInstanceOf(IllegalStateException.class);
        var withdrawnSnapshot = backupAuthority.capture("batch-2", "PRODUCT_DB", "key-1",
                Instant.now().plusSeconds(1200), "approved-retention", 1);
        var nextManifest = mapper.readTree(Files.readAllBytes(root.resolve("capture/manifest.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextManifest).put("generatedAt",
                Instant.now().toString());
        Files.write(root.resolve("capture/manifest-v2.json"), mapper.writeValueAsBytes(nextManifest));
        String nextSourceVersion = nextManifest.path("datasetId").asText() + ":"
                + nextManifest.path("generatedAt").asText();
        var nextCandidate = candidates.register(new ProductSourceCandidates.Declaration(
                candidate.sourceId(), "capture-contract-2", nextSourceVersion,
                "https://surechembl.org/api", "EMBL-EBI", "terms.html",
                digest(root.resolve("terms.html")), Map.of("jurisdiction", List.of("CN")),
                List.of("TITLE"), Map.of("TITLE", List.of("QUERY")), List.of("TITLE"),
                false, false, true, true, true, Instant.now().minusSeconds(60), until),
                "register-next", "maintainer#certificate");
        var nextVerification = new ProductSourceVerification(db, transaction, candidates, files,
                mapper, Clock.systemUTC()).verify(new ProductSourceVerification.Input(
                nextCandidate.sourceId(), nextCandidate.contractVersion(), 1,
                "capture/manifest-v2.json", digest(root.resolve("capture/manifest-v2.json")),
                "verify-next"), "maintainer#certificate");
        var nextDecision = new ProductSourceAdmission.Decision("2", nextCandidate.sourceId(),
                nextCandidate.contractVersion(), nextVerification.verificationId(), "organization",
                task.taskId(), scopeId, "UNRESOLVED", "terms.html",
                digest(root.resolve("terms.html")), "capture/manifest-v2.json",
                digest(root.resolve("capture/manifest-v2.json")), "approved-retention", 1,
                HASH, "development-team", until.minusSeconds(10),
                Map.of("TITLE", List.of("QUERY")));
        Files.write(root.resolve("decision-v2.json"), mapper.writeValueAsBytes(nextDecision));
        var nextEnabled = controller.enable(nextCandidate.sourceId(), nextCandidate.contractVersion(),
                new ProductSourceAdmissionController.Enable(1, "decision-v2.json",
                        digest(root.resolve("decision-v2.json"))), "enable-next", request);
        assertThat(nextEnabled.sourceEpoch()).isEqualTo(3);
        assertThat(watermark.current(candidate.sourceId()).rowVersion()).isEqualTo(3);
        assertThat(availability.require(candidate.sourceId(), "TITLE", "QUERY")
                .contractVersion()).isEqualTo("capture-contract-2");
        assertThatThrownBy(() -> controller.withdraw(candidate.sourceId(),
                new ProductSourceAdmissionController.Withdraw(1), "withdraw-capture", request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> backupAuthority.assertUnchanged(withdrawnSnapshot))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_authority_event",
                Integer.class)).isEqualTo(3);
        String grantId = db.queryForObject("SELECT grant_id FROM id2_grant WHERE subject_id='member' "
                + "AND status='ACTIVE' ORDER BY grant_id LIMIT 1", String.class);
        new ProductGrantWatermark(db, authority, ISSUER).change(() -> grants.revoke(grantId, 1,
                "maintainer#" + fingerprint, "revoke-for-backup", "revoke-for-backup-sha"));
        assertThatThrownBy(() -> backupAuthority.assertUnchanged(withdrawnSnapshot))
                .isInstanceOf(IllegalStateException.class);
        assertThat(availability.require(candidate.sourceId(), "TITLE", "QUERY").epoch())
                .isEqualTo(3);
        assertThatThrownBy(() -> admission.enable(input, "maintainer#certificate", "organization"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_authority_event",
                Integer.class)).isEqualTo(3);
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
    private static JdbcDataSource database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-admission-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }
    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
