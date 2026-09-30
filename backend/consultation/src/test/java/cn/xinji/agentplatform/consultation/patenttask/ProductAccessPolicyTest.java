package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProductAccessPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
    private static final Instant END = NOW.plusSeconds(60);
    private static final Actor USER = new Actor("issuer", "user-1", Kind.HUMAN, "org-1");
    private static final Actor SERVICE = new Actor("issuer", "agent-1", Kind.SERVICE, "org-1");
    private static final Resource TASK = new Resource("consultation", "task", "task-1", "org-1", 2);
    private static final Source SOURCE = new Source("source-1", "document-v2");
    private static final CurrentState CURRENT = new CurrentState(5, 7, true);

    @Test
    void exactHumanGrantAndSourceUseAllowOnlyCurrentResourceAndPurpose() {
        Request request = new Request(USER, TASK, Action.DISPLAY, Purpose.SOURCE_DISPLAY, SOURCE, NOW);
        Grant grant = grant(USER, Action.DISPLAY, Purpose.SOURCE_DISPLAY);
        SourceUse use = new SourceUse(SOURCE, Purpose.SOURCE_DISPLAY, END, 7);
        Evidence evidence = new Evidence(CURRENT, List.of(grant), null, List.of(use));
        assertThat(ProductAccessPolicy.permits(request, evidence)).isTrue();
        assertThat(ProductAccessPolicy.permits(new Request(USER,
                new Resource("consultation", "task", "task-2", "org-1", 2),
                Action.DISPLAY, Purpose.SOURCE_DISPLAY, SOURCE, NOW), evidence)).isFalse();
        assertThat(ProductAccessPolicy.permits(new Request(USER,
                new Resource("consultation", "task", "task-1", "org-2", 2),
                Action.DISPLAY, Purpose.SOURCE_DISPLAY, SOURCE, NOW), evidence)).isFalse();
        assertThat(ProductAccessPolicy.permits(new Request(USER, TASK, Action.DISPLAY,
                Purpose.MODEL_INPUT, SOURCE, NOW), evidence)).isFalse();
        assertThat(ProductAccessPolicy.permits(new Request(USER, TASK, Action.DISPLAY,
                Purpose.SOURCE_DISPLAY, SOURCE, END), evidence)).isFalse();
    }

    @Test
    void revokedOrUnavailableCurrentFactsAlwaysDeny() {
        Request request = new Request(USER, TASK, Action.QUERY, Purpose.SOURCE_QUERY, SOURCE, NOW);
        Grant grant = grant(USER, Action.QUERY, Purpose.SOURCE_QUERY);
        SourceUse use = new SourceUse(SOURCE, Purpose.SOURCE_QUERY, END, 7);
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(grant), null, List.of(use)))).isTrue();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(new CurrentState(6, 7, true),
                List.of(grant), null, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(new CurrentState(5, 8, true),
                List.of(grant), null, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(new CurrentState(5, 7, false),
                List.of(grant), null, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(grant), null, List.of()))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, null)).isFalse();
    }

    @Test
    void serviceNeedsItsOwnGrantAndExactHumanDelegationAndHumanGrant() {
        Request request = new Request(SERVICE, TASK, Action.QUERY, Purpose.SOURCE_QUERY, SOURCE, NOW);
        Grant serviceGrant = grant(SERVICE, Action.QUERY, Purpose.SOURCE_QUERY);
        Grant userGrant = grant(USER, Action.QUERY, Purpose.SOURCE_QUERY);
        Delegation delegation = new Delegation(USER, SERVICE, TASK, Action.QUERY,
                Purpose.SOURCE_QUERY, END, 5);
        SourceUse use = new SourceUse(SOURCE, Purpose.SOURCE_QUERY, END, 7);
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(serviceGrant, userGrant), delegation, List.of(use)))).isTrue();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(userGrant), delegation, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(serviceGrant), delegation, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(serviceGrant, userGrant), null, List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(serviceGrant, userGrant), new Delegation(USER, SERVICE, TASK,
                        Action.QUERY, Purpose.SOURCE_QUERY, END, 4), List.of(use)))).isFalse();
        assertThat(ProductAccessPolicy.permits(new Request(SERVICE, TASK, Action.HUMAN_REVIEW,
                Purpose.TASK_WORK, null, NOW), new Evidence(CURRENT,
                List.of(grant(SERVICE, Action.HUMAN_REVIEW, Purpose.TASK_WORK),
                        grant(USER, Action.HUMAN_REVIEW, Purpose.TASK_WORK)),
                new Delegation(USER, SERVICE, TASK, Action.HUMAN_REVIEW,
                        Purpose.TASK_WORK, END, 5), List.of()))).isFalse();
    }

    @Test
    void malformedAndUnboundFactsFailClosed() {
        Request request = new Request(USER, TASK, Action.READ, Purpose.TASK_WORK, null, NOW);
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                List.of(grant(USER, Action.READ, Purpose.TASK_WORK)), null, List.of()))).isTrue();
        assertThat(ProductAccessPolicy.permits(new Request(new Actor("", "user-1", Kind.HUMAN, "org-1"),
                TASK, Action.READ, Purpose.TASK_WORK, null, NOW), new Evidence(CURRENT,
                List.of(grant(USER, Action.READ, Purpose.TASK_WORK)), null, List.of()))).isFalse();
        assertThat(ProductAccessPolicy.permits(new Request(USER, TASK, Action.READ,
                Purpose.TASK_WORK, SOURCE, NOW), new Evidence(CURRENT,
                List.of(grant(USER, Action.READ, Purpose.TASK_WORK)), null, List.of()))).isFalse();
        assertThat(ProductAccessPolicy.permits(request, new Evidence(CURRENT,
                null, null, List.of()))).isFalse();
    }

    private static Grant grant(Actor actor, Action action, Purpose purpose) {
        return new Grant(actor, TASK, action, purpose, END, 5);
    }
}
