package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.Operation.*;
import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.ResponsibilitySource.RULE;
import static cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskStatus.*;
import static org.assertj.core.api.Assertions.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

class PatentTaskCoreContractTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private final FixtureInMemoryPatentTaskCommandRepository repository =
            new FixtureInMemoryPatentTaskCommandRepository();
    private final MutableRecoveryBoundary recovery = new MutableRecoveryBoundary();
    private final PatentTaskCommandService service = service(repository, recovery);

    @Test
    void productStatusSetContainsExactlyTheApprovedNineStates() {
        assertThat(Set.of(PatentTaskStatus.values())).containsExactlyInAnyOrder(
                IN_PROGRESS, WAITING_FOR_USER_INPUT, WAITING_FOR_CONFIRMATION, PARTIALLY_COMPLETED,
                COMPLETED, INSUFFICIENT_EVIDENCE, REQUIRES_HUMAN_REVIEW, TECHNICAL_FAILURE, USER_CANCELLED);
    }

    @Test
    void aggregationUsesTheApprovedFirstMatchingRule() {
        assertThat(aggregate(false, domainSub("a", COMPLETED), domainSub("b", WAITING_FOR_CONFIRMATION),
                domainFailed("c", 1)).status()).isEqualTo(PARTIALLY_COMPLETED);
        assertThat(aggregate(false, domainSub("a", COMPLETED), domainSub("b", COMPLETED)).status())
                .isEqualTo(COMPLETED);
        assertThat(aggregate(false, domainSub("a", REQUIRES_HUMAN_REVIEW),
                domainSub("b", WAITING_FOR_CONFIRMATION)).status()).isEqualTo(REQUIRES_HUMAN_REVIEW);
        assertThat(aggregate(false, domainSub("a", WAITING_FOR_CONFIRMATION),
                domainSub("b", WAITING_FOR_USER_INPUT)).status()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(aggregate(false, domainSub("a", WAITING_FOR_USER_INPUT), domainSub("b", IN_PROGRESS)).status())
                .isEqualTo(WAITING_FOR_USER_INPUT);
        assertThat(aggregate(false, domainFailed("a", 1)).status()).isEqualTo(TECHNICAL_FAILURE);
        assertThat(aggregate(false, domainSub("a", INSUFFICIENT_EVIDENCE),
                domainSub("b", INSUFFICIENT_EVIDENCE)).status()).isEqualTo(INSUFFICIENT_EVIDENCE);
        assertThat(aggregate(true, domainSub("a", COMPLETED)).status()).isEqualTo(USER_CANCELLED);
    }

    @Test
    void reasonsRemainIndependentFromTheNineProductStates() {
        PatentSubTask denied = new PatentSubTask("a", 1, true, INSUFFICIENT_EVIDENCE, false,
                List.of(new TaskReason(TaskReason.Code.NO_PERMISSION, "fixture denied", RULE, "rule:scope")),
                null, "rule:scope");
        assertThat(aggregate(false, denied).reasons()).extracting(TaskReason::code)
                .containsExactly(TaskReason.Code.NO_PERMISSION);
    }

    @Test
    void sameCommandAndActualPayloadReplaysOriginalResultWithoutCreatingAnotherVersion() {
        TaskCommand create = command(TaskCommandType.CREATE, "task-1", 0, "command-1", "key-1");
        TaskChange initial = change(add("a", true));
        TaskCommandResult first = service.create(create, initial);
        TaskCommandResult replay = service.create(create, initial);
        assertThat(first.outcome()).isEqualTo(TaskCommandResult.Outcome.APPLIED);
        assertThat(replay.outcome()).isEqualTo(TaskCommandResult.Outcome.REPLAYED);
        assertThat(replay.snapshot()).isEqualTo(first.snapshot());
        assertThat(service.snapshot("task-1").version()).isEqualTo(1);
    }

    @Test
    void sameIdentityOrIdempotencyKeyWithDifferentActualPayloadConflicts() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "command-1", "key-1"),
                change(add("a", true)));
        assertPayloadConflict(() -> service.create(
                command(TaskCommandType.CREATE, "task-1", 0, "command-1", "key-1"),
                change(add("different", true))));
        assertPayloadConflict(() -> service.create(
                command(TaskCommandType.CREATE, "task-1", 0, "command-2", "key-1"),
                change(add("a", true))));
    }

    @Test
    void identicalUntrustedFingerprintCannotHideDifferentActualPayload() {
        TaskCommand first = new TaskCommand("command", "key", "caller-chosen-same-fingerprint",
                TaskCommandType.CREATE, "task-1", 0, Map.of(), Map.of(), null, NOW);
        TaskCommand attack = new TaskCommand("command", "key", "caller-chosen-same-fingerprint",
                TaskCommandType.CREATE, "task-1", 0, Map.of(), Map.of(), null, NOW);
        service.create(first, change(add("a", true)));
        assertPayloadConflict(() -> service.create(attack, change(add("different", true))));
        assertThat(repository.findArrivals("task-1")).extracting(TaskCommandReceipt::payloadFingerprint)
                .doesNotContain("caller-chosen-same-fingerprint");
    }

    @Test
    void payloadNormalizationMakesMapAndMutationOrderIdempotent() {
        TaskCommand create = command(TaskCommandType.CREATE, "task-1", 0, "command", "key");
        TaskChange first = new TaskChange(List.of(add("b", false), add("a", true)),
                linkedVersions("z", 1L, "a", 1L), Map.of());
        TaskChange reordered = new TaskChange(List.of(add("a", true), add("b", false)),
                linkedVersions("a", 1L, "z", 1L), Map.of());
        service.create(create, first);
        assertThat(service.create(create, reordered).outcome()).isEqualTo(TaskCommandResult.Outcome.REPLAYED);
    }

    @Test
    void staleTaskObjectAndDependencyVersionsReturnStructuredConflict() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "c1", "k1"),
                new TaskChange(List.of(add("a", true)), Map.of("fact", 1L), Map.of("scope", 1L)));
        assertConflict(command(TaskCommandType.CONTINUE, "task-1", 2, "c2", "k2"),
                change(start("a", 1)), TaskCommandException.Code.VERSION_CONFLICT, TaskConflict.Type.TASK_VERSION);
        TaskCommand staleObject = new TaskCommand("c3", "k3", TaskCommandType.CONTINUE, "task-1", 1,
                Map.of("fact", 0L), Map.of("scope", 1L), null, NOW);
        assertConflict(staleObject, change(start("a", 1)), TaskCommandException.Code.OBJECT_VERSION_CONFLICT,
                TaskConflict.Type.OBJECT_VERSION);
        TaskCommand staleDependency = new TaskCommand("c4", "k4", TaskCommandType.CONTINUE, "task-1", 1,
                Map.of("fact", 1L), Map.of("scope", 0L), null, NOW);
        assertConflict(staleDependency, change(start("a", 1)),
                TaskCommandException.Code.DEPENDENCY_VERSION_CONFLICT, TaskConflict.Type.DEPENDENCY_VERSION);
    }

    @Test
    void concurrentCommandsCannotLastWriteOverwrite() throws Exception {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "create-key"),
                change(add("a", true)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<? extends Future<?>> futures = List.of("a", "b").stream().map(suffix -> executor.submit(() -> {
                await(start);
                try {
                    service.continueTask(command(TaskCommandType.CONTINUE, "task-1", 1,
                            "command-" + suffix, "key-" + suffix), change(waitConfirm("a", 1)));
                    applied.incrementAndGet();
                } catch (TaskCommandException error) {
                    if (error.code() == TaskCommandException.Code.VERSION_CONFLICT) conflicts.incrementAndGet();
                    else throw error;
                }
            })).toList();
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(applied).hasValue(1);
        assertThat(conflicts).hasValue(1);
        assertThat(service.snapshot("task-1").version()).isEqualTo(2);
    }

    @Test
    void callerCannotDeleteSubtasksChangePrimaryOrUseOldSubtaskVersion() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"),
                change(add("primary", true), add("support", false)));
        PatentTaskSnapshot after = service.continueTask(
                command(TaskCommandType.CONTINUE, "task-1", 1, "continue", "continue-key"),
                change(waitConfirm("primary", 1))).snapshot();
        assertThat(after.subTasks()).extracting(PatentSubTask::subTaskId)
                .containsExactlyInAnyOrder("primary", "support");
        assertThat(after.subTasks().stream().filter(t -> t.subTaskId().equals("primary"))
                .findFirst().orElseThrow().primary())
                .isTrue();
        assertThatThrownBy(() -> new SubTaskChange("primary", 2, START, true, null,
                RULE, "rule:attempt", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.continueTask(
                command(TaskCommandType.CONTINUE, "task-1", 2, "old", "old-key"),
                change(start("primary", 1))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.OBJECT_VERSION_CONFLICT));
    }

    @Test
    void completionCannotBypassDeterministicCompletionCheckOrUseUncontrolledReason() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"), change(add("a", true)));
        SubTaskChange denied = mutation("a", 1, COMPLETE, false, null,
                SubTaskChange.ResponsibilitySource.USER, "user:claim", List.of("fixture-completion"), null);
        assertThatThrownBy(() -> service.confirm(
                command(TaskCommandType.CONFIRM, "task-1", 1, "bad", "bad-key"), change(denied)))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
        PatentTaskSnapshot completed = service.confirm(
                command(TaskCommandType.CONFIRM, "task-1", 1, "good", "good-key"),
                change(complete("a", 1))).snapshot();
        assertThat(completed.status()).isEqualTo(COMPLETED);
        assertThatThrownBy(() -> mutation("a", 2, START, false, TaskReason.Code.NO_PERMISSION,
                RULE, "rule:tamper", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationRecordsLogicalStopAndLateCommandCannotAdvanceTaskButIsAudited() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "create-key"),
                change(add("a", true)));
        PatentTaskSnapshot cancelled = service.cancel(
                command(TaskCommandType.CANCEL, "task-1", 1, "cancel", "cancel-key")).snapshot();
        assertThat(cancelled.status()).isEqualTo(USER_CANCELLED);
        assertThat(cancelled.cancellationRecord().stopRequestReference()).isEqualTo("stop-request:cancel");
        assertThatThrownBy(() -> service.continueTask(
                command(TaskCommandType.CONTINUE, "task-1", 1, "late", "late-key"),
                change(start("a", 1))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.VERSION_CONFLICT));
        assertThat(repository.findArrivals("task-1")).anySatisfy(receipt ->
                assertThat(receipt.outcome()).isEqualTo(TaskCommandReceipt.Outcome.REJECTED_STALE_VERSION));
        assertThat(service.snapshot("task-1").version()).isEqualTo(2);
    }

    @Test
    void cancelledTaskRequiresExplicitRecoveryBoundaryAndCreatesNewVersion() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"), change(add("a", true)));
        service.cancel(command(TaskCommandType.CANCEL, "task-1", 1, "cancel", "cancel-key"));
        recovery.cancelAllowed = false;
        assertThatThrownBy(() -> service.resume(
                command(TaskCommandType.RESUME, "task-1", 2, "denied", "denied-key")))
                .isInstanceOf(TaskCommandException.class);
        recovery.cancelAllowed = true;
        PatentTaskSnapshot resumed = service.resume(
                command(TaskCommandType.RESUME, "task-1", 2, "resume", "resume-key")).snapshot();
        assertThat(resumed).extracting(PatentTaskSnapshot::version, PatentTaskSnapshot::cancelled)
                .containsExactly(3L, false);
    }

    @Test
    void historicalSnapshotsAreImmutableAndReadableAfterLaterVersionsCommit() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"), change(add("a", true)));
        service.continueTask(command(TaskCommandType.CONTINUE, "task-1", 1, "wait", "wait-key"),
                change(waitConfirm("a", 1)));
        assertThat(service.snapshot("task-1", 1)).extracting(PatentTaskSnapshot::version, PatentTaskSnapshot::status)
                .containsExactly(1L, IN_PROGRESS);
        assertThat(service.snapshot("task-1").version()).isEqualTo(2);
    }

    @Test
    void technicalFailureRecoveryValidatesDependenciesSourcesAndPreservesCompletedArtifacts() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"),
                new TaskChange(List.of(add("failed", true), add("preserved", true)),
                        Map.of("fact", 1L), Map.of("scope", 1L)));
        service.confirm(commandWithVersions(TaskCommandType.CONFIRM, "task-1", 1, "complete", "complete-key"),
                change(complete("preserved", 1)));
        RecoveryPoint point = recoveryPoint(2, "failed", Map.of("fact", 1L), Map.of("scope", 1L));
        service.continueTask(commandWithVersions(TaskCommandType.CONTINUE, "task-1", 2, "fail", "fail-key"),
                change(fail("failed", 1, point)));
        PatentTaskSnapshot resumed = service.resume(
                commandWithVersions(TaskCommandType.RESUME, "task-1", 3, "resume", "resume-key"),
                "failed").snapshot();
        assertThat(resumed.status()).isEqualTo(PARTIALLY_COMPLETED);
        assertThat(resumed.subTasks()).filteredOn(t -> t.subTaskId().equals("preserved"))
                .extracting(PatentSubTask::status).containsExactly(COMPLETED);
        assertThat(resumed.subTasks()).filteredOn(t -> t.subTaskId().equals("failed"))
                .extracting(PatentSubTask::status).containsExactly(IN_PROGRESS);
        assertThat(resumed.events().get(resumed.events().size() - 1).relatedArtifactReferences())
                .contains("artifact:committed", "action:unfinished", "execution:failed", "command:retry");
        assertThat(resumed.events().get(resumed.events().size() - 1).preservedArtifactReferences())
                .containsExactly("artifact:committed");
        assertThat(resumed.events().get(resumed.events().size() - 1).resumedActionReferences())
                .containsExactly("action:unfinished");
    }

    @Test
    void rejectedArrivalSurvivesServiceRestartAndDoesNotCreateTaskVersion() {
        service.create(command(TaskCommandType.CREATE, "task-1", 0, "create", "key"), change(add("a", true)));
        assertThatThrownBy(() -> service.continueTask(
                command(TaskCommandType.CONTINUE, "task-1", 9, "late", "late-key"),
                change(start("a", 1)))).isInstanceOf(TaskCommandException.class);
        PatentTaskCommandService restarted = service(repository, recovery);
        assertThat(restarted.snapshot("task-1").version()).isEqualTo(1);
        assertThat(repository.findArrivals("task-1")).extracting(TaskCommandReceipt::outcome)
                .contains(TaskCommandReceipt.Outcome.REJECTED_STALE_VERSION);
    }

    private static PatentTaskCommandService service(FixtureInMemoryPatentTaskCommandRepository repository,
                                                     RecoveryBoundaryCheck recovery) {
        return new PatentTaskCommandService(repository,
                action -> TaskAuthorizationDecision.allow("fixture-auth"),
                (snapshot, subTask, requested) -> requested.completionConditionReferences()
                        .contains("fixture-completion"), recovery);
    }

    private static TaskAggregation aggregate(boolean cancelled, PatentSubTask... tasks) {
        return PatentTaskStateAggregator.aggregate(List.of(tasks), cancelled);
    }

    private static PatentSubTask domainSub(String id, PatentTaskStatus status) {
        return new PatentSubTask(id, 1, true, status, status == IN_PROGRESS,
                List.of(), null, "fixture:domain");
    }

    private static PatentSubTask domainFailed(String id, long version) {
        return new PatentSubTask(id, version, true, TECHNICAL_FAILURE, false,
                List.of(new TaskReason(TaskReason.Code.TECHNICAL_FAILURE, "fixture", RULE, "fixture:failure")),
                recoveryPoint(version, id, Map.of(), Map.of()), "fixture:failure");
    }

    private static TaskChange change(SubTaskChange... changes) {
        return new TaskChange(List.of(changes), Map.of(), Map.of());
    }

    private static SubTaskChange add(String id, boolean primary) {
        return mutation(id, 0, ADD, primary, null, RULE, "rule:add", List.of(), null);
    }

    private static SubTaskChange start(String id, long version) {
        return mutation(id, version, START, false, null, RULE, "rule:start", List.of(), null);
    }

    private static SubTaskChange waitConfirm(String id, long version) {
        return mutation(id, version, WAIT_FOR_CONFIRMATION, false, null, RULE, "rule:confirm", List.of(), null);
    }

    private static SubTaskChange complete(String id, long version) {
        return mutation(id, version, COMPLETE, false, null, RULE, "rule:completion",
                List.of("fixture-completion"), null);
    }

    private static SubTaskChange fail(String id, long version, RecoveryPoint point) {
        return mutation(id, version, FAIL, false, TaskReason.Code.TECHNICAL_FAILURE,
                RULE, "rule:failure", List.of(), point);
    }

    private static SubTaskChange mutation(String id, long version, SubTaskChange.Operation operation,
                                          boolean primary, TaskReason.Code reason,
                                          SubTaskChange.ResponsibilitySource source, String sourceReference,
                                          List<String> completionReferences, RecoveryPoint point) {
        if (operation != MARK_INSUFFICIENT_EVIDENCE && operation != FAIL && reason != null) {
            throw new IllegalArgumentException("reason not accepted by fixture builder");
        }
        return new SubTaskChange(id, version, operation, primary, reason, source, sourceReference,
                completionReferences, point);
    }

    private static RecoveryPoint recoveryPoint(long version, String subTaskId,
                                                Map<String, Long> contexts, Map<String, Long> dependencies) {
        return new RecoveryPoint(version, subTaskId, "J06", contexts, List.of("artifact:committed"),
                List.of("action:unfinished"), "execution:failed", "command:retry", dependencies,
                List.of("source:fixture"));
    }

    private static TaskCommand command(TaskCommandType type, String taskId, long expectedVersion,
                                       String identity, String key) {
        return new TaskCommand(identity, key, type, taskId, expectedVersion, Map.of(), Map.of(),
                "untrusted-browser-user", NOW);
    }

    private static TaskCommand commandWithVersions(TaskCommandType type, String taskId, long expectedVersion,
                                                   String identity, String key) {
        return new TaskCommand(identity, key, type, taskId, expectedVersion,
                Map.of("fact", 1L), Map.of("scope", 1L), null, NOW);
    }

    private static Map<String, Long> linkedVersions(String firstKey, Long firstValue,
                                                     String secondKey, Long secondValue) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }

    private void assertConflict(TaskCommand command, TaskChange change, TaskCommandException.Code code,
                                TaskConflict.Type conflictType) {
        assertThatThrownBy(() -> service.continueTask(command, change))
                .isInstanceOfSatisfying(TaskCommandException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.conflict()).isNotNull();
                    assertThat(error.conflict().type()).isEqualTo(conflictType);
                    assertThat(error.conflict().suggestedRefreshTarget()).contains("task:task-1");
                });
    }

    private static void assertPayloadConflict(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(TaskCommandException.class, error -> {
            assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT);
            assertThat(error.conflict()).isNotNull();
        });
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static final class MutableRecoveryBoundary implements RecoveryBoundaryCheck {
        private boolean failureAllowed = true;
        private boolean cancelAllowed = true;
        @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot current) {
            return failureAllowed ? Validation.allow() : Validation.deny("fixture source unavailable");
        }
        @Override public Validation validateCancelledRecovery(PatentTaskSnapshot current) {
            return cancelAllowed ? Validation.allow() : Validation.deny("fixture cancellation recovery denied");
        }
    }
}
