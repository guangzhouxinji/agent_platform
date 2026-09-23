package cn.xinji.agentplatform.consultation.patentassistant.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantResult;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentSearchResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PatentAssistantService {
    private static final Pattern CITATION = Pattern.compile("\\[P(\\d+)]");
    private static final Pattern PUBLICATION_NUMBER = Pattern.compile("[A-Z0-9-]{4,40}");
    private static final String LIMITATION = "仅基于当前公开试点摘要生成技术参考；未检索全文，未做专业相关性校准，不构成新颖性、侵权、有效性或授权结论。";
    private final ConsultationAuthorizationPort authorization;
    private final PatentAssistantSources sources;
    private final PatentAnalysisPort analysis;

    public PatentAssistantService(ConsultationAuthorizationPort authorization,
                                  PatentAssistantSources sources,
                                  PatentAnalysisPort analysis) {
        this.authorization = authorization;
        this.sources = sources;
        this.analysis = analysis;
    }

    public PatentAssistantResult analyze(String requester, String question) {
        if (!authorization.maySearch(requester)) throw new AccessDeniedException();
        String normalized = normalize(question);
        PatentAssistantSourcePort analysisSource = sources.analysisSource();
        PatentAssistantSourcePort.SourceStatus sourceStatus = analysisSource.status();
        if (!sourceStatus.externalModelUseAllowed()) throw new ExternalModelUseNotAllowedException();
        String traceId = UUID.randomUUID().toString();
        PatentAssistantSourcePort.SearchPage search = analysisSource.search(
                new PatentAssistantSourcePort.SearchRequest(normalized, 1, 5, null));
        if (search.sources().isEmpty()) throw new NoCandidateException();
        PatentAnalysisPort.ModelStatus model = analysis.status();
        if (!model.configured()) throw new ModelUnavailableException("MODEL_NOT_CONFIGURED");
        String content = analysis.analyze(normalized, search.sources(), traceId);
        validateCitations(content, search.sources().size());
        return new PatentAssistantResult(normalized, search.searchTerms(), content, search.sources(),
                sourceStatus.dataMode(), sourceStatus.dataVersion(), model.model(), traceId, LIMITATION);
    }

    public PatentSearchResult search(String requester, String question, Integer pageValue,
                                     Integer pageSizeValue, String countryValue) {
        if (!authorization.maySearch(requester)) throw new AccessDeniedException();
        String normalized = normalize(question);
        int page = pageValue == null ? 1 : pageValue;
        int pageSize = pageSizeValue == null ? 10 : pageSizeValue;
        if (page < 1 || page > 100) throw new InvalidRequestException("页码应为 1 到 100");
        if (pageSize != 5 && pageSize != 10) throw new InvalidRequestException("每页数量只允许 5 或 10");
        String country = normalizeCountry(countryValue);
        String traceId = UUID.randomUUID().toString();
        PatentAssistantSourcePort.SearchPage search = sources.searchSource().search(
                new PatentAssistantSourcePort.SearchRequest(normalized, page, pageSize, country));
        PatentAssistantSourcePort.SourceStatus status = sources.searchSource().status();
        return new PatentSearchResult(normalized, search.searchTerms(), search.sources(), status.provider(),
                status.dataMode(), status.dataVersion(), status.externalModelUseAllowed(), traceId,
                search.page(), search.pageSize(), search.totalHits(), search.hasNext(), country,
                "公开源候选仅供技术检索参考；非中心权威数据，不代表检索完备性、专业相关性或法律结论。");
    }

    public PatentAssistantSourcePort.SourceDetail detail(String requester, String publicationNumberValue) {
        if (!authorization.maySearch(requester)) throw new AccessDeniedException();
        String publicationNumber = publicationNumberValue == null ? "" : publicationNumberValue.trim().toUpperCase();
        if (!PUBLICATION_NUMBER.matcher(publicationNumber).matches()) {
            throw new InvalidRequestException("公开号格式无效");
        }
        return sources.searchSource().detail(publicationNumber);
    }

    public AssistantStatus status() {
        PatentAssistantSourcePort.SourceStatus sourceStatus = sources.analysisSource().status();
        PatentAssistantSourcePort.SourceStatus searchStatus = sources.searchSource().status();
        PatentAnalysisPort.ModelStatus modelStatus = analysis.status();
        return new AssistantStatus(sourceStatus.provider(), sourceStatus.dataMode(), sourceStatus.dataVersion(),
                sourceStatus.sourceCount(), sourceStatus.externalModelUseAllowed(), sourceStatus.authorityStatus(),
                sourceStatus.usageDecision(), modelStatus.provider(), modelStatus.model(), modelStatus.configured(),
                searchStatus.provider(), searchStatus.dataMode(), searchStatus.dataVersion(), searchStatus.sourceCount(),
                searchStatus.externalModelUseAllowed(), searchStatus.authorityStatus(), searchStatus.usageDecision(),
                "只允许提交公开或已脱敏的技术信息；不得提交中心数据、案件材料或个人信息。");
    }

    private static String normalize(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.length() < 4 || normalized.length() > 2000) {
            throw new InvalidRequestException("技术描述长度应为 4 到 2000 个字符");
        }
        return normalized;
    }

    private static String normalizeCountry(String country) {
        if (country == null || country.isBlank()) return null;
        String normalized = country.trim().toUpperCase();
        if (!Set.of("CN", "US").contains(normalized)) {
            throw new InvalidRequestException("国家筛选只允许 CN 或 US");
        }
        return normalized;
    }

    private static void validateCitations(String content, int sourceCount) {
        if (content == null || content.isBlank()) throw new ModelUnavailableException("EMPTY_MODEL_RESPONSE");
        Matcher matcher = CITATION.matcher(content);
        Set<Integer> citations = new HashSet<>();
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value < 1 || value > sourceCount) throw new InvalidModelOutputException();
            citations.add(value);
        }
        if (citations.isEmpty()) throw new InvalidModelOutputException();
    }

    public record AssistantStatus(String sourceProvider, String dataMode, String dataVersion, Integer sourceCount,
                                  boolean externalModelUseAllowed, String authorityStatus, String usageDecision,
                                  String provider, String model, boolean modelConfigured,
                                  String searchSourceProvider, String searchDataMode, String searchDataVersion,
                                  Integer searchSourceCount, boolean searchExternalModelUseAllowed,
                                  String searchAuthorityStatus, String searchUsageDecision, String warning) {}
    public static class InvalidRequestException extends RuntimeException { public InvalidRequestException(String message) { super(message); } }
    public static class AccessDeniedException extends RuntimeException {}
    public static class NoCandidateException extends RuntimeException {}
    public static class ModelUnavailableException extends RuntimeException { public ModelUnavailableException(String message) { super(message); } }
    public static class InvalidModelOutputException extends RuntimeException {}
    public static class ExternalModelUseNotAllowedException extends RuntimeException {}
}
