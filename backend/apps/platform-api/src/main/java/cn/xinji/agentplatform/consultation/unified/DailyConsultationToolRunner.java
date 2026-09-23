package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.application.ConsultationApplicationService;
import cn.xinji.agentplatform.consultation.application.ConsultationAnswerGenerator;
import cn.xinji.agentplatform.consultation.casesearch.application.CaseSearchApplicationService;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCommand;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchTask;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult;
import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryService;
import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryService;
import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchApplicationService;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import cn.xinji.agentplatform.consultation.unified.DailyConsultationResponse.Candidate;
import cn.xinji.agentplatform.consultation.unified.DailyConsultationResponse.RouteResult;
import cn.xinji.agentplatform.consultation.unified.DailyConsultationResponse.Source;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DailyConsultationToolRunner {
    private static final Pattern CATEGORY_NUMBER = Pattern.compile("第?\\s*([0-9]{1,2})\\s*类");
    private static final Pattern GOODS_AFTER_CATEGORY = Pattern.compile("第?\\s*[0-9]{1,2}\\s*类[：:\\s]*([^，。；;、]{2,24})");
    private final ConsultationApplicationService patents;
    private final PatentLibraryService patentLibrary;
    private final TrademarkLibraryService trademarkLibrary;
    private final TrademarkSearchApplicationService trademarks;
    private final CaseSearchApplicationService cases;
    private final ConsultationAnswerGenerator modelAnswers;
    private final DailyConsultationAnswerComposer answers = new DailyConsultationAnswerComposer();

    public DailyConsultationToolRunner(ConsultationApplicationService patents, PatentLibraryService patentLibrary,
            TrademarkLibraryService trademarkLibrary, TrademarkSearchApplicationService trademarks,
            CaseSearchApplicationService cases, ConsultationAnswerGenerator modelAnswers) {
        this.patents = patents;
        this.patentLibrary = patentLibrary;
        this.trademarkLibrary = trademarkLibrary;
        this.trademarks = trademarks;
        this.cases = cases;
        this.modelAnswers = modelAnswers;
    }

    RouteResult run(String requester, String key, String question, IntentParseResult.IntentCandidate intent) {
        return switch (intent.intentId()) {
            case "PATENT_SIMILAR_SEARCH", "PATENT_COMPARISON" -> patentSearch(requester, key, question, intent);
            case "TRADEMARK_SIMILAR_SEARCH", "TRADEMARK_COMPARISON" -> trademarkSearch(requester, key, question, intent);
            case "CASE_REFERENCE" -> caseSearch(requester, key, question, intent);
            case "PATENT_INFORMATION" -> patentInformation(requester, question, intent);
            case "TRADEMARK_INFORMATION" -> trademarkInformation(requester, question, intent);
            default -> unavailable(intent);
        };
    }

    private RouteResult patentSearch(String requester, String key, String question, IntentParseResult.IntentCandidate intent) {
        String scopedQuestion = "PATENT_SIMILAR_SEARCH".equals(intent.intentId())
                ? firstSlot(intent, "technicalInput", "patentNumber") : "";
        scopedQuestion = normalizePatentInput(scopedQuestion);
        ConsultationOutcome outcome = patents.submit(requester, scopedQuestion.isBlank() ? question : scopedQuestion,
                "TECHNICAL_REFERENCE", key);
        List<Candidate> candidates = outcome.candidates().stream().map(value -> new Candidate(value.sourceId(), value.version(),
                "专利文献 " + value.sourceId(), value.sourceId(), value.matchBasis(), value.location())).toList();
        List<Source> sources = outcome.sourceLinks().stream().map(value -> new Source(value.sourceId(), value.version(),
                "专利文献 " + value.sourceId(), value.location(), "/api/v0/consultations/" + outcome.consultationId()
                        + "/source-links/" + value.sourceLinkId())).toList();
        boolean compare = "PATENT_COMPARISON".equals(intent.intentId());
        String status = compare && !candidates.isEmpty() ? "ACTION_REQUIRED" : outcome.type();
        String summary = candidates.isEmpty() ? "当前本地资料未检索到可支持本问题的专利候选，暂时无法据此给出确定回答。"
                : answers.withEvidence(outcome.content(), candidates);
        String nextAction = candidates.isEmpty() ? "可补充 CPC 分类号、关键词或技术特征后重试"
                : compare ? "请选择目标文献和候选文献后继续技术特征对照" : "可选择候选继续技术特征对照";
        return result(intent, status, outcome.retrievalTaskId(), summary, "当前本地专利检索范围", outcome.limitation(), nextAction,
                candidates.isEmpty() ? "" : "/app/compare/patents", candidates, sources);
    }

    private RouteResult trademarkSearch(String requester, String key, String question, IntentParseResult.IntentCandidate intent) {
        String name = slot(intent, "trademarkName");
        String category = slot(intent, "category");
        String combined = slot(intent, "categoryOrGoodsServices");
        if (category.isBlank()) category = category(combined);
        if (category.isBlank()) category = category(question);
        String goods = firstSlot(intent, "goodsServices");
        if (goods.isBlank()) goods = goods(combined);
        if (goods.isBlank()) goods = goods(question);
        if (name.isBlank() || category.isBlank() || goods.isBlank()) {
            return result(intent, "NEEDS_CLARIFICATION", "", "执行商标检索前还需要明确商标名称、类别和商品或服务。", "",
                    "未调用商标检索", "请补充商标名称、类别和具体商品或服务", "", List.of(), List.of());
        }
        TrademarkSearchCommand command = new TrademarkSearchCommand(TrademarkSearchCommand.InputMode.TEXT, name, null,
                List.of(category), List.of(goods), List.of(TrademarkSearchCommand.Dimension.TEXT,
                TrademarkSearchCommand.Dimension.PHONETIC, TrademarkSearchCommand.Dimension.SEMANTIC),
                TrademarkSearchCommand.SearchScope.empty(), 1, 10);
        TrademarkSearchTask task = trademarks.createImmediate(requester, key, command);
        List<Candidate> candidates = task.candidates().stream().map(value -> new Candidate(value.sourceId(), value.sourceVersion(),
                value.name(), value.registrationNumber(), value.dimensions().stream().map(item -> item.dimension() + "：" + item.basis()).reduce((a, b) -> a + "；" + b).orElse(""),
                "商标目录记录")).toList();
        List<Source> sources = task.candidates().stream().map(value -> new Source(value.sourceId(), value.sourceVersion(), value.name(),
                "注册号 " + value.registrationNumber(), "/app/libraries/trademarks?sourceId=" + value.sourceId())).toList();
        boolean compare = "TRADEMARK_COMPARISON".equals(intent.intentId());
        String status = task.status() == TrademarkSearchTask.Status.FAILED ? "TOOL_FAILURE"
                : task.candidates().isEmpty() ? "NO_RESULT" : compare ? "ACTION_REQUIRED" : "ANSWER_READY";
        String summary = candidates.isEmpty() ? "当前确认范围内未返回商标候选。"
                : answers.withEvidence(modelAnswer(question, DailyConsultationEvidenceFactory.trademarks(task.candidates()),
                        task.taskId(), task.actualScope()), candidates);
        return result(intent, status, task.taskId(), summary, task.actualScope(), task.limitation(),
                candidates.isEmpty() ? "可调整名称、类别或商品服务后重试" : "请选择目标和候选后继续多维辅助比对",
                candidates.isEmpty() ? "" : "/app/compare/trademarks", candidates, sources);
    }

    private RouteResult caseSearch(String requester, String key, String question, IntentParseResult.IntentCandidate intent) {
        var preparation = cases.prepare(requester, question);
        if (preparation.requiresClarification()) {
            return result(intent, "NEEDS_CLARIFICATION", "", String.join("；", preparation.issues()), "", preparation.limitation(),
                    "补充具体争议或关键事实后重新发送", "", List.of(), List.of());
        }
        CaseSearchTask task = cases.create(requester, key, new CaseSearchCommand(question, preparation.suggestedKeywords(),
                List.of(), List.of(), List.of(), List.of(), null, null, 10));
        List<Candidate> candidates = task.candidates().stream().map(value -> new Candidate(value.sourceId(), value.sourceVersion(), value.title(),
                value.caseNumber(), String.join("；", value.rankingBasis()), value.evidence().sourceLocation())).toList();
        List<Source> sources = task.candidates().stream().map(value -> new Source(value.sourceId(), value.sourceVersion(), value.title(),
                value.evidence().sourceLocation(), "/app/libraries/cases?sourceId=" + value.sourceId())).toList();
        String status = candidates.isEmpty() ? "NO_RESULT" : "ANSWER_READY";
        String summary = candidates.isEmpty() ? "当前确认范围内未返回案例候选。"
                : answers.withEvidence(modelAnswer(question, DailyConsultationEvidenceFactory.cases(task.candidates()),
                        task.taskId(), task.actualScope()), candidates);
        return result(intent, status, task.taskId(), summary, task.actualScope(), task.limitation(),
                "案例仅供参考，请结合当前事实核对差异", "", candidates, sources);
    }

    private RouteResult patentInformation(String requester, String question, IntentParseResult.IntentCandidate intent) {
        String number = slot(intent, "patentNumber");
        if (number.isBlank()) return clarification(intent, "请补充专利申请号、公开/公告号或明确查询条件");
        String requestId = UUID.randomUUID().toString();
        var query = new PatentLibraryService.Query(null, number, null, null, null, List.of(), null, false, List.of(), null, null, null, null, 1, 10);
        var page = patentLibrary.query(requester, query, requestId);
        if (page.items().isEmpty()) page = patentLibrary.query(requester, new PatentLibraryService.Query(number, null, null, null, null,
                List.of(), null, false, List.of(), null, null, null, null, 1, 10), requestId);
        List<Candidate> candidates = page.items().stream().map(value -> new Candidate(value.sourceRecordId(), value.sourceVersion(), value.title(),
                value.publicationNumber(), "申请人：" + String.join("、", value.applicants()) + "；状态：" + value.mappedLegalStatus(), "专利目录记录")).toList();
        List<Source> sources = page.items().stream().map(value -> new Source(value.sourceRecordId(), value.sourceVersion(), value.title(),
                "公开/公告号 " + value.publicationNumber(), "/app/libraries/patents?sourceId=" + value.sourceRecordId())).toList();
        return result(intent, candidates.isEmpty() ? "NO_RESULT" : "ANSWER_READY", requestId,
                candidates.isEmpty() ? "本地专利库未找到对应记录。"
                        : answers.withEvidence("已从本地专利库整理出与当前条件匹配的记录。", candidates), "本地专利库",
                page.limitation(), "可打开专利资料库核对全文和事件", "/app/libraries/patents", candidates, sources);
    }

    private RouteResult trademarkInformation(String requester, String question, IntentParseResult.IntentCandidate intent) {
        String number = slot(intent, "registrationNumber");
        String name = slot(intent, "trademarkName");
        if (number.isBlank() && name.isBlank()) return clarification(intent, "请补充商标注册号或明确名称");
        String requestId = UUID.randomUUID().toString();
        var page = trademarkLibrary.query(requester, new TrademarkLibraryService.Query(number, name, null, List.of(), List.of(),
                null, null, null, null, 1, 10), requestId);
        List<Candidate> candidates = page.items().stream().map(value -> new Candidate(value.sourceRecordId(), value.sourceVersion(), value.name(),
                value.registrationNumber(), "申请人：" + String.join("、", value.applicants()) + "；状态：" + value.displayStatus(), "商标目录记录")).toList();
        List<Source> sources = page.items().stream().map(value -> new Source(value.sourceRecordId(), value.sourceVersion(), value.name(),
                "注册号 " + value.registrationNumber(), "/app/libraries/trademarks?sourceId=" + value.sourceRecordId())).toList();
        return result(intent, candidates.isEmpty() ? "NO_RESULT" : "ANSWER_READY", requestId,
                candidates.isEmpty() ? "本地商标库未找到对应记录。"
                        : answers.withEvidence("已从本地商标库整理出与当前条件匹配的记录。", candidates), "本地商标库",
                page.limitation(), "可打开商标资料库核对详情和历史事项", "/app/libraries/trademarks", candidates, sources);
    }

    private static RouteResult clarification(IntentParseResult.IntentCandidate intent, String message) {
        return result(intent, "NEEDS_CLARIFICATION", "", message, "", "未调用下游工具", "补充信息后重新发送", "", List.of(), List.of());
    }

    private String modelAnswer(String question, List<ConsultationCandidate> evidence, String taskId, String scope) {
        return modelAnswers.generate(taskId, question, evidence, scope).content();
    }

    private static RouteResult unavailable(IntentParseResult.IntentCandidate intent) {
        return result(intent, "ROUTE_UNAVAILABLE", "", intent.reason(), "", "当前没有可执行的已授权工具", "请换一种更明确的知识产权问题", "", List.of(), List.of());
    }

    private static RouteResult result(IntentParseResult.IntentCandidate intent, String status, String taskId, String summary,
            String scope, String limitation, String nextAction, String href, List<Candidate> candidates, List<Source> sources) {
        return new RouteResult(intent.intentId(), intent.label(), intent.category(), status, taskId, summary, scope,
                limitation, nextAction, href, candidates, sources);
    }

    private static String slot(IntentParseResult.IntentCandidate intent, String name) {
        return intent.slots().stream().filter(value -> name.equals(value.name())).map(IntentParseResult.ExtractedSlot::value).findFirst().orElse("");
    }

    private static String firstSlot(IntentParseResult.IntentCandidate intent, String... names) {
        for (String name : names) {
            String value = slot(intent, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String category(String value) {
        Matcher matcher = CATEGORY_NUMBER.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String normalizePatentInput(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceFirst("^(?:请|帮我)?(?:检索|查询|查找|搜索)\\s*", "")
                .replaceFirst("[，,；;]?\\s*(?:用于|目的是|目的为).*$", "")
                .replaceFirst("(?:相关|相似|类似)?(?:的)?专利$", "")
                .trim();
    }

    private static String goods(String value) {
        Matcher matcher = GOODS_AFTER_CATEGORY.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
