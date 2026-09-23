package cn.xinji.agentplatform.consultation;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter;
import cn.xinji.agentplatform.consultation.application.ConsultationAnswerModelPort;
import cn.xinji.agentplatform.consultation.application.ModelChainConsultationAnswerGenerator;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelChainConsultationAnswerGeneratorTest {
    private static final ConsultationCandidate PUBLIC_CANDIDATE = new ConsultationCandidate(
            "KB-PAT-001", "v3", "DM8 PUBLIC_LOCAL / title+claims", "标题命中：水性粘结剂");

    @Test void primaryModelReturnsAnswerWithStableCitationAndVersion() {
        var port = new StubPort((model, candidates) -> new ConsultationAnswerModelPort.AnswerDraft(
                "候选技术采用水性粘结剂。", List.of("KB-PAT-001")));
        var result = generator(port).generate("con-1", "水性粘结剂", List.of(PUBLIC_CANDIDATE), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("候选技术采用水性粘结剂。", "KB-PAT-001@v3", "不构成新颖性");
        assertThat(result.limitation()).isEmpty();
        assertThat(port.calls).containsExactly("primary");
    }

    @Test void timeoutSwitchesToBackupModel() {
        var port = new StubPort((model, candidates) -> {
            if ("primary".equals(model)) throw new ConsultationAnswerModelPort.InvocationFailure("TIMEOUT");
            return new ConsultationAnswerModelPort.AnswerDraft("备用模型答复。", List.of("KB-PAT-001"));
        });
        var result = generator(port).generate("con-2", "问题", List.of(PUBLIC_CANDIDATE), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("备用模型答复。", "KB-PAT-001@v3");
        assertThat(port.calls).containsExactly("primary", "backup");
    }

    @Test void invalidCitationDoesNotSwitchAndPreservesCandidatesAsFailure() {
        var port = new StubPort((model, candidates) -> new ConsultationAnswerModelPort.AnswerDraft(
                "越界引用。", List.of("UNKNOWN")));
        var result = generator(port).generate("con-3", "问题", List.of(PUBLIC_CANDIDATE), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("模型辅助答复未生成");
        assertThat(result.limitation()).contains("未通过来源引用校验", "候选与来源仍可直接核对");
        assertThat(port.calls).containsExactly("primary");
    }

    @Test void normalizesVersionedAndBracketedCandidateCitations() {
        var port = new StubPort((model, candidates) -> new ConsultationAnswerModelPort.AnswerDraft(
                "候选技术采用水性粘结剂。", List.of("【KB-PAT-001@v3】")));
        var result = generator(port).generate("con-versioned-citation", "问题", List.of(PUBLIC_CANDIDATE), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("候选技术采用水性粘结剂。", "KB-PAT-001@v3");
        assertThat(result.limitation()).isEmpty();
    }

    @Test void doesNotRepeatSourceIdWhenPersistedVersionAlreadyContainsIt() {
        var candidate = new ConsultationCandidate(
                "CN-108276937-A", "CN-108276937-A@20180713", "DM8 PUBLIC_LOCAL / 摘要", "水性粘结剂");
        var port = new StubPort((model, candidates) -> new ConsultationAnswerModelPort.AnswerDraft(
                "候选技术采用水性粘结剂。", List.of("CN-108276937-A")));

        var result = generator(port).generate("con-version-label", "问题", List.of(candidate), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("CN-108276937-A@20180713")
                .doesNotContain("CN-108276937-A@CN-108276937-A@20180713");
    }

    @Test void exhaustedChainPreservesRetrievedCandidatesAsFailure() {
        var port = new StubPort((model, candidates) -> {
            throw new ConsultationAnswerModelPort.InvocationFailure("UPSTREAM_UNAVAILABLE");
        });
        var result = generator(port).generate("con-4", "问题", List.of(PUBLIC_CANDIDATE), "DM8 PUBLIC_LOCAL");

        assertThat(result.content()).contains("已完成候选检索", "模型辅助答复未生成");
        assertThat(result.limitation()).contains("模型服务暂时不可用", "候选与来源仍可直接核对");
        assertThat(port.calls).containsExactly("primary", "backup");
    }

    @Test void authorizedNonPublicLocalCandidateCanUseTheModel() {
        var port = new StubPort((model, candidates) -> new ConsultationAnswerModelPort.AnswerDraft("合成候选辅助答复。", List.of("SYN")));
        var candidate = new ConsultationCandidate("SYN", "v1", "合成来源", "标题命中");
        var result = generator(port).generate("con-5", "问题", List.of(candidate), "合成范围");

        assertThat(result.content()).contains("合成候选辅助答复。", "SYN@v1");
        assertThat(port.calls).containsExactly("primary");
    }

    private static ModelChainConsultationAnswerGenerator generator(ConsultationAnswerModelPort port) {
        return new ModelChainConsultationAnswerGenerator(port, new ModelFailoverRouter(),
                List.of("primary", "backup"), Set.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE"));
    }

    private static final class StubPort implements ConsultationAnswerModelPort {
        private final Behavior behavior;
        private final List<String> calls = new ArrayList<>();
        private StubPort(Behavior behavior) { this.behavior = behavior; }
        @Override public AnswerDraft generate(String modelId, String question,
                                               List<ConsultationCandidate> candidates, String traceId) {
            calls.add(modelId);
            return behavior.generate(modelId, candidates);
        }
    }

    @FunctionalInterface
    private interface Behavior {
        ConsultationAnswerModelPort.AnswerDraft generate(String modelId, List<ConsultationCandidate> candidates);
    }
}
