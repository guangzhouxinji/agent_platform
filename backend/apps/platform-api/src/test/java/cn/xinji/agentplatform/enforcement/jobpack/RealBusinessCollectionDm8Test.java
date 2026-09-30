package cn.xinji.agentplatform.enforcement.jobpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8JobPackStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8RealBusinessCollectionTaskStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.PublicInput;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.Evidence;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.FactType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RealBusinessCollectionDm8Test {
    @Test void realDm8PersistsOneClaimPublicPackageAndNoFalseTrigger() throws Exception {
        var source = new DriverManagerDataSource(required("JOB_PACK_DM8_URL"), required("JOB_PACK_DM8_USER"),
                required("JOB_PACK_DM8_PASSWORD"));
        source.setDriverClassName("dm.jdbc.driver.DmDriver");
        var db = new JdbcTemplate(source);
        migrate(db, "db/dm8/V062__enforcement_v2_job_pack.sql");
        migrate(db, "db/dm8/V066__enforcement_real_business_collection.sql");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var json = new ObjectMapper().findAndRegisterModules();
        var tasks = new Dm8RealBusinessCollectionTaskStore(db, tx, json, Clock.systemUTC());
        var packs = new Dm8JobPackStore(db, tx, json, Clock.systemUTC());
        var fileRoot = Files.createTempDirectory("enf-real-business-i-");
        var files = new LocalMaterialFiles(fileRoot);
        new PublicCollectionTaskImporter(tasks, files, json,
                "data/enforcement/public-job-pack-candidates-v1/candidates.json")
                .run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        assertThat(tasks.list()).hasSize(12);
        String taskId = "ENF-RBC-CNIPA-GUIDANCE-CASE-1";
        String packageId = "ENF-JOB-CNIPA-GUIDANCE-CASE-1";
        var input = tasks.find(taskId).orElseThrow().publicInput();
        try {
            var claimed = tasks.claim(taskId, "mechanism-operator", "ORG-I", "claim-real-public", "a".repeat(64));
            var claimedInput = new PublicInput(input.candidateId(), input.sourceUrl(), input.sourceName(), input.publishedAt(),
                    input.capturedAt(), input.rawSha256(), input.rawFileKey(), input.originalName(), input.collectionBatchId(),
                    input.collectionDecision(), input.publicFacts(), "mechanism-operator");
            var service = new JobPackService(packs, (actor, organization, id, action) -> { },
                    (packageRef, evidence) -> { }, Clock.systemUTC());
            service.createPublic("mechanism-operator", "create-real-public",
                    new JobPackService.CreatePublic(packageId, "ORG-I", "真实业务采集",
                            "REAL_BUSINESS_COLLECTION", claimedInput));
            assertThat(claimed.assignmentState()).isEqualTo("CLAIMED");
            assertThat(packs.latest(packageId).orElseThrow().state())
                    .isEqualTo(JobPackContract.State.WAITING_REAL_HUMAN_ACTION);
            assertThat(files.read(input.rawFileKey())).isNotEmpty();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_real_business_task_event WHERE task_id=?",
                    Integer.class, taskId)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?",
                    Integer.class, packageId)).isZero();
            var mechanismEvidence = new Evidence("MECHANISM-ONLY", input.rawSha256(), input.rawFileKey(), input.originalName());
            submitAndVerify(service, packageId, "mechanism-operator", "mechanism-reviewer", FactType.ACTOR_POSITION,
                    Map.of("personRef", "mechanism-operator", "organizationRef", "ORG-I",
                            "positionTitle", "机制测试岗位", "appointmentRef", "MECHANISM-ONLY",
                            "validFrom", "2026-09-01", "validTo", "2027-09-01"), List.of(mechanismEvidence));
            submitAndVerify(service, packageId, "mechanism-reviewer", "mechanism-operator", FactType.APPROVAL,
                    Map.of("decision", "APPROVED", "method", "机制测试人工动作", "reason", "仅验证交叉流程",
                            "targetContentRevision", "2"), List.of(mechanismEvidence));
            submitAndVerify(service, packageId, "mechanism-operator", "mechanism-reviewer", FactType.HUMAN_RECEIPT,
                    Map.of("personRef", "mechanism-operator", "positionTitle", "机制测试岗位",
                            "text", "机制测试回执，不是真人回执", "targetContentRevision", "2"), List.of());
            submitAndVerify(service, packageId, "mechanism-operator", "mechanism-reviewer", FactType.BUSINESS_EVALUATION,
                    Map.of("completeness", "5", "traceability", "5", "operability", "5", "accuracy", "5",
                            "humanEffort", "5", "basis", "机制测试数据", "conclusion", "仅证产品流程",
                            "targetContentRevision", "2"), List.of());
            assertThat(packs.latest(packageId).orElseThrow().state()).isEqualTo(JobPackContract.State.RUNNING);
            assertThat(packs.latest(packageId).orElseThrow().trigger().taskId()).isEqualTo("NEXT-V2-INT-ENF-01");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id=?",
                    Integer.class, packageId)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_receipt WHERE package_id=?",
                    Integer.class, packageId)).isEqualTo(9);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_job_pack_event WHERE package_id=?",
                    Integer.class, packageId)).isEqualTo(9);
            tasks.claim(taskId, "mechanism-operator", "ORG-I", "claim-real-public", "a".repeat(64));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM enf8_real_business_task_event WHERE task_id=?",
                    Integer.class, taskId)).isEqualTo(1);
            assertThatThrownBy(() -> tasks.claim(taskId, "other-operator", "ORG-I", "other-real-public",
                    "b".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        } finally {
            db.update("DELETE FROM enf8_integration_trigger WHERE package_id=?", packageId);
            db.update("DELETE FROM enf8_job_pack_event WHERE package_id=?", packageId);
            db.update("DELETE FROM enf8_job_pack_receipt WHERE package_id=?", packageId);
            db.update("DELETE FROM enf8_job_pack_revision WHERE package_id=?", packageId);
            db.update("DELETE FROM enf8_real_business_task_event WHERE task_id=?", taskId);
            db.update("DELETE FROM enf8_real_business_task WHERE collection_batch_id=?", input.collectionBatchId());
            try (var paths = Files.walk(fileRoot)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void submitAndVerify(JobPackService service, String packageId, String submitter, String verifier,
                                        FactType type, Map<String, String> fields, List<Evidence> evidence) {
        var submitted = service.submit(submitter, "dm8-submit-" + type, packageId, type,
                new JobPackService.FactInput(fields, evidence));
        service.verify(verifier, "dm8-verify-" + type, packageId, type,
                new JobPackService.VerificationInput(submitted.revision(), true, "仅核双人机制"));
    }

    private static void migrate(JdbcTemplate db, String path) throws Exception {
        String table = path.contains("V062") ? "ENF8_JOB_PACK_REVISION" : "ENF8_REAL_BUSINESS_TASK";
        Integer count = db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?", Integer.class, table);
        if (count != null && count > 0) return;
        for (String statement : Files.readString(java.nio.file.Path.of(path)).split(";"))
            if (!statement.isBlank()) db.execute(statement.trim());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be injected for real DM8 I");
        return value;
    }
}
