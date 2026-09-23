package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.*;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;
import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;
import cn.xinji.agentplatform.consultation.patenttask.domain.TaskReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

class PatentTaskAuthorizationBoundaryContractTest {
    private static final ProtectedTaskAction ACTION =
            new ProtectedTaskAction("CONTINUE", "fixture-task", "forged-browser-user", "forged-scope");

    @Test
    void missingIdentityInvalidContextUnresolvedResourceAndDefaultPolicyAllDeny() {
        assertThat(PatentTaskAuthorizationGuard.defaultDeny(Optional::empty).revalidate(ACTION).denialReason())
                .isEqualTo("IDENTITY_MISSING");
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("fixture-task", "scope-b");
        CurrentAuthorizationContext invalid = context("server-user", "scope-b", false);
        PatentTaskAuthorizationGuard invalidGuard = new PatentTaskAuthorizationGuard(
                () -> Optional.of(invalid), resolver, (context, action, requirement) -> true);
        assertThat(invalidGuard.revalidate(ACTION).denialReason()).isEqualTo("AUTHORIZATION_CONTEXT_INVALID");
        CurrentAuthorizationContext valid = context("server-user", "scope-b", true);
        PatentTaskAuthorizationGuard unresolved = new PatentTaskAuthorizationGuard(
                () -> Optional.of(valid), action -> Optional.empty(), (c, a, r) -> true);
        assertThat(unresolved.revalidate(ACTION).denialReason()).isEqualTo("RESOURCE_AUTHORIZATION_UNRESOLVED");
        assertThat(PatentTaskAuthorizationGuard.defaultDeny(() -> Optional.of(valid))
                .revalidate(ACTION).denialReason()).isEqualTo("DEFAULT_DENY");
    }

    @Test
    void identityWithoutPermissionScopeMismatchAndOutOfBoundaryActionDeny() {
        CurrentAuthorizationContext valid = context("server-user", "scope-a", true);
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("fixture-task", "scope-b");
        PatentTaskAuthorizationGuard noPermission = new PatentTaskAuthorizationGuard(
                () -> Optional.of(valid), resolver, (context, action, requirement) -> false);
        assertThat(noPermission.revalidate(ACTION).allowed()).isFalse();
        PatentTaskAuthorizationGuard scopeGuard = guard(() -> Optional.of(valid), resolver);
        assertThat(scopeGuard.revalidate(ACTION).denialReason()).isEqualTo("SCOPE_OR_ACTION_DENIED");
        PatentTaskAuthorizationGuard actionGuard = new PatentTaskAuthorizationGuard(
                () -> Optional.of(context("server-user", "scope-b", true)), resolver,
                (context, action, requirement) -> action.action().equals("READ_SNAPSHOT"));
        assertThat(actionGuard.revalidate(ACTION).denialReason()).isEqualTo("SCOPE_OR_ACTION_DENIED");
    }

    @Test
    void explicitlyMarkedFixtureIdentityMayAccessServerResolvedFixtureScope() {
        CurrentAuthorizationContext fixture = new CurrentAuthorizationContext("fixture-context",
                CurrentIdentity.fixture("fixture-user"), new DataScope("scope-b"), true);
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-b");
        PatentTaskAuthorizationGuard guard = new PatentTaskAuthorizationGuard(() -> Optional.of(fixture), resolver,
                (context, action, requirement) -> context.identity().testIdentity()
                        && context.dataScope().reference().equals(requirement.requiredScopeReference()));
        assertThat(guard.revalidate(new ProtectedTaskAction("CREATE", "task", null, null)))
                .isEqualTo(TaskAuthorizationDecision.allow("fixture-context"));
    }

    @Test
    void untrustedClaimedIdentityCannotReplaceServerIdentity() {
        CurrentAuthorizationContext server = context("server-user", "scope-b", true);
        AtomicReference<String> evaluatedIdentity = new AtomicReference<>();
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-b");
        PatentTaskAuthorizationGuard guard = new PatentTaskAuthorizationGuard(() -> Optional.of(server), resolver,
                (context, action, requirement) -> {
                    evaluatedIdentity.set(context.identity().subjectReference());
                    return context.identity().subjectReference().equals("server-user");
                });
        TaskAuthorizationDecision result = guard.revalidate(
                new ProtectedTaskAction("CONTINUE", "task", "privileged-browser-user", null));
        assertThat(result.allowed()).isTrue();
        assertThat(evaluatedIdentity).hasValue("server-user");
    }

    @Test
    void callerScopeClaimCannotChangeServerResourceRequirement() {
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-b");
        AtomicReference<CurrentAuthorizationContext> current = new AtomicReference<>(
                context("server-user", "scope-b", true));
        PatentTaskCommandService service = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(), guard(() -> Optional.of(current.get()), resolver));
        service.create(command(TaskCommandType.CREATE, 0, "create", "key"), createChange());
        current.set(context("server-user", "scope-a", true));
        assertThatThrownBy(() -> service.snapshot("task", "scope-a"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.ACCESS_DENIED));
    }

    @Test
    void everyProtectedCommandRevalidatesAndServerScopeChangeCannotBeBypassed() {
        AtomicReference<CurrentAuthorizationContext> current = new AtomicReference<>(
                context("server-user", "scope-a", true));
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-a");
        AtomicInteger checks = new AtomicInteger();
        PatentTaskAuthorizationGuard guard = new PatentTaskAuthorizationGuard(
                () -> Optional.ofNullable(current.get()), resolver, (context, action, requirement) -> {
                    checks.incrementAndGet();
                    return context.dataScope().reference().equals(requirement.requiredScopeReference());
                });
        PatentTaskCommandService service = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(), guard);
        service.create(command(TaskCommandType.CREATE, 0, "create", "create-key"), createChange());
        resolver.bind("task", "scope-b", 2);
        assertThatThrownBy(() -> service.continueTask(
                command(TaskCommandType.CONTINUE, 1, "continue", "continue-key"), startChange()))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.ACCESS_DENIED));
        current.set(context("server-user", "scope-b", true));
        assertThat(service.continueTask(
                command(TaskCommandType.CONTINUE, 1, "allowed", "allowed-key"), startChange()).snapshot().version())
                .isEqualTo(2);
        assertThat(checks).hasValue(3);
    }

    @Test
    void taskCoreConsumesOnlyAuthorizationDecisionAndCannotInferPermissionFacts() {
        PatentTaskCommandService denied = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(),
                action -> TaskAuthorizationDecision.deny("fixture-deny"));
        assertThatThrownBy(() -> denied.create(
                command(TaskCommandType.CREATE, 0, "create", "key"), createChange()))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.ACCESS_DENIED));
    }

    @Test
    void reverseAuthorizationChainUsesCurrentServerIdentityAndResourceFactsOnEveryAccess() {
        AtomicReference<CurrentAuthorizationContext> current = new AtomicReference<>();
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-a");
        PatentTaskCommandService service = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(), guard(() -> Optional.ofNullable(current.get()), resolver));
        assertThatThrownBy(() -> service.create(
                command(TaskCommandType.CREATE, 0, "create", "key"), createChange()))
                .isInstanceOf(TaskCommandException.class);
        current.set(context("fixture-user", "scope-a", true));
        service.create(command(TaskCommandType.CREATE, 0, "create-2", "key-2"), createChange());
        resolver.bind("task", "scope-b", 2);
        assertThatThrownBy(() -> service.snapshot("task", "scope-a")).isInstanceOf(TaskCommandException.class);
        current.set(context("fixture-user", "scope-b", true));
        assertThat(service.snapshot("task", "forged-scope-a").taskId()).isEqualTo("task");
    }

    @Test
    void failureRecoveryRevalidatesChangedResourceScopeBeforeRecoveryBoundary() {
        AtomicReference<CurrentAuthorizationContext> current = new AtomicReference<>(
                context("fixture-user", "scope-a", true));
        FixtureProtectedResourceAuthorizationResolver resolver = resolver("task", "scope-a");
        RecoveryBoundaryCheck allowRecovery = new RecoveryBoundaryCheck() {
            @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot snapshot) {
                return Validation.allow();
            }
            @Override public Validation validateCancelledRecovery(PatentTaskSnapshot snapshot) {
                return Validation.allow();
            }
        };
        PatentTaskCommandService service = new PatentTaskCommandService(
                new FixtureInMemoryPatentTaskCommandRepository(), guard(() -> Optional.of(current.get()), resolver),
                (snapshot, subTask, requested) -> false, allowRecovery);
        service.create(command(TaskCommandType.CREATE, 0, "create", "key"), createChange());
        RecoveryPoint point = new RecoveryPoint(1, "sub", "J06", Map.of(), List.of("artifact:done"),
                List.of("action:retry"), "execution:failed", "command:retry", Map.of(), List.of("source:fixture"));
        TaskChange failure = new TaskChange(List.of(new SubTaskChange("sub", 1,
                SubTaskChange.Operation.FAIL, false, TaskReason.Code.TECHNICAL_FAILURE,
                SubTaskChange.ResponsibilitySource.RULE, "rule:failure", List.of(), point)), Map.of(), Map.of());
        service.continueTask(command(TaskCommandType.CONTINUE, 1, "fail", "fail-key"), failure);
        resolver.bind("task", "scope-b", 2);
        assertThatThrownBy(() -> service.resume(
                command(TaskCommandType.RESUME, 2, "resume", "resume-key"), "sub"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.ACCESS_DENIED));
        current.set(context("fixture-user", "scope-b", true));
        assertThat(service.resume(command(TaskCommandType.RESUME, 2, "resume-2", "resume-key-2"), "sub")
                .snapshot().version()).isEqualTo(3);
    }

    private static PatentTaskAuthorizationGuard guard(CurrentAuthorizationContextProvider provider,
                                                       FixtureProtectedResourceAuthorizationResolver resolver) {
        return new PatentTaskAuthorizationGuard(provider, resolver,
                (context, action, requirement) -> context.dataScope().reference()
                        .equals(requirement.requiredScopeReference()));
    }

    private static FixtureProtectedResourceAuthorizationResolver resolver(String taskId, String scope) {
        FixtureProtectedResourceAuthorizationResolver resolver = new FixtureProtectedResourceAuthorizationResolver();
        resolver.bind(taskId, scope, 1);
        return resolver;
    }

    private static CurrentAuthorizationContext context(String identity, String scope, boolean valid) {
        return new CurrentAuthorizationContext("fixture-context-" + identity + "-" + scope,
                CurrentIdentity.fixture(identity), new DataScope(scope), valid);
    }

    private static TaskChange createChange() {
        return new TaskChange(List.of(new SubTaskChange("sub", 0, SubTaskChange.Operation.ADD, true,
                null, SubTaskChange.ResponsibilitySource.RULE, "rule:add", List.of(), null)), Map.of(), Map.of());
    }

    private static TaskChange startChange() {
        return new TaskChange(List.of(new SubTaskChange("sub", 1, SubTaskChange.Operation.START, false,
                null, SubTaskChange.ResponsibilitySource.RULE, "rule:start", List.of(), null)), Map.of(), Map.of());
    }

    private static TaskCommand command(TaskCommandType type, long expectedVersion, String identity, String key) {
        return new TaskCommand(identity, key, type, "task", expectedVersion, Map.of(), Map.of(),
                "forged-browser-user", Instant.parse("2026-09-23T00:00:00Z"));
    }
}
