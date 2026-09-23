package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.Operation.*;
import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.ResponsibilitySource.RULE;
import static cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskStatus.TECHNICAL_FAILURE;
import static org.assertj.core.api.Assertions.*;

class PatentTaskRecoveryContractTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private final FixtureInMemoryPatentTaskCommandRepository repository =
            new FixtureInMemoryPatentTaskCommandRepository();
    private final MutableRecoveryBoundary recovery = new MutableRecoveryBoundary();
    private final PatentTaskCommandService service = new PatentTaskCommandService(repository,
            action -> TaskAuthorizationDecision.allow("fixture-auth"), (snapshot, subTask, change) -> false, recovery);

    @Test
    void recoveryRejectsChangedDependencyAndUnavailableSourceWithoutRepeatingCommittedActions() {
        service.create(command(TaskCommandType.CREATE, 0, "create", "key"),
                new TaskChange(List.of(add()), Map.of("fact", 1L), Map.of("scope", 1L)));
        RecoveryPoint point = recoveryPoint(1, Map.of("fact", 1L), Map.of("scope", 1L));
        service.continueTask(commandWithVersions(TaskCommandType.CONTINUE, 1, "fail", "fail-key"),
                change(fail(1, point)));
        recovery.failureAllowed = false;
        assertThatThrownBy(() -> service.resume(
                commandWithVersions(TaskCommandType.RESUME, 2, "source", "source-key"), "a"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
        recovery.failureAllowed = true;
        TaskCommand wrongDependency = new TaskCommand("dependency", "dependency-key", TaskCommandType.RESUME,
                "task-1", 2, Map.of("fact", 1L), Map.of("scope", 2L), null, NOW);
        assertThatThrownBy(() -> service.resume(wrongDependency, "a"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.DEPENDENCY_VERSION_CONFLICT));
        assertThat(service.snapshot("task-1").version()).isEqualTo(2);
    }

    @Test
    void fullReverseChainKeepsVersionsAndRecoveryFactsDeterministic() {
        service.create(command(TaskCommandType.CREATE, 0, "create", "k1"), change(add()));
        service.continueTask(command(TaskCommandType.CONTINUE, 1, "wait", "k2"),
                change(mutation(1, WAIT_FOR_CONFIRMATION, null)));
        RecoveryPoint point = recoveryPoint(2, Map.of(), Map.of());
        service.continueTask(command(TaskCommandType.CONTINUE, 2, "fail", "k3"), change(fail(2, point)));
        service.resume(command(TaskCommandType.RESUME, 3, "recover", "k4"), "a");
        service.cancel(command(TaskCommandType.CANCEL, 4, "cancel", "k5"));
        PatentTaskSnapshot resumed = service.resume(command(TaskCommandType.RESUME, 5, "resume", "k6")).snapshot();
        assertThat(resumed.version()).isEqualTo(6);
        assertThat(service.snapshot("task-1", 3).status()).isEqualTo(TECHNICAL_FAILURE);
        assertThat(repository.findArrivals("task-1")).hasSize(6);
    }

    private static SubTaskChange add() {
        return new SubTaskChange("a", 0, ADD, true, null, RULE, "rule:add", List.of(), null);
    }

    private static SubTaskChange fail(long version, RecoveryPoint point) {
        return new SubTaskChange("a", version, FAIL, false, TaskReason.Code.TECHNICAL_FAILURE,
                RULE, "rule:failure", List.of(), point);
    }

    private static SubTaskChange mutation(long version, SubTaskChange.Operation operation, RecoveryPoint point) {
        return new SubTaskChange("a", version, operation, false, null, RULE, "rule:transition", List.of(), point);
    }

    private static TaskChange change(SubTaskChange... changes) {
        return new TaskChange(List.of(changes), Map.of(), Map.of());
    }

    private static RecoveryPoint recoveryPoint(long version, Map<String, Long> contexts,
                                                Map<String, Long> dependencies) {
        return new RecoveryPoint(version, "a", "J06", contexts, List.of("artifact:committed"),
                List.of("action:unfinished"), "execution:failed", "command:retry", dependencies,
                List.of("source:fixture"));
    }

    private static TaskCommand command(TaskCommandType type, long version, String identity, String key) {
        return new TaskCommand(identity, key, type, "task-1", version, Map.of(), Map.of(), null, NOW);
    }

    private static TaskCommand commandWithVersions(TaskCommandType type, long version,
                                                   String identity, String key) {
        return new TaskCommand(identity, key, type, "task-1", version,
                Map.of("fact", 1L), Map.of("scope", 1L), null, NOW);
    }

    private static final class MutableRecoveryBoundary implements RecoveryBoundaryCheck {
        private boolean failureAllowed = true;
        @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot current) {
            return failureAllowed ? Validation.allow() : Validation.deny("fixture source unavailable");
        }
        @Override public Validation validateCancelledRecovery(PatentTaskSnapshot current) {
            return Validation.allow();
        }
    }
}
