package cn.xinji.agentplatform.consultation.casesearch.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryPort;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCandidate;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCandidate.EvidenceExcerpt;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCommand;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CaseSearchApplicationService {
    private static final String RANKING_VERSION = "PUBLIC-LOCAL-CASE-TERM-RANK-V1";
    private static final String LIMITATION = "仅检索本地公开案例快照；分数是固定词项相关度，不是经专家标定的案情相似度、裁判结果预测或法律意见。";
    private static final Set<String> DOMAIN_TERMS = Set.of(
            "商标", "专利", "侵权", "无效", "注册", "行政裁决", "技术秘密", "植物新品种", "垄断", "赔偿", "许可", "合同");
    private static final Set<String> GENERIC_QUESTIONS = Set.of("商标问题", "专利问题", "知识产权问题", "咨询", "案例参考");

    private final CaseSearchTaskRepository repository;
    private final CaseLibraryPort cases;
    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public CaseSearchApplicationService(CaseSearchTaskRepository repository, CaseLibraryPort cases,
                                        ConsultationAuthorizationPort authorization, Clock clock) {
        this.repository = repository;
        this.cases = cases;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Preparation prepare(String requester, String question) {
        requireAccess(requester);
        String normalized = question == null ? "" : question.trim();
        if (normalized.isBlank()) throw error("INVALID_REQUEST", "咨询问题不能为空", HttpStatus.BAD_REQUEST);
        List<String> keywords = extractKeywords(normalized);
        boolean clarification = GENERIC_QUESTIONS.contains(normalized) || keywords.isEmpty();
        return new Preparation(normalized, keywords, clarification,
                clarification ? List.of("请补充具体争议、关键事实或希望参考的处理问题") : List.of(),
                "关键词仅来自本次输入中的可见词项，请在创建任务前确认或修改。", UUID.randomUUID().toString());
    }

    public CaseSearchTask create(String requester, String key, CaseSearchCommand command) {
        requireAccess(requester);
        if (key == null || key.isBlank()) throw error("INVALID_REQUEST", "Idempotency-Key 不能为空", HttpStatus.BAD_REQUEST);
        String fingerprint = fingerprint(command);
        Optional<CaseSearchTask> existing = repository.findByIdempotency(requester, key);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw error("IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求", HttpStatus.CONFLICT);
            }
            return existing.get();
        }

        List<CaseSearchCandidate> ranked = cases.records().stream()
                .filter(record -> inScope(record, command))
                .map(record -> score(record, command.confirmedKeywords()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(CaseSearchCandidate::relevanceScore).reversed()
                        .thenComparing(CaseSearchCandidate::decisionDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CaseSearchCandidate::sourceId))
                .limit(command.resultLimit()).toList();
        String scope = "本地公开案例快照 " + cases.records().size() + " 条；按确认范围和关键词检索";
        CaseSearchTask.Status status = ranked.isEmpty() ? CaseSearchTask.Status.NO_RESULT : CaseSearchTask.Status.SUCCEEDED;
        CaseSearchTask task = new CaseSearchTask("CS-" + UUID.randomUUID(), requester, key, fingerprint, command,
                status, cases.dataMode(), cases.sourceVersion(), RANKING_VERSION, scope, ranked.size(), ranked,
                LIMITATION, UUID.randomUUID().toString(), clock.instant());
        CaseSearchTask saved = repository.saveIfAbsent(task);
        if (!saved.requestFingerprint().equals(fingerprint)) {
            throw error("IDEMPOTENCY_CONFLICT", "幂等键已用于不同请求", HttpStatus.CONFLICT);
        }
        return saved;
    }

    public CaseSearchTask read(String requester, String taskId) {
        requireAccess(requester);
        CaseSearchTask task = repository.findById(taskId).orElseThrow(() -> error("TASK_NOT_FOUND", "任务不存在", HttpStatus.NOT_FOUND));
        if (!task.requester().equals(requester)) throw error("TASK_NOT_FOUND", "任务不存在", HttpStatus.NOT_FOUND);
        return task;
    }

    public List<CaseSearchTask> history(String requester) {
        requireAccess(requester);
        return repository.findByRequester(requester);
    }

    private Optional<CaseSearchCandidate> score(CaseLibraryRecord record, List<String> keywords) {
        String title = value(record.title());
        String outcome = value(record.outcomeExcerpt());
        List<CaseLibraryRecord.LocatedParagraph> paragraphs = record.documents().stream()
                .flatMap(document -> document.paragraphs().stream()).toList();
        String body = paragraphs.stream().map(CaseLibraryRecord.LocatedParagraph::text).reduce("", (left, right) -> left + "\n" + right);
        int score = 0;
        List<String> matched = new ArrayList<>();
        List<String> basis = new ArrayList<>();
        for (String keyword : keywords) {
            boolean hit = false;
            if (contains(title, keyword)) { score += 35; basis.add("标题命中：" + keyword); hit = true; }
            if (contains(outcome, keyword)) { score += 20; basis.add("裁判结果节选命中：" + keyword); hit = true; }
            if (contains(body, keyword)) { score += 10; basis.add("裁判原文命中：" + keyword); hit = true; }
            if (hit) matched.add(keyword);
        }
        if (score == 0) return Optional.empty();
        EvidenceExcerpt evidence = evidence(paragraphs, outcome, matched);
        int normalized = Math.min(100, score);
        return Optional.of(new CaseSearchCandidate(record.sourceRecordId(), record.sourceVersion(), record.caseNumber(),
                record.docketNumber(), record.title(), record.caseType(), record.intellectualPropertyType(), record.institution(),
                record.procedure(), record.decisionDate(), record.outcomeExcerpt(), normalized,
                normalized >= 60 ? "较高词项相关" : normalized >= 30 ? "中等词项相关" : "较低词项相关",
                matched, basis, evidence, record.sourceRef()));
    }

    private static EvidenceExcerpt evidence(List<CaseLibraryRecord.LocatedParagraph> paragraphs, String outcome, List<String> keywords) {
        for (CaseLibraryRecord.LocatedParagraph paragraph : paragraphs) {
            if (keywords.stream().anyMatch(keyword -> contains(paragraph.text(), keyword))) {
                return new EvidenceExcerpt(excerpt(paragraph.text()), paragraph.sourceLocation(), paragraph.systemLocator(), "SOURCE_TEXT");
            }
        }
        if (!outcome.isBlank()) return new EvidenceExcerpt(excerpt(outcome), "裁判结果节选", "OUTCOME-EXCERPT", "SOURCE_TEXT");
        return new EvidenceExcerpt("", "", "", "SOURCE_NOT_PROVIDED");
    }

    private static boolean inScope(CaseLibraryRecord record, CaseSearchCommand command) {
        return selected(command.caseTypes(), record.caseType())
                && selected(command.intellectualPropertyTypes(), record.intellectualPropertyType())
                && selected(command.institutions(), record.institution())
                && selected(command.procedures(), record.procedure())
                && (command.decisionFrom() == null || record.decisionDate() != null && !record.decisionDate().isBefore(command.decisionFrom()))
                && (command.decisionTo() == null || record.decisionDate() != null && !record.decisionDate().isAfter(command.decisionTo()));
    }

    private static boolean selected(List<String> selected, String value) { return selected.isEmpty() || value != null && selected.contains(value); }
    private static boolean contains(String text, String keyword) { return value(text).toLowerCase(Locale.ROOT).contains(value(keyword).toLowerCase(Locale.ROOT)); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String excerpt(String text) { String normalized=value(text).replaceAll("\\s+", " ").trim(); return normalized.length() <= 260 ? normalized : normalized.substring(0, 260) + "..."; }

    static List<String> extractKeywords(String question) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String term : DOMAIN_TERMS) if (question.contains(term)) keywords.add(term);
        for (String part : question.split("[\\s，,、；;。：:/？?]+")) {
            String normalized = part.replaceAll("(我想|请问|如何|怎么|是否|相关|案例|参考)", "").trim();
            if (normalized.length() >= 2 && normalized.length() <= 12) keywords.add(normalized);
        }
        return keywords.stream().limit(8).toList();
    }

    private void requireAccess(String requester) {
        if (!authorization.maySearch(requester)) throw error("ACCESS_DENIED", "当前身份无权检索案例", HttpStatus.FORBIDDEN);
    }
    private static String fingerprint(CaseSearchCommand command) {
        try {
            String canonical = command.question() + "|" + command.confirmedKeywords() + "|" + command.caseTypes() + "|"
                    + command.intellectualPropertyTypes() + "|" + command.institutions() + "|" + command.procedures() + "|"
                    + command.decisionFrom() + "|" + command.decisionTo() + "|" + command.resultLimit();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static SearchException error(String code, String message, HttpStatus status) { return new SearchException(code, message, status); }

    public record Preparation(String question, List<String> suggestedKeywords, boolean requiresClarification,
                              List<String> issues, String limitation, String traceId) {}
    public static final class SearchException extends RuntimeException {
        private final String code; private final HttpStatus status;
        public SearchException(String code, String message, HttpStatus status) { super(message); this.code=code; this.status=status; }
        public String code() { return code; } public HttpStatus status() { return status; }
    }
}
