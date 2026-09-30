package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentTaskReverificationRegressionTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private final PatentTaskCommandService service = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(),
            action -> TaskAuthorizationDecision.allow("fixture-auth"),
            (snapshot, subTask, change) -> false,
            new RecoveryBoundaryCheck() {
                @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot current) {
                    return Validation.allow();
                }
                @Override public Validation validateCancelledRecovery(PatentTaskSnapshot current) {
                    return Validation.allow();
                }
            });

    @Test
    void objectAndDependencyVersionSectionsCannotShareOneFingerprint() {
        TaskCommand create = command(TaskCommandType.CREATE, 0, "same");
        service.create(create, new TaskChange(List.of(add("a")), Map.of("version", 1L), Map.of()));
        assertThatThrownBy(() -> service.create(create,
                new TaskChange(List.of(add("a")), Map.of(), Map.of("version", 1L))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT));
    }

    @Test
    void recoveryEventReferencesOnlyTheSelectedFailedBranch() {
        service.create(command(TaskCommandType.CREATE, 0, "create"),
                change(add("a"), add("b")));
        RecoveryPoint first = point("a");
        RecoveryPoint second = point("b");
        service.continueTask(command(TaskCommandType.CONTINUE, 1, "fail"),
                change(fail("a", first), fail("b", second)));
        PatentTaskSnapshot resumed = service.resume(command(TaskCommandType.RESUME, 2, "resume"), "a")
                .snapshot();
        TaskEvent event = resumed.events().get(resumed.events().size() - 1);
        assertThat(event.resumedActionReferences()).containsExactly("action:a");
        assertThat(event.preservedArtifactReferences()).containsExactly("artifact:a");
        assertThat(event.relatedArtifactReferences()).doesNotContain("action:b", "artifact:b");
    }

    @Test
    void cancelledTaskRecoveryDoesNotClaimToRestartFailedActions() {
        service.create(command(TaskCommandType.CREATE, 0, "create"), change(add("a")));
        service.continueTask(command(TaskCommandType.CONTINUE, 1, "fail"),
                change(fail("a", point("a"))));
        service.cancel(command(TaskCommandType.CANCEL, 2, "cancel"));
        PatentTaskSnapshot resumed = service.resume(command(TaskCommandType.RESUME, 3, "resume"))
                .snapshot();
        TaskEvent event = resumed.events().get(resumed.events().size() - 1);
        assertThat(event.resumedActionReferences()).isEmpty();
        assertThat(event.preservedArtifactReferences()).isEmpty();
        assertThat(resumed.subTasks()).extracting(PatentSubTask::status)
                .containsExactly(PatentTaskStatus.TECHNICAL_FAILURE);
    }

    @Test
    void activeSubTaskCannotBeResumedAndRejectedArrivalIsCompleted() {
        FixtureInMemoryPatentTaskCommandRepository repository = new FixtureInMemoryPatentTaskCommandRepository();
        PatentTaskCommandService local = new PatentTaskCommandService(repository,
                action -> TaskAuthorizationDecision.allow("fixture-auth"));
        local.create(command(TaskCommandType.CREATE, 0, "create"), change(add("a")));

        assertThatThrownBy(() -> local.resume(command(TaskCommandType.RESUME, 1, "resume"), "a"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
        assertThat(local.snapshot("task-1").version()).isEqualTo(1);
        assertThat(repository.findArrivals("task-1"))
                .extracting(TaskCommandReceipt::outcome)
                .containsExactlyInAnyOrder(TaskCommandReceipt.Outcome.APPLIED,
                        TaskCommandReceipt.Outcome.REJECTED_INVALID);
    }

    private static RecoveryPoint point(String id) {
        return new RecoveryPoint(1, id, "J06", Map.of(), List.of("artifact:" + id),
                List.of("action:" + id), "execution:" + id, "retry:" + id,
                Map.of(), List.of("source:" + id));
    }

    private static SubTaskChange add(String id) {
        return new SubTaskChange(id, 0, SubTaskChange.Operation.ADD, true, null,
                SubTaskChange.ResponsibilitySource.RULE, "rule:add", List.of(), null);
    }

    private static SubTaskChange fail(String id, RecoveryPoint point) {
        return new SubTaskChange(id, 1, SubTaskChange.Operation.FAIL, false,
                TaskReason.Code.TECHNICAL_FAILURE, SubTaskChange.ResponsibilitySource.RULE,
                "rule:failure", List.of(), point);
    }

    private static TaskChange change(SubTaskChange... changes) {
        return new TaskChange(List.of(changes), Map.of(), Map.of());
    }

    private static TaskCommand command(TaskCommandType type, long version, String id) {
        return new TaskCommand(id, id + "-key", type, "task-1", version, Map.of(), Map.of(), null, NOW);
    }
}
