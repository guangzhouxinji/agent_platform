package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentHumanContractTest {
    private final FixtureInMemoryPatentTaskCommandRepository repository = new FixtureInMemoryPatentTaskCommandRepository();
    private long version;

    @Test void preparedPackageIsUnavailableAndCannotClaimAcceptanceWithoutAuthority() {
        PatentTaskCommandService service = defaultService();
        create(service);
        var packet = packet();
        service.prepareHandoff(command(TaskCommandType.HUMAN_HANDOFF, "prepare"),
                change(PatentHumanAction.prepare(packet)));
        version++;
        assertThat(service.snapshot("task").humanState().prepared().get("handoff@1").availability())
                .isEqualTo("UNAVAILABLE");
        assertThat(service.snapshot("task").humanState().receipts()).isEmpty();
        assertThatThrownBy(() -> service.receiveHumanResult(command(TaskCommandType.HUMAN_RETURN, "receipt"),
                change(PatentHumanAction.receive(receipt("authority-1", "result")))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
    }

    @Test void fixtureAuthorityRequiresMatchingScopeAndMakesReplayIdempotent() {
        PatentTaskCommandService service = fixtureAuthorityService();
        create(service);
        service.prepareHandoff(command(TaskCommandType.HUMAN_HANDOFF, "prepare"),
                change(PatentHumanAction.prepare(packet())));
        version++;
        var wrongScope = new PatentHumanAction.Receipt("authority-1", "handoff@1", "reviewer:fixture",
                "other-scope", "result", "authority:fixture");
        reject(service, wrongScope, "wrong-scope");
        var command = command(TaskCommandType.HUMAN_RETURN, "receipt");
        var original = change(PatentHumanAction.receive(receipt("authority-1", "result")));
        service.receiveHumanResult(command, original);
        version++;
        assertThat(service.receiveHumanResult(command, original).outcome())
                .isEqualTo(TaskCommandResult.Outcome.REPLAYED);
        assertThatThrownBy(() -> service.receiveHumanResult(command,
                change(PatentHumanAction.receive(receipt("authority-1", "different")))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT));
        reject(service, receipt("authority-1", "different"), "conflict");
        assertThat(service.snapshot("task").humanState().receipts()).hasSize(1);
    }

    private PatentTaskCommandService defaultService() {
        return new PatentTaskCommandService(repository, action -> TaskAuthorizationDecision.allow("fixture-auth"));
    }

    private PatentTaskCommandService fixtureAuthorityService() {
        return new PatentTaskCommandService(repository, action -> TaskAuthorizationDecision.allow("fixture-auth"),
                (current, subTask, change) -> false, new RecoveryBoundaryCheck() {
                    @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot current) {
                        return Validation.deny("not used");
                    }
                    @Override public Validation validateCancelledRecovery(PatentTaskSnapshot current) {
                        return Validation.deny("not used");
                    }
                }, receipt -> receipt.authorityEvidenceReference().equals("authority:fixture"));
    }

    private void create(PatentTaskCommandService service) {
        service.create(new TaskCommand("create", "create", TaskCommandType.CREATE, "task", 0, Map.of(), Map.of(),
                null, Instant.parse("2026-09-23T00:00:00Z")), new TaskChange(List.of(new SubTaskChange("patent",
                0, SubTaskChange.Operation.ADD, true, null, SubTaskChange.ResponsibilitySource.RULE,
                "fixture:create", List.of(), null)), Map.of(), Map.of()));
        version = 1;
    }

    private PatentHumanAction.Handoff packet() {
        return new PatentHumanAction.Handoff("handoff", 1, "main", "patent", version,
                "goal:technical-reference", List.of("feature@1"), List.of("batch@1"),
                List.of("excerpt@1"), List.of("comparison@1"), List.of("full-text-unavailable"),
                "fixture-scope", "review evidence gap", "UNAVAILABLE");
    }

    private PatentHumanAction.Receipt receipt(String id, String result) {
        return new PatentHumanAction.Receipt(id, "handoff@1", "reviewer:fixture", "fixture-scope",
                result, "authority:fixture");
    }

    private void reject(PatentTaskCommandService service, PatentHumanAction.Receipt receipt, String id) {
        assertThatThrownBy(() -> service.receiveHumanResult(command(TaskCommandType.HUMAN_RETURN, id),
                change(PatentHumanAction.receive(receipt))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
    }

    private TaskChange change(PatentHumanAction action) {
        return new TaskChange(List.of(), Map.of(), Map.of(), null, null, null, null, null, action);
    }

    private TaskCommand command(TaskCommandType type, String id) {
        return new TaskCommand(id, id, type, "task", version, Map.of(), Map.of(), null,
                Instant.parse("2026-09-23T00:00:00Z"));
    }
}
