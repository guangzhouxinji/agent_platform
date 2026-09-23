package cn.xinji.agentplatform.consultation.guidance.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.guidance.domain.ConsultationGuidance;
import cn.xinji.agentplatform.consultation.guidance.domain.ConsultationGuidanceResult;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ConsultationGuidanceService {
    public static final String VERSION = "LOCAL-GUIDANCE-V1";
    private static final String LANGUAGE = "zh-CN";
    private static final String STATUS = "DEVELOPMENT_DRAFT";
    private static final String AUDIENCE = "LOCAL_AUTHORIZED_CONSULTATION_USER";
    private static final String LIMITATION = "开发期固定草稿，未经中心内容评审，不是正式发布提示词；选择后只填入普通咨询问题，不自动执行工具或管理动作。";
    private static final List<ConsultationGuidance> GUIDES = List.of(
            guide("LG-PATENT-INFO-01", "查询专利基本信息", "查询 CN116234567A 的申请人、发明人和当前状态", "PATENT_INFORMATION", 10),
            guide("LG-PATENT-SEARCH-01", "检索相似专利", "检索与水性粘结剂、双区烘干和在线测厚反馈相关的中国专利，用于技术参考", "PATENT_SIMILAR_SEARCH", 20),
            guide("LG-PATENT-COMPARE-01", "对照两项专利技术", "对比 CN116234567A 与 CN115987654A 的核心技术特征，用于技术参考", "PATENT_COMPARISON", 30),
            guide("LG-TRADEMARK-INFO-01", "查询商标基本信息", "查询注册号 12345678 的注册人、核定类别和当前状态", "TRADEMARK_INFORMATION", 40),
            guide("LG-TRADEMARK-SEARCH-01", "检索近似商标", "检索第9类中与“星河智造”文字近似的商标，并列出当前状态", "TRADEMARK_SIMILAR_SEARCH", 50),
            guide("LG-TRADEMARK-COMPARE-01", "对照两个商标", "对比“星河智造”与“星河制造”在文字、读音、含义和第9类商品服务方面的差异", "TRADEMARK_COMPARISON", 60),
            guide("LG-CASE-REFERENCE-01", "查找相似案例", "查找商标侵权二审中涉及近似标识判断的可参考案例", "CASE_REFERENCE", 70));
    private static final Set<String> SCENARIOS = GUIDES.stream().map(ConsultationGuidance::scenarioId).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public ConsultationGuidanceService(ConsultationAuthorizationPort authorization, Clock clock) {
        this.authorization = authorization;
        this.clock = clock;
    }

    public ConsultationGuidanceResult query(String requester, String scenarioId, String language, int limit) {
        if (!authorization.maySearch(requester)) {
            throw new GuidanceException("ACCESS_DENIED", "当前身份无权读取咨询问题引导", HttpStatus.FORBIDDEN);
        }
        String selectedLanguage = language == null || language.isBlank() ? LANGUAGE : language.trim();
        if (!LANGUAGE.equals(selectedLanguage)) {
            throw new GuidanceException("UNSUPPORTED_LANGUAGE", "当前开发版本只提供 zh-CN 引导", HttpStatus.BAD_REQUEST);
        }
        if (limit < 1 || limit > 10) {
            throw new GuidanceException("INVALID_LIMIT", "展示条数必须在 1 至 10 之间", HttpStatus.BAD_REQUEST);
        }
        String selectedScenario = scenarioId == null || scenarioId.isBlank() ? null : scenarioId.trim();
        if (selectedScenario != null && !SCENARIOS.contains(selectedScenario)) {
            throw new GuidanceException("UNKNOWN_SCENARIO", "未知的咨询场景", HttpStatus.BAD_REQUEST);
        }
        List<ConsultationGuidance> guides = GUIDES.stream()
                .filter(value -> selectedScenario == null || selectedScenario.equals(value.scenarioId()))
                .limit(limit)
                .toList();
        return new ConsultationGuidanceResult(VERSION, STATUS, selectedScenario, selectedLanguage, guides, LIMITATION, clock.instant());
    }

    private static ConsultationGuidance guide(String id, String title, String question, String scenario, int order) {
        return new ConsultationGuidance(id, title, question, scenario, List.of(scenario), LANGUAGE, AUDIENCE, order, STATUS, VERSION);
    }

    public static final class GuidanceException extends RuntimeException {
        private final String code;
        private final HttpStatus status;

        public GuidanceException(String code, String message, HttpStatus status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() { return code; }
        public HttpStatus status() { return status; }
    }
}
