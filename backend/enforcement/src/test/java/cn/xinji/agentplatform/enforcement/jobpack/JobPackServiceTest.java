package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class JobPackServiceTest {
    private static final String PACK = "ENF-REAL-JOB-001";
    private static final Evidence EVIDENCE = new Evidence("EV-1", "0".repeat(64), "job/ev-1", "evidence.bin");
    private final MemoryStore store = new MemoryStore();
    private final List<String> actions = new ArrayList<>();
    private final JobPackService service = new JobPackService(store,
            (actor, organization, id, action) -> actions.add(actor + ":" + action),
            (packageId, evidence) -> { },
            Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneOffset.UTC));

    @Test void missingFactsStayWaitingAndCannotBeSelfVerified() {
        service.create("submitter", "create", new JobPackService.Create(PACK, "ORG-1", "真实线索任务", "REAL_INTEGRATION"));
        assertThat(service.read("submitter", PACK).state()).isEqualTo(State.COLLECTING);
        service.submit("submitter", "task", PACK, FactType.TASK, fact(Map.of(
                "externalTaskRef", "TASK-001", "externalTaskVersion", "1", "originalTaskSha256", "1".repeat(64),
                "currentProcess", "人工登记并复核", "expectedOutput", "辅助任务包")));
        assertThat(service.read("submitter", PACK).state()).isEqualTo(State.WAITING_DATA);
        assertThatThrownBy(() -> service.verify("submitter", "self", PACK, FactType.TASK,
                new JobPackService.VerificationInput(2, true, "本人确认")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Independent");
        assertThat(store.latest(PACK).orElseThrow().revision()).isEqualTo(2);
    }

    @Test void verifiedRealFactSetCreatesOneAutomaticIntegrationTrigger() {
        service.create("submitter", "create", new JobPackService.Create(PACK, "ORG-1", "真实线索任务", "REAL_INTEGRATION"));
        submitAndVerify("submitter", "reviewer", FactType.TASK, Map.of(
                "externalTaskRef", "TASK-001", "externalTaskVersion", "1", "originalTaskSha256", "1".repeat(64),
                "currentProcess", "人工登记并复核", "expectedOutput", "辅助任务包"), true);
        submitAndVerify("rights-owner", "reviewer", FactType.SOURCE_RIGHTS, Map.of(
                "sourceRef", "SOURCE-1", "sourceVersion", "1", "rightsHolder", "权利方",
                "allowedUses", "QUERY,SAVE,DISPLAY,REAL_INTEGRATION", "scope", "TASK-001",
                "validFrom", "2026-09-01", "validTo", "2027-09-01"), true);
        submitAndVerify("operator", "reviewer", FactType.ACTOR_POSITION, Map.of(
                "personRef", "operator", "organizationRef", "ORG-1", "positionTitle", "核查人员",
                "appointmentRef", "APPOINT-1", "validFrom", "2026-09-01", "validTo", "2027-09-01"), true);
        submitAndVerify("approver", "reviewer", FactType.APPROVAL, Map.of(
                "decision", "APPROVED", "method", "在线逐项审阅", "reason", "同意受控验证", "targetContentRevision", "3"), true);
        submitAndVerify("operator", "reviewer", FactType.HUMAN_RECEIPT, Map.of(
                "personRef", "operator", "positionTitle", "核查人员", "text", "已核收并确认任务边界", "targetContentRevision", "3"), false);
        var finalReceipt = submitAndVerify("evaluator", "reviewer", FactType.BUSINESS_EVALUATION, Map.of(
                "completeness", "5", "traceability", "5", "operability", "4", "accuracy", "4", "humanEffort", "4",
                "basis", "逐项复核材料与回执", "conclusion", "可进入受控真实集成", "targetContentRevision", "3"), false);
        var running = service.read("reviewer", PACK);
        assertThat(finalReceipt.state()).isEqualTo(State.RUNNING);
        assertThat(running.trigger()).isNotNull();
        assertThat(running.trigger().taskId()).isEqualTo("NEXT-V2-INT-ENF-01");
        assertThat(store.triggered()).isEqualTo(1);
        assertThat(actions).contains("reviewer:TRIGGER");
        service.complete("integration-runner", "complete", PACK,
                new JobPackService.Completion(running.revision(), "PASSED", "INT-RECEIPT-1", "真实集成验收通过"));
        assertThat(service.read("integration-runner", PACK).state()).isEqualTo(State.PASSED);
    }

    @Test void publicInputWaitsOnlyForActualHumanFactsAndClaimantCannotApprove() {
        var input = new PublicInput("ENF-PUBLIC-CNIPA-1", "https://www.cnipa.gov.cn/example",
                "国家知识产权局", "2021-04-01", "2026-09-29T06:52:02Z", "2".repeat(64),
                "public/file-1", "source.html", "BATCH-1", "COLLECTION_ALLOWED",
                Map.of("realPublicTask", "公开任务"), "operator");
        service.createPublic("operator", "create-public", new JobPackService.CreatePublic(PACK,
                "ORG-1", "公开任务", "REAL_BUSINESS_COLLECTION", input));
        var waiting = service.read("operator", PACK);
        assertThat(waiting.state()).isEqualTo(State.WAITING_REAL_HUMAN_ACTION);
        assertThat(waiting.findings()).containsExactly("MISSING:ACTOR_POSITION", "MISSING:APPROVAL",
                "MISSING:HUMAN_RECEIPT", "MISSING:BUSINESS_EVALUATION");
        assertThat(waiting.findings()).doesNotContain("MISSING:TASK", "MISSING:SOURCE_RIGHTS");
        assertThatThrownBy(() -> service.submit("operator", "self-approve", PACK, FactType.APPROVAL,
                fact(Map.of("decision", "APPROVED", "method", "在线", "reason", "本人批准",
                        "targetContentRevision", "1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Self approval");
        assertThat(store.latest(PACK).orElseThrow().revision()).isEqualTo(1);
    }

    @Test void verifiedPublicPackageHumanFactsTriggerTheExistingIntegrationTaskOnce() {
        var input = new PublicInput("ENF-PUBLIC-CNIPA-1", "https://www.cnipa.gov.cn/example",
                "国家知识产权局", "2021-04-01", "2026-09-29T06:52:02Z", "2".repeat(64),
                "public/file-1", "source.html", "BATCH-1", "COLLECTION_ALLOWED",
                Map.of("realPublicTask", "公开任务"), "operator");
        service.createPublic("operator", "create-public", new JobPackService.CreatePublic(PACK,
                "ORG-1", "公开任务", "REAL_BUSINESS_COLLECTION", input));
        submitAndVerify("operator", "reviewer", FactType.ACTOR_POSITION, Map.of(
                "personRef", "operator", "organizationRef", "ORG-1", "positionTitle", "执法人员",
                "appointmentRef", "APPOINT-1", "validFrom", "2026-09-01", "validTo", "2027-09-01"), true);
        submitAndVerify("approver", "reviewer", FactType.APPROVAL, Map.of(
                "decision", "APPROVED", "method", "系统内人工审阅", "reason", "同意执行",
                "targetContentRevision", "2"), true);
        submitAndVerify("operator", "reviewer", FactType.HUMAN_RECEIPT, Map.of(
                "personRef", "operator", "positionTitle", "执法人员", "text", "已核收公开任务",
                "targetContentRevision", "2"), false);
        var receipt = submitAndVerify("evaluator", "reviewer", FactType.BUSINESS_EVALUATION, Map.of(
                "completeness", "5", "traceability", "5", "operability", "4", "accuracy", "4",
                "humanEffort", "3", "basis", "逐项人工核查", "conclusion", "可进入真实集成",
                "targetContentRevision", "2"), false);
        assertThat(receipt.state()).isEqualTo(State.RUNNING);
        assertThat(store.latest(PACK).orElseThrow().trigger().taskId()).isEqualTo("NEXT-V2-INT-ENF-01");
        assertThat(store.triggered()).isEqualTo(1);
    }

    @Test void twoPeopleCanCrossSubmitAndVerifyWithoutSelfApprovalOrSelfVerification() {
        var input = new PublicInput("ENF-PUBLIC-CNIPA-1", "https://www.cnipa.gov.cn/example",
                "国家知识产权局", "2021-04-01", "2026-09-29T06:52:02Z", "2".repeat(64),
                "public/file-1", "source.html", "BATCH-1", "COLLECTION_ALLOWED",
                Map.of("realPublicTask", "公开任务"), "mechanism-operator");
        service.createPublic("mechanism-operator", "two-create", new JobPackService.CreatePublic(PACK,
                "ORG-1", "双人现场机制", "REAL_BUSINESS_COLLECTION", input));
        var position = service.submit("mechanism-operator", "two-position", PACK, FactType.ACTOR_POSITION,
                fact(Map.of("personRef", "mechanism-operator", "organizationRef", "ORG-1",
                        "positionTitle", "机制测试岗位", "appointmentRef", "MECHANISM-ONLY",
                        "validFrom", "2026-09-01", "validTo", "2027-09-01")));
        service.verify("mechanism-reviewer", "two-position-verify", PACK, FactType.ACTOR_POSITION,
                new JobPackService.VerificationInput(position.revision(), true, "仅核双人机制"));
        var approval = service.submit("mechanism-reviewer", "two-approval", PACK, FactType.APPROVAL,
                fact(Map.of("decision", "APPROVED", "method", "机制测试人工动作", "reason", "仅用于机制验证",
                        "targetContentRevision", "2")));
        service.verify("mechanism-operator", "two-approval-verify", PACK, FactType.APPROVAL,
                new JobPackService.VerificationInput(approval.revision(), true, "仅核双人机制"));
        var humanReceipt = service.submit("mechanism-operator", "two-receipt", PACK, FactType.HUMAN_RECEIPT,
                new JobPackService.FactInput(Map.of("personRef", "mechanism-operator", "positionTitle", "机制测试岗位",
                        "text", "机制测试回执，不是真人业务回执", "targetContentRevision", "2"), List.of()));
        service.verify("mechanism-reviewer", "two-receipt-verify", PACK, FactType.HUMAN_RECEIPT,
                new JobPackService.VerificationInput(humanReceipt.revision(), true, "仅核双人机制"));
        var evaluation = service.submit("mechanism-operator", "two-evaluation", PACK, FactType.BUSINESS_EVALUATION,
                new JobPackService.FactInput(Map.of("completeness", "5", "traceability", "5", "operability", "5",
                        "accuracy", "5", "humanEffort", "5", "basis", "机制测试数据",
                        "conclusion", "仅证产品流程", "targetContentRevision", "2"), List.of()));
        var result = service.verify("mechanism-reviewer", "two-evaluation-verify", PACK,
                FactType.BUSINESS_EVALUATION,
                new JobPackService.VerificationInput(evaluation.revision(), true, "仅核双人机制"));
        assertThat(result.state()).isEqualTo(State.RUNNING);
        assertThat(store.latest(PACK).orElseThrow().trigger().taskId()).isEqualTo("NEXT-V2-INT-ENF-01");
        assertThat(store.triggered()).isEqualTo(1);
    }

    @Test void functionalValidationUsesTrustedActorAndDoesNotRequirePositionFact() {
        service.create("operator", "functional-create", new JobPackService.Create(PACK, "ORG-1",
                "功能验证任务（测试）", "FUNCTIONAL_VALIDATION"));
        submitAndVerify("operator", "reviewer", FactType.TASK, Map.of(
                "externalTaskRef", "TEST-TASK-001", "externalTaskVersion", "1",
                "originalTaskSha256", "1".repeat(64), "currentProcess", "功能验证",
                "expectedOutput", "测试任务包"), true);
        submitAndVerify("operator", "reviewer", FactType.SOURCE_RIGHTS, Map.of(
                "sourceRef", "PROJECT-TEST", "sourceVersion", "1", "rightsHolder", "项目测试数据",
                "allowedUses", "FUNCTIONAL_VALIDATION", "scope", "TEST-TASK-001",
                "validFrom", "2026-09-01", "validTo", "2027-09-01"), true);
        submitAndVerify("reviewer", "operator", FactType.APPROVAL, Map.of(
                "decision", "APPROVED", "method", "功能验证人工动作", "reason", "仅验证产品链",
                "targetContentRevision", "2"), true);
        var receipt = service.submit("operator", "functional-receipt", PACK, FactType.HUMAN_RECEIPT,
                new JobPackService.FactInput(Map.of("personRef", "spoofed", "text", "测试回执",
                        "targetContentRevision", "2"), List.of()));
        assertThat(service.read("operator", PACK).facts().stream()
                .filter(fact -> fact.type() == FactType.HUMAN_RECEIPT).findFirst().orElseThrow().fields())
                .containsEntry("personRef", "operator").doesNotContainValue("spoofed");
        service.verify("reviewer", "functional-receipt-verify", PACK, FactType.HUMAN_RECEIPT,
                new JobPackService.VerificationInput(receipt.revision(), true, "功能链核验"));
        var evaluation = service.submit("operator", "functional-evaluation", PACK, FactType.BUSINESS_EVALUATION,
                new JobPackService.FactInput(Map.of("completeness", "5", "traceability", "5",
                        "operability", "5", "accuracy", "5", "humanEffort", "5", "basis", "测试链路",
                        "conclusion", "仅支持功能就绪", "targetContentRevision", "2"), List.of()));
        var result = service.verify("reviewer", "functional-evaluation-verify", PACK,
                FactType.BUSINESS_EVALUATION,
                new JobPackService.VerificationInput(evaluation.revision(), true, "功能链核验"));
        assertThat(result.state()).isEqualTo(State.RUNNING);
        assertThat(store.latest(PACK).orElseThrow().findings()).isEmpty();
        assertThat(store.latest(PACK).orElseThrow().facts()).noneMatch(fact -> fact.type() == FactType.ACTOR_POSITION);
        assertThat(store.triggered()).isEqualTo(1);
    }

    private Receipt submitAndVerify(String submitter, String verifier, FactType type,
                                    Map<String, String> fields, boolean evidenceRequired) {
        var submitted = service.submit(submitter, "submit-" + type, PACK, type,
                new JobPackService.FactInput(fields, evidenceRequired ? List.of(EVIDENCE) : List.of()));
        return service.verify(verifier, "verify-" + type, PACK, type,
                new JobPackService.VerificationInput(submitted.revision(), true, "证据、主体和有效期已逐项核验"));
    }

    private static JobPackService.FactInput fact(Map<String, String> fields) {
        return new JobPackService.FactInput(fields, List.of(EVIDENCE));
    }

    private static final class MemoryStore implements JobPackStore {
        private final Map<String, List<Snapshot>> data = new HashMap<>();
        private final Map<String, Stored> receipts = new HashMap<>();
        @Override public Optional<Snapshot> latest(String id) {
            var values = data.getOrDefault(id, List.of()); return values.isEmpty() ? Optional.empty() : Optional.of(values.getLast());
        }
        @Override public Optional<Snapshot> snapshot(String id, long revision) {
            return data.getOrDefault(id, List.of()).stream().filter(v -> v.revision() == revision).findFirst();
        }
        @Override public Receipt command(String key, String fingerprint, String actor, String operation, Supplier<Receipt> action) {
            var prior = receipts.get(key);
            if (prior != null) {
                if (!prior.fingerprint.equals(fingerprint) || !prior.actor.equals(actor) || !prior.operation.equals(operation))
                    throw new IllegalArgumentException("receipt conflict");
                return prior.receipt;
            }
            var result = action.get(); receipts.put(key, new Stored(fingerprint, actor, operation, result)); return result;
        }
        @Override public void add(Snapshot snapshot) { data.computeIfAbsent(snapshot.id(), ignored -> new ArrayList<>()).add(snapshot); }
        int triggered() { return (int) data.get(PACK).stream().filter(v -> v.trigger() != null).count(); }
        private record Stored(String fingerprint, String actor, String operation, Receipt receipt) { }
    }
}
