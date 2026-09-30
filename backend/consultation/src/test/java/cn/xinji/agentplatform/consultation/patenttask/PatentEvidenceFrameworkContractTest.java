package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentEvidenceFrameworkContractTest {
    private final PatentTaskCommandService service = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(), action -> TaskAuthorizationDecision.allow("fixture-auth"));
    private long version;

    @Test void aCitationAndSourceLocationDoNotProveProfessionalSufficiency() {
        setup();
        var evidence = new PatentEvidenceAction.Evidence("excerpt", 1, "main", "fixture-source",
                "document-v1", "paragraph-12", "claim@1", true, List.of("abstract-only"));
        apply(PatentEvidenceAction.record(evidence));
        apply(PatentEvidenceAction.set(new PatentEvidenceAction.EvidenceSet("set", 1, "main", List.of("excerpt@1"))));
        reject(PatentEvidenceAction.assess(assessment(1, PatentEvidenceAction.Status.SUFFICIENT, null)));
        reject(PatentEvidenceAction.assess(assessment(1, PatentEvidenceAction.Status.SUFFICIENT, "unverified-rule")));
        apply(PatentEvidenceAction.assess(assessment(1, PatentEvidenceAction.Status.NOT_EVALUABLE, null)));
        assertThat(service.snapshot("task").evidenceState().assessments().get("assessment@1").status())
                .isEqualTo(PatentEvidenceAction.Status.NOT_EVALUABLE);
        assertThat(service.snapshot("task").evidenceState().evidence().get("excerpt@1"))
                .extracting(PatentEvidenceAction.Evidence::sourceVersion,
                        PatentEvidenceAction.Evidence::locator).containsExactly("document-v1", "paragraph-12");
    }

    @Test void correctionInvalidatesOnlyDependentAssessmentAndPreservesHistoricalVersion() {
        setup();
        apply(PatentEvidenceAction.record(new PatentEvidenceAction.Evidence("excerpt", 1, "main",
                "fixture-source", "document-v1", "paragraph-12", "claim@1", true, List.of())));
        apply(PatentEvidenceAction.set(new PatentEvidenceAction.EvidenceSet("set", 1, "main", List.of("excerpt@1"))));
        apply(PatentEvidenceAction.assess(assessment(1, PatentEvidenceAction.Status.PENDING_EVIDENCE, null)));
        long before = version;
        assertThat(service.snapshot("task").evidenceState().usableAssessment("assessment@1",
                service.snapshot("task").correctionState())).isTrue();
        service.correct(command(TaskCommandType.CORRECT, "correct"), new TaskChange(List.of(), Map.of(), Map.of(),
                null, new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                new PatentCorrectionAction.Artifact("claim", 2, PatentCorrectionAction.Type.FEATURE,
                        "main", List.of()), "claim", 1, "user corrected feature")));
        version++;
        assertThat(service.snapshot("task").evidenceState().usableAssessment("assessment@1",
                service.snapshot("task").correctionState())).isFalse();
        assertThat(service.snapshot("task", before).evidenceState().usableAssessment("assessment@1",
                service.snapshot("task", before).correctionState())).isTrue();
        assertThat(service.snapshot("task").evidenceState().evidence()).containsKey("excerpt@1");
    }

    @Test void unavailableEvidenceAndForeignBranchCannotBeUsedInAssessment() {
        setup();
        apply(PatentEvidenceAction.record(new PatentEvidenceAction.Evidence("excerpt", 1, "main",
                "fixture-source", "document-v1", "paragraph-12", "claim@1", false, List.of("no-access"))));
        reject(PatentEvidenceAction.set(new PatentEvidenceAction.EvidenceSet("foreign", 1, "other",
                List.of("excerpt@1"))));
        apply(PatentEvidenceAction.set(new PatentEvidenceAction.EvidenceSet("set", 1, "main", List.of("excerpt@1"))));
        reject(PatentEvidenceAction.assess(assessment(1, PatentEvidenceAction.Status.NOT_EVALUABLE, null)));
    }

    private void setup() {
        service.create(command(TaskCommandType.CREATE, "create"), new TaskChange(List.of(new SubTaskChange(
                "patent", 0, SubTaskChange.Operation.ADD, true, null,
                SubTaskChange.ResponsibilitySource.RULE, "fixture:create", List.of(), null)), Map.of(), Map.of()));
        version = 1;
        service.continueTask(command(TaskCommandType.CONTINUE, "register"), new TaskChange(List.of(), Map.of(),
                Map.of(), null, new PatentCorrectionAction(PatentCorrectionAction.Kind.REGISTER,
                new PatentCorrectionAction.Artifact("claim", 1, PatentCorrectionAction.Type.FEATURE,
                        "main", List.of()), null, 0, null)));
        version++;
    }

    private PatentEvidenceAction.Assessment assessment(long assessmentVersion, PatentEvidenceAction.Status status,
                                                       String matrix) {
        return new PatentEvidenceAction.Assessment("assessment", assessmentVersion, "main", "claim@1", "set@1",
                matrix, status, "fixture evidence needs professional review", List.of("full text"),
                List.of("abstract-only"), "rule:fixture-boundary");
    }

    private void apply(PatentEvidenceAction action) {
        service.continueTask(command(TaskCommandType.CONTINUE, "evidence-" + version),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, action));
        version++;
    }

    private void reject(PatentEvidenceAction action) {
        assertThatThrownBy(() -> service.continueTask(command(TaskCommandType.CONTINUE, "reject-" + version),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, action)))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
    }

    private TaskCommand command(TaskCommandType type, String id) {
        return new TaskCommand(id, id, type, "task", type == TaskCommandType.CREATE ? 0 : version,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z"));
    }
}
