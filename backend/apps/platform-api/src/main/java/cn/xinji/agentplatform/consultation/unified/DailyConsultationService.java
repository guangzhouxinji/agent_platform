package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.intent.application.ConsultationIntentParserService;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseCommand;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult;
import cn.xinji.agentplatform.consultation.unified.DailyConsultationResponse.RouteResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DailyConsultationService {
    private static final String LIMITATION = "统一入口只编排现有本地开发能力；检索相关度和辅助观察不是法律结论，正式使用前仍需专业复核。";
    private static final List<String> COMPLETED_STATUSES = List.of("ANSWER_READY", "ACTION_REQUIRED");
    private final ConsultationIntentParserService parser;
    private final DailyConsultationToolRunner tools;
    private final ExternalModelIntentAdvisor modelIntent;
    private final DailyIntentContractCatalog contracts;

    public DailyConsultationService(ConsultationIntentParserService parser, DailyConsultationToolRunner tools,
            ExternalModelIntentAdvisor modelIntent, DailyIntentContractCatalog contracts) {
        this.parser = parser;
        this.tools = tools;
        this.modelIntent = modelIntent;
        this.contracts = contracts;
    }

    public DailyConsultationResponse submit(String requester, String key, DailyConsultationRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new InvalidRequestException("咨询问题不能为空");
        }
        if (key == null || key.isBlank()) throw new InvalidRequestException("Idempotency-Key 不能为空");
        List<IntentParseCommand.ContextTurn> context = request.context().stream()
                .map(value -> new IntentParseCommand.ContextTurn(value.round(), value.content())).toList();
        IntentParseResult parsed = parser.parse(requester, new IntentParseCommand(request.question(), context));
        Resolution resolution = preferModelCandidates(parsed, modelIntent.advise(request.question(),
                context.stream().map(IntentParseCommand.ContextTurn::content).toList(), parsed.traceId()));
        parsed = resolution.parsed();
        String consultationId = "DC-" + UUID.randomUUID();
        List<RouteResult> results = new ArrayList<>();
        for (IntentParseResult.IntentCandidate intent : parsed.intents()) {
            if (!intent.missingSlots().isEmpty() || !"AVAILABLE".equals(intent.routeState())) {
                results.add(toPending(intent));
                continue;
            }
            try {
                results.add(tools.run(requester, key + ":" + intent.order(), request.question(), intent));
            } catch (RuntimeException failure) {
                results.add(new RouteResult(intent.intentId(), intent.label(), intent.category(), "TOOL_FAILURE", "",
                        "该项能力执行失败，未把故障解释为没有相关结果。", "", failure.getMessage() == null ? "工具执行失败" : failure.getMessage(),
                        "可稍后重试本项咨询", "", List.of(), List.of()));
            }
        }
        String status = overallStatus(parsed, results);
        String content = summary(results, parsed.clarificationQuestions());
        return new DailyConsultationResponse(consultationId, status, parsed.parserVersion(), resolution.source(), parsed.primaryIntentId(),
                parsed.clarificationQuestions(), results, content, LIMITATION);
    }

    private static RouteResult toPending(IntentParseResult.IntentCandidate intent) {
        String summary = intent.missingSlots().isEmpty() ? intent.reason() : "还需要补充：" + intent.missingSlots().stream()
                .map(DailyConsultationService::missingLabel).collect(java.util.stream.Collectors.joining("、"));
        return new RouteResult(intent.intentId(), intent.label(), intent.category(), "NEEDS_CLARIFICATION", "", summary,
                "", "未调用下游工具", "补充信息后重新发送", "", List.of(), List.of());
    }

    private Resolution preferModelCandidates(IntentParseResult parsed, List<DailyIntentContractCatalog.IntentAdvice> suggestions) {
        if (suggestions.isEmpty()) return new Resolution(parsed, "FIXED_RULE_FALLBACK");
        List<IntentParseResult.IntentCandidate> ordered = new ArrayList<>();
        for (DailyIntentContractCatalog.IntentAdvice advice : suggestions) {
            DailyIntentContractCatalog.Contract contract = contracts.find(advice.intentId()).orElse(null);
            if (contract == null) continue;
            IntentParseResult.IntentCandidate fixed = parsed.intents().stream()
                    .filter(value -> advice.intentId().equals(value.intentId())).findFirst().orElse(null);
            ordered.add(modelCandidate(contract, fixed, advice, ordered.size() + 1));
        }
        if (ordered.isEmpty()) return new Resolution(parsed, "FIXED_RULE_FALLBACK");
        parsed.intents().stream().filter(value -> !"UNKNOWN".equals(value.intentId())
                && !(value.intentId().endsWith("_UNCLEAR") && ordered.stream()
                        .anyMatch(model -> model.category().equals(value.category())))
                && ordered.stream().noneMatch(model -> model.intentId().equals(value.intentId())))
                .forEach(value -> ordered.add(reorder(value, ordered.size() + 1)));
        boolean missing = ordered.stream().anyMatch(value -> !value.missingSlots().isEmpty());
        String status = missing ? "NEEDS_CLARIFICATION" : ordered.size() > 1 ? "MULTI_INTENT" : "RESOLVED";
        List<String> questions = ordered.stream().flatMap(value -> value.missingSlots().stream()
                .map(slot -> value.label() + "还需要补充：" + missingLabel(slot))).distinct().toList();
        return new Resolution(new IntentParseResult(status, parsed.parserVersion() + "+" + contracts.version(), parsed.question(),
                ordered.getFirst().intentId(), ordered, questions, parsed.limitation(), parsed.traceId(), parsed.parsedAt()),
                "MODEL_CANDIDATE_CONFIRMED");
    }

    private IntentParseResult.IntentCandidate modelCandidate(DailyIntentContractCatalog.Contract contract,
            IntentParseResult.IntentCandidate fixed, DailyIntentContractCatalog.IntentAdvice advice, int order) {
        Map<String, IntentParseResult.ExtractedSlot> merged = new LinkedHashMap<>();
        if (fixed != null) fixed.slots().forEach(slot -> merged.putIfAbsent(slot.name(), slot));
        advice.slots().forEach((name, value) -> merged.putIfAbsent(name,
                new IntentParseResult.ExtractedSlot(name, value, 0, "MODEL_CANDIDATE")));
        List<IntentParseResult.ExtractedSlot> slots = List.copyOf(merged.values());
        List<String> missing = contracts.missing(contract, new LinkedHashSet<>(merged.keySet()));
        return new IntentParseResult.IntentCandidate(contract.intentId(), contract.label(), contract.category(), order,
                List.of("MODEL_CANDIDATE"), slots, missing, missing.isEmpty() ? contract.route() : null,
                missing.isEmpty() ? "AVAILABLE" : "NOT_READY", fixed == null ? "模型候选已通过契约校验" : fixed.reason());
    }

    private static IntentParseResult.IntentCandidate reorder(IntentParseResult.IntentCandidate value, int order) {
        return new IntentParseResult.IntentCandidate(value.intentId(), value.label(), value.category(), order, value.matchedSignals(),
                value.slots(), value.missingSlots(), value.plannedRoute(), value.routeState(), value.reason());
    }

    private record Resolution(IntentParseResult parsed, String source) {}

    private static String missingLabel(String slot) {
        return switch (slot) {
            case "specificIssueOrFacts" -> "具体争议或关键事实";
            case "consultationPurpose" -> "希望查询、检索、比对还是参考案例";
            case "registrationNumberOrCondition" -> "注册号或明确查询条件";
            case "patentNumberOrCondition" -> "专利号或明确查询条件";
            case "trademarkName" -> "商标名称";
            case "categoryOrGoodsServices", "goodsServices" -> "类别及商品或服务";
            case "technicalInputOrPatentNumber" -> "技术方案、技术特征或专利号";
            case "searchPurpose", "analysisPurpose" -> "检索或分析目的";
            case "targetTrademark" -> "目标商标";
            case "candidateTrademark" -> "待比较商标";
            case "targetPatent" -> "目标专利";
            case "candidatePatent" -> "待比较专利";
            default -> "必要信息";
        };
    }

    static String overallStatus(IntentParseResult parsed, List<RouteResult> results) {
        if (results.isEmpty() || "UNKNOWN".equals(parsed.status())) return "NEEDS_CLARIFICATION";
        long completed = results.stream().filter(value -> COMPLETED_STATUSES.contains(value.status())).count();
        long partial = results.stream().filter(value -> "PARTIAL_RESULT".equals(value.status())).count();
        long pending = results.stream().filter(value -> "NEEDS_CLARIFICATION".equals(value.status())).count();
        long noResult = results.stream().filter(value -> "NO_RESULT".equals(value.status())).count();
        if (completed == results.size()) return "ANSWER_READY";
        if (completed + partial > 0) return "PARTIAL_RESULT";
        if (noResult == results.size()) return "NO_RESULT";
        if (noResult > 0 && noResult + pending == results.size()) return "PARTIAL_RESULT";
        return pending == results.size() ? "NEEDS_CLARIFICATION" : "TOOL_FAILURE";
    }

    static String summary(List<RouteResult> results, List<String> clarifications) {
        if (results.isEmpty()) return clarifications.isEmpty() ? "暂时无法识别具体知识产权诉求，请说明希望查询、检索、比对或参考案例。" : String.join("；", clarifications);
        long ready = results.stream().filter(value -> COMPLETED_STATUSES.contains(value.status())).count();
        long partial = results.stream().filter(value -> "PARTIAL_RESULT".equals(value.status())).count();
        long pending = results.stream().filter(value -> "NEEDS_CLARIFICATION".equals(value.status())).count();
        long noResult = results.stream().filter(value -> "NO_RESULT".equals(value.status())).count();
        if (noResult == results.size()) return "已完成检索，当前资料范围内未找到可支持回答的结果。";
        String value = "已识别 " + results.size() + " 项诉求，完成 " + ready + " 项";
        if (partial > 0) value += "，" + partial + " 项部分完成";
        if (noResult > 0) value += "，" + noResult + " 项在当前范围内无结果";
        return pending == 0 ? value + "。" : value + "，另有 " + pending + " 项需要补充信息。";
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }
}
