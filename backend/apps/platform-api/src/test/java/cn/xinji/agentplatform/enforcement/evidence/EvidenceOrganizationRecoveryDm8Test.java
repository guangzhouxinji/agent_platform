package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class EvidenceOrganizationRecoveryDm8Test {
    @Test void restoredEvidenceDbFilesRevisionsAndReceiptsReconcile() throws Exception {
        String gate = System.getenv("ENF_EVIDENCE_RECOVERY_GATE");
        String url = System.getenv("ENF_EVIDENCE_RESTORED_DB_URL");
        String root = System.getenv("ENF_EVIDENCE_RESTORED_ROOT");
        Assumptions.assumeTrue(gate != null && url != null && root != null,
                "Evidence DB and file physical restore required");
        Properties checkpoint = new Properties();
        try (var input = Files.newInputStream(Path.of(gate))) { checkpoint.load(input); }
        var source = new DriverManagerDataSource(url, checkpoint.getProperty("businessUser"),
                checkpoint.getProperty("businessPassword"));
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var json = new ObjectMapper();
        var organizations = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC());
        var rules = new Dm8EvidenceRuleStore(db, tx, Clock.systemUTC());
        var materials = new Dm8MaterialRepository(db, tx, Clock.systemUTC());
        Path recovered = Path.of(root);
        var files = new LocalMaterialFiles(recovered.resolve("files"));
        assertThat(organizations.latest("ORG-CASE-001")).isEqualTo(4);
        assertThat(organizations.revision("ORG-CASE-001", 1).orElseThrow().state())
                .isEqualTo("PENDING_REVIEW");
        assertThat(organizations.revision("ORG-CASE-001", 2).orElseThrow().links())
                .anyMatch(link -> "HUMAN_CORRECTION".equals(link.originKind()));
        assertThat(organizations.revision("ORG-CASE-001", 3).orElseThrow().state())
                .isEqualTo("CONFIRMED");
        var revision = organizations.revision("ORG-CASE-001", 4).orElseThrow();
        assertThat(revision.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(revision.evidenceSet()).hasSize(2);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(5);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(5);
        for (int index = 0; index < Integer.parseInt(checkpoint.getProperty("evidenceCount")); index++) {
            String prefix = "evidence." + index + ".";
            String id = checkpoint.getProperty(prefix + "id");
            byte[] original = Files.readAllBytes(recovered.resolve("files")
                    .resolve(checkpoint.getProperty(prefix + "fileKey") + ".blob"));
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
            assertThat(actual).isEqualTo(checkpoint.getProperty(prefix + "sha256"));
            assertThat(materials.material(id).orElseThrow().sha256()).isEqualTo(actual);
        }
        var authorityAvailable = new AtomicBoolean(true);
        MaterialAccess current = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                                          long version, String action, String purpose) {
                if (!authorityAvailable.get()) throw new SecurityException("Authority unavailable");
            }
            @Override public void requireSource(String id, String use, long epoch) {
                if (!authorityAvailable.get()) throw new SecurityException("Authority unavailable");
            }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var evidenceSource = new VerifiedEvidenceSource(recovered.resolve("case-snapshot.json"),
                json, materials, files, current, Clock.systemUTC());
        var service = new EvidenceOrganizationService(rules, organizations, evidenceSource,
                (actor, org, type, id, version, action, purpose) ->
                        current.require(actor, org, type, id, version, action, purpose));
        assertThat(service.read("named-corrector", "ORG-CASE-001", 4)).isEqualTo(revision);
        var exclusion = revision.exclusions().getFirst();
        var replay = service.exclude(exclusion.actor(), "exclude-b", revision.id(),
                new EvidenceOrganizationService.Exclude(3, exclusion.evidenceId(), exclusion.reason()));
        assertThat(replay.revision()).isEqualTo(4);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(5);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(5);
        authorityAvailable.set(false);
        assertThatThrownBy(() -> service.exclude(exclusion.actor(), "exclude-b", revision.id(),
                new EvidenceOrganizationService.Exclude(3, exclusion.evidenceId(), exclusion.reason())))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.read("named-corrector", "ORG-CASE-001", 4))
                .isInstanceOf(SecurityException.class);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(5);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(5);
        System.out.println("ENF_EVIDENCE_PHYSICAL_RESTORE revisions=4 evidenceFiles=3 "
                + "receipts=5 events=5 replay=SAME unavailable=DENIED");
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
