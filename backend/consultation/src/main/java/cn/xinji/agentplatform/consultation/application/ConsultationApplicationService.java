package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import cn.xinji.agentplatform.consultation.domain.ConsultationSourceLink;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ConsultationApplicationService {
    private final ConsultationStore store;
    private final ConsultationAuthorizationPort authorizationPort;
    private final PatentSearchPort patentSearchPort;
    private final ConsultationAnswerGenerator answerGenerator;

    public ConsultationApplicationService(
            ConsultationStore store,
            ConsultationAuthorizationPort authorizationPort,
            PatentSearchPort patentSearchPort,
            ConsultationAnswerGenerator answerGenerator) {
        this.store = store;
        this.authorizationPort = authorizationPort;
        this.patentSearchPort = patentSearchPort;
        this.answerGenerator = answerGenerator;
    }

    public ConsultationOutcome submit(String requester, String question, String purpose, String key) {
        String fingerprint = requester + "|" + question + "|" + purpose;
        Optional<ConsultationStore.StoredResult> previous = store.findByIdempotency(requester, key);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) throw new ConflictException();
            return previous.get().outcome();
        }
        String id = UUID.randomUUID().toString();
        ConsultationOutcome result;
        if (purpose != null && !purpose.isBlank() && !"TECHNICAL_REFERENCE".equals(purpose)) {
            result = outOfScope(id, fingerprint);
        } else if (!authorizationPort.maySearch(requester)) {
            result = denied(id, fingerprint);
        } else {
            result = search(requester, id, fingerprint, question);
        }
        store.save(requester, key, question, purpose, result);
        return result;
    }

    public ConsultationOutcome refresh(String requester, String consultationId) {
        Optional<ConsultationStore.StoredResult> stored = store.findByConsultationId(consultationId);
        if (stored.isEmpty() || !stored.get().requester().equals(requester) || !authorizationPort.maySearch(requester)) return denied(consultationId, "refresh");
        ConsultationOutcome existing = stored.get().outcome();
        ConsultationOutcome refreshed = search(requester, consultationId, existing.fingerprint(), stored.get().accumulatedInput(), existing.answerVersion() + 1);
        store.saveRefresh(requester, refreshed);
        return refreshed;
    }

    public ConsultationOutcome supplement(String requester, String consultationId, String content, String key) {
        String fingerprint = requester + "|" + consultationId + "|" + content;
        Optional<ConsultationStore.StoredResult> previous = store.findByIdempotency(requester, key);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) throw new ConflictException();
            return previous.get().outcome();
        }
        Optional<ConsultationStore.StoredResult> current = store.findByConsultationId(consultationId);
        if (current.isEmpty() || !current.get().requester().equals(requester) || !authorizationPort.maySearch(requester)) return denied(consultationId, fingerprint);
        int nextVersion = current.get().outcome().answerVersion() + 1;
        String accumulatedInput = current.get().accumulatedInput() + "\n" + content;
        ConsultationOutcome result = search(requester, consultationId, fingerprint, accumulatedInput, nextVersion);
        store.saveSupplement(requester, key, content, accumulatedInput, "", result);
        return result;
    }

    public ConsultationOutcome continueWithoutSupplement(String requester, String consultationId, String key) {
        String fingerprint = requester + "|" + consultationId + "|continue";
        Optional<ConsultationStore.StoredResult> previous = store.findByIdempotency(requester, key);
        if (previous.isPresent()) {
            if (!previous.get().fingerprint().equals(fingerprint)) throw new ConflictException();
            return previous.get().outcome();
        }
        Optional<ConsultationStore.StoredResult> current = store.findByConsultationId(consultationId);
        if (current.isEmpty() || !current.get().requester().equals(requester) || !authorizationPort.maySearch(requester)) return denied(consultationId, fingerprint);
        if (!"NEEDS_CLARIFICATION".equals(current.get().outcome().type())) throw new InvalidSupplementException();

        int nextVersion = current.get().outcome().answerVersion() + 1;
        String accumulatedInput = current.get().accumulatedInput();
        List<String> blockingIssues = blockingInformationIssues(accumulatedInput);
        ConsultationOutcome result = blockingIssues.isEmpty()
                ? searchWithOptionalContext(requester, consultationId, fingerprint, accumulatedInput, nextVersion)
                : clarification(consultationId, fingerprint, informationIssues(accumulatedInput, ""), nextVersion);
        store.saveContinue(requester, key, accumulatedInput, result);
        return result;
    }

    public ConsultationOutcome read(String requester, String consultationId) {
        Optional<ConsultationStore.StoredResult> stored = store.findByConsultationId(consultationId);
        if (stored.isEmpty() || !stored.get().requester().equals(requester) || cannotReadSources(requester, stored.get().outcome())) return denied(consultationId, "read");
        return withCurrentSources(stored.get().outcome());
    }

    public ConsultationSourceLink readSourceLink(String requester, String consultationId, String sourceLinkId) {
        Optional<ConsultationStore.StoredResult> stored = store.findByConsultationId(consultationId);
        if (stored.isEmpty() || !stored.get().requester().equals(requester)) throw new SourceLinkUnavailableException();
        ConsultationSourceLink link = stored.get().outcome().sourceLinks().stream()
                .filter(candidate -> candidate.sourceLinkId().equals(sourceLinkId))
                .findFirst()
                .orElseThrow(SourceLinkUnavailableException::new);
        if (!authorizationPort.maySearch(requester) || !patentSearchPort.isSourceAvailable(link.sourceId(), link.version())) {
            throw new SourceLinkUnavailableException();
        }
        return link;
    }
    public ConsultationOutcome readAnswer(String requester, String consultationId, String answerId) {
        Optional<ConsultationStore.StoredResult> stored=store.findByAnswerId(consultationId, answerId);
        if(stored.isEmpty()||!stored.get().requester().equals(requester)||cannotReadSources(requester, stored.get().outcome())) return denied(consultationId,"read");
        return withCurrentSources(stored.get().outcome());
    }

    private static List<String> informationIssues(String question, String purpose) {
        List<String> issues = new ArrayList<>();
        if (purpose == null || purpose.isBlank()) issues.add("检索目的");
        if (question == null || question.isBlank()) issues.add("具体技术条件");
        if (question != null && question.contains("80") && question.contains("120")) issues.add("烘干温度冲突：80 与 120");
        return issues;
    }

    private static List<String> blockingInformationIssues(String question) {
        List<String> issues = new ArrayList<>();
        if (question == null || question.isBlank()) issues.add("具体技术条件");
        if (question != null && question.contains("80") && question.contains("120")) issues.add("烘干温度冲突：80 与 120");
        return issues;
    }

    private ConsultationOutcome search(String requester, String id, String fingerprint, String confirmedInput) {
        return search(requester, id, fingerprint, confirmedInput, 1);
    }

    private ConsultationOutcome search(String requester, String id, String fingerprint, String confirmedInput, int answerVersion) {
        return search(requester, id, fingerprint, confirmedInput, answerVersion, false);
    }

    private ConsultationOutcome searchWithOptionalContext(String requester, String id, String fingerprint, String confirmedInput, int answerVersion) {
        return search(requester, id, fingerprint, confirmedInput, answerVersion, true);
    }

    private ConsultationOutcome search(String requester, String id, String fingerprint, String confirmedInput, int answerVersion, boolean skippedOptionalContext) {
        String optionalLimitation = skippedOptionalContext ? "未补充上下文，结果仅限当前技术输入和 V0 技术参考范围" : "";
        PatentSearchPort.SearchResult searchResult = patentSearchPort.search(requester, id + ":answer:" + answerVersion, confirmedInput);
        if (searchResult.type() == PatentSearchPort.SearchResult.Type.NO_RESULT) {
            return outcome(id, fingerprint, "NO_RESULT", List.of(), List.of(), "当前实际检索范围内未返回候选。", mergeLimitations(searchResult.limitation(), optionalLimitation), answerVersion);
        }
        if (searchResult.type() == PatentSearchPort.SearchResult.Type.SOURCE_UNAVAILABLE) {
            return sourceUnavailable(id, fingerprint, answerVersion);
        }
        if (searchResult.type() == PatentSearchPort.SearchResult.Type.TOOL_FAILURE) {
            return outcome(id, fingerprint, "TOOL_FAILURE", List.of(), List.of(), "检索工具当前不可用，未形成候选。", mergeLimitations(searchResult.limitation(), optionalLimitation), answerVersion);
        }
        List<ConsultationCandidate> candidates = searchResult.candidates();
        if (candidates.isEmpty()) {
            return outcome(id, fingerprint, "NO_RESULT", List.of(), List.of(), "当前实际检索范围内未返回候选。", mergeLimitations("不等于不存在相关技术", optionalLimitation), answerVersion);
        }
        String scope = searchResult.actualScope().isBlank() ? "当前已配置检索源" : searchResult.actualScope();
        ConsultationAnswerGenerator.Generation generation = answerGenerator.generate(id, confirmedInput, candidates, scope);
        String limitation = mergeLimitations(mergeLimitations(searchResult.limitation(), generation.limitation()), optionalLimitation);
        return answer(id, fingerprint, searchResult.retrievalTaskId(), candidates, generation.content(), limitation, answerVersion);
    }

    private static String mergeLimitations(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + "；" + second;
    }

    private boolean cannotReadSources(String requester, ConsultationOutcome outcome) {
        return !outcome.sourceLinks().isEmpty() && !authorizationPort.maySearch(requester);
    }

    private ConsultationOutcome withCurrentSources(ConsultationOutcome outcome) {
        if (outcome.sourceLinks().isEmpty()) return outcome;
        Set<String> availableSourceIds = outcome.sourceLinks().stream()
                .filter(link -> patentSearchPort.isSourceAvailable(link.sourceId(), link.version()))
                .map(ConsultationSourceLink::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        if (availableSourceIds.size() == outcome.sourceLinks().size()) return outcome;

        List<ConsultationCandidate> candidates = outcome.candidates().stream()
                .filter(candidate -> availableSourceIds.contains(candidate.sourceId()))
                .toList();
        List<ConsultationSourceLink> links = outcome.sourceLinks().stream()
                .filter(link -> availableSourceIds.contains(link.sourceId()))
                .toList();
        if (links.isEmpty()) {
            return new ConsultationOutcome(
                    outcome.consultationId(), outcome.answerId(), outcome.answerVersion(), outcome.retrievalTaskId(), outcome.fingerprint(),
                    "SOURCE_UNAVAILABLE", outcome.issues(), List.of(), List.of(),
                    "来源当前不可用，无法形成有依据的回答。", "不等于没有相关技术");
        }
        return new ConsultationOutcome(
                outcome.consultationId(), outcome.answerId(), outcome.answerVersion(), outcome.retrievalTaskId(), outcome.fingerprint(),
                "PARTIAL_RESULT", outcome.issues(), candidates, links, outcome.content(), "部分来源当前不可用，已从回答依据中排除。");
    }

    private static ConsultationOutcome clarification(String id, String fp, List<String> issues) { return clarification(id, fp, issues, 1); }
    private static ConsultationOutcome clarification(String id, String fp, List<String> issues, int version) { return outcome(id, fp, "NEEDS_CLARIFICATION", issues, List.of(), "请补充或确认信息。", "未执行检索", version); }
    private static ConsultationOutcome outOfScope(String id, String fp) { return new ConsultationOutcome(id, fp, "OUT_OF_SCOPE", List.of(), List.of(), "当前 V0 只支持专利技术参考。", "未调用工具"); }
    private static ConsultationOutcome denied(String id, String fp) { return new ConsultationOutcome(id, fp, "ACCESS_DENIED", List.of(), List.of(), "当前无权访问相关资料。", "未读取来源内容"); }
    private static ConsultationOutcome sourceUnavailable(String id, String fp, int version) { return outcome(id, fp, "SOURCE_UNAVAILABLE", List.of(), List.of(), "来源当前不可用，无法形成有依据的回答。", "不等于没有相关技术", version); }
    private static ConsultationOutcome answer(String id, String fp, String taskId, List<ConsultationCandidate> c, String content, String limitation, int version) { return outcome(id, fp, taskId, limitation.isBlank() ? "ANSWER_READY" : "PARTIAL_RESULT", List.of(), List.copyOf(c), content, limitation, version); }
    private static ConsultationOutcome outcome(String id, String fp, String type, List<String> issues, List<ConsultationCandidate> candidates, String content, String limitation, int version) {
        return outcome(id, fp, "", type, issues, candidates, content, limitation, version);
    }
    private static ConsultationOutcome outcome(String id, String fp, String taskId, String type, List<String> issues, List<ConsultationCandidate> candidates, String content, String limitation, int version) {
        List<ConsultationSourceLink> links = candidates.stream().map(c -> new ConsultationSourceLink(UUID.randomUUID().toString(), c.sourceId(), c.version(), c.location())).toList();
        return new ConsultationOutcome(id, UUID.randomUUID().toString(), version, taskId, fp, type, issues, candidates, links, content, limitation);
    }
    public static class ConflictException extends RuntimeException {}
    public static class InvalidSupplementException extends RuntimeException {}
    public static class SourceLinkUnavailableException extends RuntimeException {}
}
