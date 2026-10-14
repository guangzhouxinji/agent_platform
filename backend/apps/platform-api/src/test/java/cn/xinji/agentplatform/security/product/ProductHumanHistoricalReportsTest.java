package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.*;
import org.junit.jupiter.api.Test;

/** Real H2 matrix closure plus controlled lineage-port boundary tests; no runtime authorization claim. */
class ProductHumanHistoricalReportsTest {
    @Test void exactReportSurvivesTaskRevisionButOrdinaryEndpointKeepsCurrentVersionGuard() throws Exception {
        var f = new ProductComparisonMatrixExecutionTest(); f.setup();
        var scope = f.f.scope;
        var receipt = f.execution.execute(f.f.source.request, scope, f.f.acquire("human-history"));
        var fixture = f.f.source.fixture;
        var original = f.store.read(scope.task(), scope.comparison(), receipt.reportId());
        fixture.db.update("UPDATE con3_task SET version=version+1 WHERE task_id=?", scope.task());
        assertThatThrownBy(() -> f.reader().report(scope.task(), scope.comparison(), receipt.reportId(), fixture.human))
                .hasMessageContaining("CLOSURE_INVALID");
        assertThat(f.reader().historicalReport(scope.task(), scope.comparison(), receipt.reportId(), fixture.human,
                fixture.input.comparisons)).isEqualTo(original);
        assertThat(f.calls).hasValue(1);
    }

    @Test void historicalReadRejectsMissingNativeCitationAfterTaskRevision() throws Exception {
        var f = new ProductComparisonMatrixExecutionTest(); f.setup();
        var scope = f.f.scope;
        var receipt = f.execution.execute(f.f.source.request, scope, f.f.acquire("human-history-corrupt"));
        var fixture = f.f.source.fixture;
        fixture.db.update("UPDATE con3_task SET version=version+1 WHERE task_id=?", scope.task());
        fixture.db.update("DELETE FROM con5_matrix_citation WHERE report_id=?", receipt.reportId());
        assertThatThrownBy(() -> f.reader().historicalReport(scope.task(), scope.comparison(), receipt.reportId(), fixture.human,
                fixture.input.comparisons)).hasMessageContaining("CLOSURE_INVALID");
    }

    @Test void historicalReadStillRejectsCurrentReadRevocation() throws Exception {
        var f = new ProductComparisonMatrixExecutionTest(); f.setup();
        var scope = f.f.scope;
        var receipt = f.execution.execute(f.f.source.request, scope, f.f.acquire("human-history-denied"));
        var fixture = f.f.source.fixture;
        fixture.db.update("UPDATE con3_task SET version=version+1 WHERE task_id=?", scope.task());
        when(fixture.input.access.permits(any(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> f.reader().historicalReport(scope.task(), scope.comparison(), receipt.reportId(), fixture.human,
                fixture.input.comparisons)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void historicalParentKeepsExactChainAfterPointerAndTaskRevisionChange() {
        var f = historicalParent();
        assertThatThrownBy(() -> f.reader.report("task", "current", "report", f.jwt))
                .hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
        var result = f.reader.historicalReport("task", "current", "report", f.jwt);
        assertThat(result.comparisonId()).isEqualTo("current");
        assertThat(result.sourceComparisonId()).isEqualTo("parent");
        assertThat(result.originalJson()).isEqualTo(f.raw);
        assertThat(result.report()).isEqualTo(f.report);
    }

    @Test void historicalParentRejectsSourceSaveWithdrawal() {
        var f = historicalParent();
        when(f.sources.require("source", "CLAIMS", "SAVE")).thenThrow(new IllegalStateException("SOURCE_WITHDRAWN"));
        assertThatThrownBy(() -> f.reader.historicalReport("task", "current", "report", f.jwt))
                .hasMessage("SOURCE_WITHDRAWN");
    }

    @Test void historicalParentRejectsUnrelatedReportSelection() {
        var f = historicalParent();
        assertThatThrownBy(() -> f.reader.historicalReport("task", "current", "foreign", f.jwt))
                .hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
        verify(f.matrices, never()).read("task", "parent", "foreign");
    }

    @Test void historicalParentRejectsAuthorizationDriftAfterProjection() {
        var f = historicalParent();
        var current = new ProductTaskDirectory.Task("task", "org", "issuer", "subject", "IN_PROGRESS", 3, 3, "scope", 1, f.at.plusSeconds(600));
        var changed = new ProductTaskDirectory.Task("task", "org", "issuer", "subject", "IN_PROGRESS", 3, 4, "scope", 1, f.at.plusSeconds(600));
        when(f.loader.require("task", f.jwt, ProductAccessPolicy.Action.READ)).thenReturn(current, current, changed);
        assertThatThrownBy(() -> f.reader.historicalReport("task", "current", "report", f.jwt))
                .hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }

    @Test void changingCurrentAccessWindowDoesNotRenewOrShortenOriginalRetentionDeadline() {
        var f = historicalParent();
        when(f.loaded.expiresAt()).thenReturn(f.at.plusSeconds(60));
        var shorter = f.reader.historicalReport("task", "current", "report", f.jwt);
        var shortRetention = f.reader.retainedUntil(shorter, f.jwt);
        when(f.loaded.expiresAt()).thenReturn(f.at.plusSeconds(300));
        var longer = f.reader.historicalReport("task", "current", "report", f.jwt);
        assertThat(shorter.expiresAt()).isEqualTo(f.at.plusSeconds(60));
        assertThat(longer.expiresAt()).isEqualTo(f.at.plusSeconds(300));
        assertThat(f.reader.retainedUntil(longer, f.jwt)).isEqualTo(shortRetention).isEqualTo(f.at.plusSeconds(600));
        f.now = f.at.plusSeconds(60);
        assertThatThrownBy(() -> f.reader.retainedUntil(shorter, f.jwt)).hasMessage("LINEAGE_PARENT_OR_CURRENT_CHANGED");
    }

    private ProductComparisonParentEvidenceReaderTest historicalParent() {
        var f = new ProductComparisonParentEvidenceReaderTest(); f.setup();
        when(f.inputStore.current("task")).thenReturn("successor");
        when(f.loader.require("task", f.jwt, ProductAccessPolicy.Action.READ)).thenReturn(
                new ProductTaskDirectory.Task("task", "org", "issuer", "subject", "IN_PROGRESS", 3, 3, "scope", 1, f.at.plusSeconds(600)));
        return f;
    }
}
