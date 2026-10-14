package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;
import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8JobPackStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8JobPackEvidenceStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class JobPackDm8IntegrationTest {
    @Test void realDm8PersistsFunctionalValidationAndTriggersOnce() throws Exception {
        String url = required("JOB_PACK_DM8_URL");
        String user = required("JOB_PACK_DM8_USER");
        String password = required("JOB_PACK_DM8_PASSWORD");
        var source = new DriverManagerDataSource(url, user, password);
        source.setDriverClassName("dm.jdbc.driver.DmDriver");
        var db = new JdbcTemplate(source);
        migrate(db);
        var store = new Dm8JobPackStore(db, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        Path fileRoot = Files.createTempDirectory("enf-job-pack-i-");
        var files = new LocalMaterialFiles(fileRoot);
        var staged = files.stage("actual controlled task bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var evidenceStore = new Dm8JobPackEvidenceStore(db, Clock.systemUTC());
        var service = new JobPackService(store, (actor, organization, id, action) -> { }, (packageId, evidence) -> {
            assertThat(evidenceStore.contains(packageId, evidence)).isTrue();
            assertThat(files.read(evidence.fileKey())).isNotEmpty();
            assertThat(evidence.sha256()).isEqualTo(staged.sha256());
        }, Clock.systemUTC());
        String id = "ENF-JOB-I-" + UUID.randomUUID();
        try {
            var created = service.create("dm8-operator", "create-" + id,
                    new JobPackService.Create(id, "ORG-I", "功能验证任务（测试）", "FUNCTIONAL_VALIDATION"));
            var evidence = new Evidence("INPUT-REF", staged.sha256(), staged.key(), "input.bin");
            evidenceStore.register(id, evidence, "dm8-operator");
            var task = service.submit("dm8-operator", "task-" + id, id, FactType.TASK,
                    new JobPackService.FactInput(Map.of("externalTaskRef", "MECHANISM-ONLY", "externalTaskVersion", "1",
                            "originalTaskSha256", staged.sha256(), "currentProcess", "功能验证",
                            "expectedOutput", "测试任务包"), List.of(evidence)));
            service.verify("dm8-reviewer", "task-verify-" + id, id, FactType.TASK,
                    new JobPackService.VerificationInput(task.revision(), true, "测试任务复核"));
            var rights = service.submit("dm8-operator", "rights-" + id, id, FactType.SOURCE_RIGHTS,
                    new JobPackService.FactInput(Map.of("sourceRef", "PROJECT-TEST", "sourceVersion", "1",
                            "rightsHolder", "项目测试数据", "allowedUses", "FUNCTIONAL_VALIDATION",
                            "scope", "MECHANISM-ONLY", "validFrom", "2026-09-01", "validTo", "2027-09-01"),
                            List.of(evidence)));
            service.verify("dm8-reviewer", "rights-verify-" + id, id, FactType.SOURCE_RIGHTS,
                    new JobPackService.VerificationInput(rights.revision(), true, "测试用途复核"));
            var approval = service.submit("dm8-reviewer", "approval-" + id, id, FactType.APPROVAL,
                    new JobPackService.FactInput(Map.of("decision", "APPROVED", "method", "功能验证人工动作",
                            "reason", "仅验证产品链", "targetContentRevision", "2"), List.of(evidence)));
            service.verify("dm8-operator", "approval-verify-" + id, id, FactType.APPROVAL,
                    new JobPackService.VerificationInput(approval.revision(), true, "异人复核"));
            var receipt = service.submit("dm8-operator", "receipt-" + id, id, FactType.HUMAN_RECEIPT,
                    new JobPackService.FactInput(Map.of("personRef", "spoofed", "text", "测试回执",
                            "targetContentRevision", "2"), List.of()));
            service.verify("dm8-reviewer", "receipt-verify-" + id, id, FactType.HUMAN_RECEIPT,
                    new JobPackService.VerificationInput(receipt.revision(), true, "异人复核"));
            var evaluation = service.submit("dm8-operator", "evaluation-" + id, id,
                    FactType.BUSINESS_EVALUATION, new JobPackService.FactInput(Map.of("completeness", "5",
                            "traceability", "5", "operability", "5", "accuracy", "5", "humanEffort", "5",
                            "basis", "功能验证", "conclusion", "仅支持功能就绪", "targetContentRevision", "2"),
                            List.of()));
            var completed = service.verify("dm8-reviewer", "evaluation-verify-" + id, id,
                    FactType.BUSINESS_EVALUATION,
                    new JobPackService.VerificationInput(evaluation.revision(), true, "异人复核"));
            assertThat(created.state()).isEqualTo(State.COLLECTING);
            assertThat(completed.state()).isEqualTo(State.RUNNING);
            assertThat(store.latest(id).orElseThrow().facts()).noneMatch(fact -> fact.type() == FactType.ACTOR_POSITION);
            assertThat(store.latest(id).orElseThrow().facts().stream().filter(fact -> fact.type() == FactType.HUMAN_RECEIPT)
                    .findFirst().orElseThrow().fields()).containsEntry("personRef", "dm8-operator");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_revision WHERE package_id=?", Integer.class, id)).isEqualTo(11);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_receipt WHERE package_id=?", Integer.class, id)).isEqualTo(11);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_event WHERE package_id=?", Integer.class, id)).isEqualTo(11);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_file WHERE package_id=?", Integer.class, id)).isEqualTo(1);
            service.verify("dm8-reviewer", "evaluation-verify-" + id, id, FactType.BUSINESS_EVALUATION,
                    new JobPackService.VerificationInput(evaluation.revision(), true, "异人复核"));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?", Integer.class, id)).isEqualTo(1);
        } finally {
            db.update("DELETE FROM enf8_integration_trigger WHERE package_id=?", id);
            db.update("DELETE FROM enf8_job_pack_event WHERE package_id=?", id);
            db.update("DELETE FROM enf8_job_pack_receipt WHERE package_id=?", id);
            db.update("DELETE FROM enf8_job_pack_file WHERE package_id=?", id);
            db.update("DELETE FROM enf8_job_pack_revision WHERE package_id=?", id);
            files.discard(staged.key());
            Files.deleteIfExists(fileRoot);
        }
    }

    private static void migrate(JdbcTemplate db) throws Exception {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF8_JOB_PACK_REVISION'", Integer.class);
        if (count != null && count == 1) {
            Integer files = db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF8_JOB_PACK_FILE'", Integer.class);
            if (files != null && files == 0) db.execute("CREATE TABLE enf8_job_pack_file (file_key VARCHAR(36) PRIMARY KEY, package_id VARCHAR(120) NOT NULL, package_revision BIGINT DEFAULT 1 NOT NULL, evidence_ref VARCHAR(200) NOT NULL, sha256 VARCHAR(64) NOT NULL, original_name VARCHAR(500) NOT NULL, uploaded_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT uq_enf8_file_ref UNIQUE(package_id,evidence_ref), CONSTRAINT fk_enf8_file_package FOREIGN KEY(package_id,package_revision) REFERENCES enf8_job_pack_revision(package_id,revision))");
            return;
        }
        String script = Files.readString(Path.of("db", "dm8", "V062__enforcement_v2_job_pack.sql"));
        for (String statement : script.split(";")) if (!statement.isBlank()) db.execute(statement.trim());
    }

    private static String required(String name) {
        String value = cn.xinji.agentplatform.enforcement.testing.FixtureEnvironment.optional(name, "AGENT_JOBPACK_", System.getenv());
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be injected for real DM8 I");
        return value;
    }
}
