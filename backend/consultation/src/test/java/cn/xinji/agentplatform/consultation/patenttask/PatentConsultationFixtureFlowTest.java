package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.*;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PatentConsultationFixtureFlowTest {
    private final PatentTaskCommandService tasks = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(), action -> TaskAuthorizationDecision.allow("fixture-auth"));
    private final PatentConsultationFixtureFlow flow = new PatentConsultationFixtureFlow(tasks,
            action -> TaskAuthorizationDecision.allow("fixture-search"), new FixturePatentSource());

    @Test void wholeFixtureJourneyKeepsVersionsSourcesAndCorrectionInOneTask() {
        var started = flow.start("测试技术方案：温度测量及控制", "fixture-scope",
                List.of("测温单元", "控制单元"));
        assertThat(started.candidates()).isEmpty();
        var found = flow.search(started.taskId(), "测温控制");
        assertThat(found.candidates()).hasSize(2).allMatch(PatentConsultationFixtureFlow.CandidateView::available);
        var chosen = found.candidates().get(0);
        flow.select(started.taskId(), chosen.resultVersion(), 1);
        var source = flow.openSource(started.taskId(), chosen.candidateId(), chosen.documentVersion());
        assertThat(source.locator()).isNotBlank();
        var compared = flow.compare(started.taskId());
        assertThat(compared.comparisons()).hasSize(2)
                .allMatch(item -> item.relationship().equals("UNKNOWN") && item.locator() != null);
        var corrected = flow.correctFeature(started.taskId(), "feature-1", "多点测温单元");
        assertThat(corrected.candidates()).allMatch(item -> !item.available());
        assertThatThrownBy(() -> flow.openSource(started.taskId(), chosen.candidateId(), chosen.documentVersion()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flow.compare(started.taskId()))
                .isInstanceOf(IllegalStateException.class);
        var refound = flow.search(started.taskId(), "多点测温控制");
        assertThat(refound.candidates().stream().filter(PatentConsultationFixtureFlow.CandidateView::available))
                .hasSize(2);
        flow.select(started.taskId(), 2, 1);
        var redone = flow.compare(started.taskId());
        assertThat(redone.comparisons()).hasSize(2);
        assertThat(flow.prepareHandoff(started.taskId()).humanAvailability()).isEqualTo("UNAVAILABLE");
        assertThat(tasks.snapshot(started.taskId()).comparisonState().versions()).hasSize(2);
    }

    @Test void emptyAndFailedReadsAreDistinctAndDoNotFabricateCandidates() {
        var started = flow.start("测试方案", "fixture-scope", List.of("技术特征"));
        var empty = flow.search(started.taskId(), "fixture:no-result");
        assertThat(empty.retrievalOutcomes()).contains("NO_RESULT");
        assertThat(empty.candidates()).isEmpty();
        var failed = flow.search(started.taskId(), "fixture:failure");
        assertThat(failed.retrievalOutcomes()).contains("TECHNICAL_FAILURE");
        assertThat(failed.candidates()).isEmpty();
        assertThat(flow.endWithoutEvidence(started.taskId()).taskStatus())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test void missingKeyFeatureWaitsForOneSupplementBeforeAnySearch() {
        var started = flow.start("测试方案", "fixture-scope", List.of());
        assertThat(started.taskStatus()).isEqualTo("WAITING_FOR_USER_INPUT");
        assertThatThrownBy(() -> flow.search(started.taskId(), "query"))
                .isInstanceOf(IllegalArgumentException.class);
        var continued = flow.supplementFeatures(started.taskId(), List.of("用户确认的技术特征"));
        assertThat(continued.taskStatus()).isEqualTo("IN_PROGRESS");
        assertThat(flow.search(started.taskId(), "query").candidates()).hasSize(2);
    }

    @Test void scopeChangeRequiresRecordedConfirmationAndCancelStopsFurtherReads() {
        var started = flow.start("测试方案", "scope-1", List.of("特征"));
        flow.search(started.taskId(), "query-1");
        var changed = flow.changeScope(started.taskId(), "scope-2");
        assertThat(changed.candidates()).allMatch(item -> !item.available());
        assertThat(flow.search(started.taskId(), "query-2").candidates().stream()
                .filter(PatentConsultationFixtureFlow.CandidateView::available)).hasSize(2);
        assertThat(flow.cancel(started.taskId()).taskStatus()).isEqualTo("USER_CANCELLED");
        assertThatThrownBy(() -> flow.search(started.taskId(), "query-3"))
                .isInstanceOf(TaskCommandException.class);
        assertThatThrownBy(() -> flow.resume(started.taskId()))
                .isInstanceOf(TaskCommandException.class);
    }
}
