package cn.xinji.agentplatform.consultation.patenttask.human;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.*;

class OrdinaryHumanHandlingTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private final Actor requester = new Actor("https://consultation.example", "requester", ActorKind.HUMAN);
    private final Actor other = new Actor("https://consultation.example", "other", ActorKind.HUMAN);
    private final Binding original = new Binding("goal@1", "facts@1", "input@1", "report@1");
    private final Packet packet = new Packet("task", "package", 1, "a".repeat(64),
            requester, original, List.of("source-v1/claim-1"));
    private final OrdinaryHumanRetention retention = new OrdinaryHumanRetention(
            new OrdinaryHumanRetention.Policy("Q-CON-HUMAN-FINAL-01/V3", 3, "a".repeat(64)));
    private final OrdinaryHumanRetention.Bounds bounds = new OrdinaryHumanRetention.Bounds(
            NOW.plus(Duration.ofDays(100)), NOW.plus(Duration.ofDays(200)),
            NOW.plus(Duration.ofDays(100)), NOW.plus(Duration.ofDays(200)),
            NOW.plus(Duration.ofDays(365)), NOW.plus(Duration.ofDays(200)));

    private OrdinaryHumanHandling requested() {
        return OrdinaryHumanHandling.request(packet, NOW, retention, bounds);
    }

    private OrdinaryHumanHandling claimed() { return requested().claim(1, requester, NOW, retention, bounds); }

    private OrdinaryHumanHandling opinion(OrdinaryHumanHandling handling, String previous, String id, Actor actor,
                                         Binding binding, Instant now) {
        return handling.recordOpinion(handling.processingVersion(), previous, id, actor, now,
                "An ordinary auxiliary opinion", List.of("source-v1/claim-1"),
                "No professional review or official decision", binding, retention, bounds);
    }

    @Test void requesterMayClaimButSelfOpinionCanNeverBecomeIndependentReview() {
        var waiting = requested();
        assertThat(waiting.state()).isEqualTo(State.WAITING);
        var result = opinion(claimed(), null, "opinion-1", requester, original, NOW);
        assertThat(result.selfHandling()).isTrue();
        assertThat(result.claimant()).isEqualTo(requester);
        assertThat(result.opinions().getFirst().author()).isEqualTo(requester);
        assertThat(result.opinions().getFirst().independentReview()).isFalse();
        assertThat(waiting.state()).isEqualTo(State.WAITING);
    }

    @Test void onlyOneVersionCanClaimAndASecondActorCannotTakeOverTheClaim() {
        var first = requested().claim(1, other, NOW, retention, bounds);
        assertThat(first.selfHandling()).isFalse();
        assertThatThrownBy(() -> first.claim(1, requester, NOW, retention, bounds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> first.claim(2, requester, NOW, retention, bounds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> opinion(first, null, "opinion", requester, original, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingClaimAndServiceImpersonationCannotProduceHumanOpinions() {
        assertThatThrownBy(() -> opinion(requested(), null, "opinion", requester, original, NOW))
                .isInstanceOf(IllegalStateException.class);
        var service = new Actor(requester.issuer(), requester.subject(), ActorKind.SERVICE);
        assertThatThrownBy(() -> requested().claim(1, service, NOW, retention, bounds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void correctionMakesLateOpinionHistoricalWithoutChangingPackageOrOldOpinion() {
        var first = opinion(claimed(), null, "opinion-1", requester, original, NOW);
        var corrected = new Binding("goal@1", "facts@2", "input@2", "report@2");
        var next = opinion(first, "opinion-1", "opinion-2", requester, corrected, NOW.plusSeconds(1));
        assertThat(next.opinions()).hasSize(2);
        assertThat(next.opinions().getFirst()).isEqualTo(first.opinions().getFirst());
        assertThat(next.opinions().getLast().attribution()).isEqualTo(Attribution.HISTORICAL_VERSION);
        assertThat(next.packet()).isEqualTo(packet);
        assertThat(first.opinions()).hasSize(1);
    }

    @Test void endFirstRejectsLateOpinionAndOpinionFirstPreservesHistoryWhenEnding() {
        var claim = claimed();
        var withdrawn = claim.withdraw(2, requester, NOW, "No longer needed", retention, bounds);
        assertThatThrownBy(() -> opinion(withdrawn, null, "late", requester, original, NOW))
                .isInstanceOf(IllegalStateException.class);
        var saved = opinion(claim, null, "opinion-1", requester, original, NOW);
        assertThatThrownBy(() -> saved.withdraw(2, requester, NOW, "Stale termination", retention, bounds))
                .isInstanceOf(IllegalStateException.class);
        var returned = saved.returnPackage(3, requester, NOW, "More material needed", retention, bounds);
        assertThat(returned.state()).isEqualTo(State.RETURNED);
        assertThat(returned.opinions()).isEqualTo(saved.opinions());
        assertThatThrownBy(() -> returned.claim(4, requester, NOW, retention, bounds))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void requesterMayWithdrawUnclaimedPackageButAnotherActorCannotWithdrawOrReturnIt() {
        assertThat(requested().withdraw(1, requester, NOW, "Withdraw request", retention, bounds).state())
                .isEqualTo(State.WITHDRAWN);
        assertThatThrownBy(() -> requested().withdraw(1, other, NOW, "Not requester", retention, bounds))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested().returnPackage(1, requester, NOW, "Not claimed", retention, bounds))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void opinionMustKeepExactPreviousRevisionAndPackageEvidence() {
        var first = opinion(claimed(), null, "opinion-1", requester, original, NOW);
        assertThatThrownBy(() -> opinion(first, null, "opinion-2", requester, original, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> opinion(first, "opinion-1", "opinion-1", requester, original, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> first.recordOpinion(3, "opinion-1", "opinion-2", requester, NOW,
                "Body", List.of("unclaimed/source-v2"), "Limits", original, retention, bounds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void restoredProjectionCannotInventClaimOrOpinionHistoryAndExpiryCannotBeRenewed() {
        assertThatThrownBy(() -> new OrdinaryHumanHandling(packet, 1, State.CLAIMED,
                requester, NOW, NOW, null, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claimed().recordOpinion(2, null, "expired", requester,
                bounds.taskWriteUntil(), "Body", List.of(), "Limits", original, retention, bounds))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void aGeneralConsultationPackageNeedsNoComparisonReportOrModelExecution() {
        var binding = new Binding("goal@1", "facts@1", null, null);
        var general = new Packet("task", "general", 1, "b".repeat(64), requester, binding, List.of());
        var claim = OrdinaryHumanHandling.request(general, NOW, retention, bounds)
                .claim(1, requester, NOW, retention, bounds);
        var result = claim.recordOpinion(2, null, "general-opinion", requester, NOW, "Ordinary help",
                List.of(), "No legal conclusion", binding, retention, bounds);
        assertThat(result.opinions().getFirst().attribution()).isEqualTo(Attribution.CURRENT_VERSION);
    }
}
