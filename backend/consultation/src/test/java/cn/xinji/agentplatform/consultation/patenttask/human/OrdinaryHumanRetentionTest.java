package cn.xinji.agentplatform.consultation.patenttask.human;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrdinaryHumanRetentionTest {
    private static final Instant START = Instant.parse("2026-10-13T00:00:00Z");
    private final OrdinaryHumanRetention retention = new OrdinaryHumanRetention(
            new OrdinaryHumanRetention.Policy("Q-CON-HUMAN-FINAL-01/V3", 3, "a".repeat(64)));

    private OrdinaryHumanRetention.Bounds bounds(long writeDays) {
        return new OrdinaryHumanRetention.Bounds(day(writeDays), day(700), day(600),
                day(600), day(700), day(700));
    }

    private static Instant day(long days) { return START.plus(Duration.ofDays(days)); }

    @Test void readingDoesNotRenewBodyAndTerminalClockCannotMoveWithLaterAuditChanges() {
        var limits = bounds(300);
        assertThat(retention.bodyUntil(START, null, limits)).isEqualTo(day(180));
        retention.requireReadable(day(179), START, null, limits);
        assertThat(retention.bodyUntil(START, null, limits)).isEqualTo(day(180));
        assertThat(retention.bodyUntil(day(30), day(10), limits)).isEqualTo(day(190));
        assertThatThrownBy(() -> retention.requireWritable(day(31), day(30), day(10), limits))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void contentCannotBeReopenedByLateActivityAtTheExactExpiryBoundary() {
        var limits = bounds(300);
        assertThatThrownBy(() -> retention.requireWritable(day(180), START, null, limits))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retention.requireReadable(START.minusNanos(1), START, null, limits))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void sourceUseAndPhysicalSaveRemainDifferentAndBothConstrainTheirOwnOperations() {
        var limits = new OrdinaryHumanRetention.Bounds(day(100), day(300), day(2),
                day(50), day(400), day(400));
        assertThat(retention.bodyUntil(START, null, limits)).isEqualTo(day(50));
        assertThatThrownBy(() -> retention.requireReadable(day(2), START, null, limits))
                .isInstanceOf(IllegalStateException.class);
        assertThat(retention.auditUntil(START, limits)).isEqualTo(day(300));
    }

    @Test void receiptMustOutlastEveryPossibleWriteInItsOriginalTaskScope() {
        retention.requireReceiptCompatibility(START, bounds(365));
        assertThatThrownBy(() -> retention.requireReceiptCompatibility(START, bounds(366)))
                .isInstanceOf(IllegalArgumentException.class);
        var shorterAudit = new OrdinaryHumanRetention.Bounds(day(20), day(500), day(500),
                day(500), day(19), day(500));
        assertThatThrownBy(() -> retention.requireReceiptCompatibility(START, shorterAudit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retention.requireReceiptCompatibility(day(20), bounds(20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void auditDoesNotSlideWhenNewerBusinessActivityOccurs() {
        assertThat(retention.auditUntil(START, bounds(300))).isEqualTo(day(365));
        assertThat(retention.auditUntil(day(5), bounds(300))).isEqualTo(day(370));
        assertThat(retention.auditUntil(START, bounds(300))).isEqualTo(day(365));
    }

    @Test void nativeBackupIncludesEveryStillStoredObjectRatherThanDroppingExpiredRows() {
        assertThat(retention.backupUntil(START, List.of(day(365), day(12)), bounds(100)))
                .isEqualTo(day(12));
        assertThat(retention.backupUntil(START, List.of(day(365)), bounds(100))).isEqualTo(day(30));
        assertThatThrownBy(() -> retention.backupUntil(START, List.of(day(365), START), bounds(100)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retention.backupUntil(START, List.of(), bounds(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void backupCannotExtendPastThirtyDaysOrItsExactExpiry() {
        retention.requireBackupCurrent(day(29), START, day(30));
        assertThatThrownBy(() -> retention.requireBackupCurrent(day(30), START, day(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> retention.requireBackupCurrent(day(1), START, day(31)))
                .isInstanceOf(IllegalStateException.class);
    }
}
