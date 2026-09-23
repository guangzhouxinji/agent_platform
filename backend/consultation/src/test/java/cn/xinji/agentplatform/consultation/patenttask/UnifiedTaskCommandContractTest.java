package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentSubTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnifiedTaskCommandContractTest {
    @Test
    void everySupportedMutationUsesTheSameExpectedVersionRule() {
        for (TaskCommandType type : List.of(TaskCommandType.CONTINUE, TaskCommandType.SUPPLEMENT,
                TaskCommandType.CONFIRM, TaskCommandType.CORRECT, TaskCommandType.SELECT)) {
            PatentTaskCommandService service = new PatentTaskCommandService(
                    new FixtureInMemoryPatentTaskCommandRepository(),
                    action -> TaskAuthorizationDecision.allow("fixture-auth"));
            service.create(command(TaskCommandType.CREATE, type + "-task", 0, "create"), createChange());

            TaskCommandResult result = apply(service, type,
                    command(type, type + "-task", 1, "change"), startChange());
            assertThat(result.snapshot().version()).isEqualTo(2);

            assertThatThrownBy(() -> apply(service, type,
                    command(type, type + "-task", 1, "late"), startChange()))
                    .isInstanceOfSatisfying(TaskCommandException.class,
                            error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.VERSION_CONFLICT));
        }
    }

    private static TaskCommandResult apply(PatentTaskCommandService service, TaskCommandType type,
                                           TaskCommand command, TaskChange change) {
        return switch (type) {
            case CONTINUE -> service.continueTask(command, change);
            case SUPPLEMENT -> service.supplement(command, change);
            case CONFIRM -> service.confirm(command, change);
            case CORRECT -> service.correct(command, change);
            case SELECT -> service.select(command, change);
            default -> throw new IllegalArgumentException("unsupported mutation in fixture test");
        };
    }

    private static TaskChange createChange() {
        return new TaskChange(List.of(new SubTaskChange("sub", 0, SubTaskChange.Operation.ADD,
                true, null, SubTaskChange.ResponsibilitySource.RULE, "rule:add", List.of(), null)),
                Map.of(), Map.of());
    }

    private static TaskChange startChange() {
        return new TaskChange(List.of(new SubTaskChange("sub", 1, SubTaskChange.Operation.START,
                false, null, SubTaskChange.ResponsibilitySource.RULE, "rule:start", List.of(), null)),
                Map.of(), Map.of());
    }

    private static TaskCommand command(TaskCommandType type, String taskId, long expectedVersion, String suffix) {
        return new TaskCommand(taskId + "-" + suffix, taskId + "-key-" + suffix,
                type, taskId, expectedVersion, Map.of(), Map.of(), null,
                Instant.parse("2026-09-22T00:00:00Z"));
    }
}
