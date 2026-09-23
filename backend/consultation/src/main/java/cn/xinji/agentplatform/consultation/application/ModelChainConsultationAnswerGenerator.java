package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter;
import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter.ModelFailure;
import cn.xinji.agentplatform.consultation.agentconfig.application.ModelFailoverRouter.RoutingFailure;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelChainConsultationAnswerGenerator implements ConsultationAnswerGenerator {
    private static final Logger log = LoggerFactory.getLogger(ModelChainConsultationAnswerGenerator.class);
    private final ConsultationAnswerModelPort models;
    private final ModelFailoverRouter router;
    private final Supplier<List<String>> modelChain;
    private final Set<String> switchOn;

    public ModelChainConsultationAnswerGenerator(
            ConsultationAnswerModelPort models,
            ModelFailoverRouter router,
            List<String> modelChain,
            Set<String> switchOn) {
        this(() -> List.copyOf(modelChain), models, router, switchOn);
    }

    public ModelChainConsultationAnswerGenerator(
            Supplier<List<String>> modelChain,
            ConsultationAnswerModelPort models,
            ModelFailoverRouter router,
            Set<String> switchOn) {
        this.models = models;
        this.router = router;
        this.modelChain = modelChain;
        this.switchOn = Set.copyOf(switchOn);
    }

    @Override
    public Generation generate(String consultationId, String question,
                               List<ConsultationCandidate> candidates, String actualScope) {
        if (candidates.isEmpty()) return failure(consultationId, "NO_CANDIDATES", List.of());
        try {
            var routed = router.execute(List.copyOf(modelChain.get()), switchOn,
                    modelId -> invoke(modelId, question, candidates, consultationId));
            log.info("Consultation model generation succeeded: consultationId={}, modelId={}, attempts={}",
                    consultationId, routed.selectedModelId(), routed.attempts());
            String citations = routed.result().citedSourceIds().stream()
                    .map(sourceId -> citationLabel(sourceId, versionOf(sourceId, candidates)))
                    .reduce((left, right) -> left + "、" + right).orElseThrow();
            return new Generation(routed.result().content().trim()
                    + "\n\n依据来源：" + citations
                    + "\n仅供技术参考，不构成新颖性、侵权、有效性或授权结论。实际范围：" + actualScope + "。", "");
        } catch (RoutingFailure failure) {
            return failure(consultationId, failure.code(), failure.attempts());
        }
    }

    private ConsultationAnswerModelPort.AnswerDraft invoke(
            String modelId, String question, List<ConsultationCandidate> candidates, String traceId) {
        try {
            var draft = models.generate(modelId, question, candidates, traceId);
            draft = new ConsultationAnswerModelPort.AnswerDraft(draft.content(),
                    normalizeCitations(draft.citedSourceIds(), candidates));
            validate(draft, candidates);
            return draft;
        } catch (ModelFailure failure) {
            throw failure;
        } catch (ConsultationAnswerModelPort.InvocationFailure failure) {
            throw new ModelFailure(failure.code());
        } catch (RuntimeException failure) {
            throw new ModelFailure("MODEL_INVOCATION_FAILED");
        }
    }

    private static void validate(ConsultationAnswerModelPort.AnswerDraft draft,
                                 List<ConsultationCandidate> candidates) {
        if (draft == null || draft.content() == null || draft.content().isBlank()
                || draft.citedSourceIds() == null || draft.citedSourceIds().isEmpty()) {
            throw new ModelFailure("OUTPUT_VALIDATION_FAILED");
        }
        Set<String> available = candidates.stream().map(ConsultationCandidate::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> citations = new HashSet<>(draft.citedSourceIds());
        if (citations.size() != draft.citedSourceIds().size() || !available.containsAll(citations)) {
            throw new ModelFailure("OUTPUT_VALIDATION_FAILED");
        }
    }

    private static List<String> normalizeCitations(List<String> citations,
                                                   List<ConsultationCandidate> candidates) {
        if (citations == null) return List.of();
        return citations.stream().map(String::trim)
                .map(value -> normalizeCitation(value, candidates)).toList();
    }

    private static String normalizeCitation(String citation, List<ConsultationCandidate> candidates) {
        String unwrapped = citation.replaceAll("^[\\[【]\\s*|\\s*[\\]】]$", "");
        return candidates.stream().filter(candidate ->
                        candidate.sourceId().equals(unwrapped)
                                || (candidate.sourceId() + "@" + candidate.version()).equals(unwrapped))
                .map(ConsultationCandidate::sourceId).findFirst().orElse(unwrapped);
    }

    private static String versionOf(String sourceId, List<ConsultationCandidate> candidates) {
        return candidates.stream().filter(candidate -> candidate.sourceId().equals(sourceId))
                .findFirst().orElseThrow().version();
    }

    private static String citationLabel(String sourceId, String version) {
        return version.startsWith(sourceId + "@") ? version : sourceId + "@" + version;
    }

    private static Generation failure(String consultationId, String code, List<ModelFailoverRouter.Attempt> attempts) {
        log.warn("Consultation model generation failed: consultationId={}, code={}, attempts={}",
                consultationId, code, attempts);
        return new Generation(
                "已完成候选检索，但模型辅助答复未生成。请直接核对下方候选及来源。",
                failureMessage(code));
    }

    private static String failureMessage(String code) {
        return switch (code) {
            case "NO_CANDIDATES" -> "当前没有可发送给模型的候选；候选与来源仍可直接核对。";
            case "OUTPUT_VALIDATION_FAILED" -> "模型返回内容未通过来源引用校验，已拒绝展示；候选与来源仍可直接核对。";
            case "MODEL_CHAIN_EXHAUSTED" -> "模型服务暂时不可用，未生成辅助答复；候选与来源仍可直接核对。";
            default -> "模型辅助答复未生成，且未回退为伪造答复；候选与来源仍可直接核对。";
        };
    }
}
