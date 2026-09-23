package cn.xinji.agentplatform.consultation.agentconfig.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.agentconfig.application.ConsultationAgentModelRegistry.RegisteredModel;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ConsultationAgentDraftService {
    public static final String AGENT_ID = "CONSULTATION-AGENT-001";
    public static final Set<String> SWITCHABLE_FAILURES = Set.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE");
    private static final List<Option> SCENARIOS = List.of(
            option("PATENT", "专利咨询"), option("TRADEMARK", "商标咨询"), option("CASE_REFERENCE", "案例参考"));
    private static final List<Option> TOOLS = List.of(
            option("PATENT_INFORMATION", "专利资料查询"), option("PATENT_SIMILAR_SEARCH", "专利相似检索"),
            option("PATENT_COMPARISON", "专利辅助比对"), option("TRADEMARK_INFORMATION", "商标资料查询"),
            option("TRADEMARK_SIMILAR_SEARCH", "商标相似检索"), option("TRADEMARK_COMPARISON", "商标辅助比对"),
            option("CASE_REFERENCE", "案例检索"));

    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;
    private final ConsultationAgentModelRegistry modelRegistry;
    private final AtomicReference<AgentDraft> draft;

    public ConsultationAgentDraftService(
            ConsultationAuthorizationPort authorization,
            Clock clock,
            ConsultationAgentModelRegistry modelRegistry) {
        this.authorization = authorization;
        this.clock = clock;
        this.modelRegistry = modelRegistry;
        List<ModelOption> initialModels = models();
        ModelOption defaultModel = initialModels.stream().filter(ModelOption::configured).findFirst().orElse(initialModels.getFirst());
        this.draft = new AtomicReference<>(new AgentDraft(AGENT_ID, "知识产权咨询助手", "开发期配置草稿",
                List.of("PATENT", "TRADEMARK", "CASE_REFERENCE"), List.of(defaultModel.modelId()),
                List.of("TIMEOUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE"),
                TOOLS.stream().map(Option::id).toList(), 1, "DEVELOPMENT_DRAFT", "初始开发草稿", clock.instant()));
    }

    public Catalog catalog(String requester) {
        authorize(requester);
        return new Catalog(models(), SCENARIOS, TOOLS, SWITCHABLE_FAILURES.stream().sorted().toList(),
                "目录只包含后端允许引用的配置，不返回端点或密钥。新增模型须由运维加入后端目录。", clock.instant());
    }

    public AgentDraft get(String requester) {
        authorize(requester);
        return draft.get();
    }

    public AgentDraft save(String requester, SaveDraft command) {
        authorize(requester);
        if (!AGENT_ID.equals(command.agentId())) throw error("IMMUTABLE_AGENT_ID", "智能体编号不可修改", HttpStatus.BAD_REQUEST);
        AgentDraft current = draft.get();
        if (command.expectedVersion() != current.version()) throw error("VERSION_CONFLICT", "草稿已被更新，请刷新后重试", HttpStatus.CONFLICT);
        List<Issue> issues = structuralIssues(command);
        if (!issues.isEmpty()) throw new DraftException("INVALID_DRAFT", "草稿配置无效", HttpStatus.BAD_REQUEST, issues);
        AgentDraft updated = new AgentDraft(AGENT_ID, required(command.name(), "名称"), required(command.description(), "描述"),
                List.copyOf(command.scenarioIds()), List.copyOf(command.modelChain()), List.copyOf(command.switchOn()),
                List.copyOf(command.toolIds()), current.version() + 1, "DEVELOPMENT_DRAFT",
                required(command.changeNote(), "变更说明"), clock.instant());
        if (!draft.compareAndSet(current, updated)) throw error("VERSION_CONFLICT", "草稿已被更新，请刷新后重试", HttpStatus.CONFLICT);
        return updated;
    }

    public ValidationResult validate(String requester) {
        authorize(requester);
        AgentDraft value = draft.get();
        List<Issue> issues = new ArrayList<>();
        for (String modelId : value.modelChain()) {
            models().stream().filter(item -> item.modelId().equals(modelId)).findFirst()
                    .filter(item -> !item.configured())
                    .ifPresent(item -> issues.add(new Issue("MODEL_NOT_CONFIGURED", item.displayName() + " 尚未配置密钥，不能试运行")));
        }
        issues.add(new Issue("FORMAL_DEPENDENCIES_PENDING", "正式提示词、意图、对话流版本及发布权限尚未确认"));
        return new ValidationResult(false, value.version(), issues,
                "仅完成开发草稿结构校验；不会发布或改变当前咨询服务。", clock.instant());
    }

    private List<Issue> structuralIssues(SaveDraft command) {
        List<Issue> issues = new ArrayList<>();
        if (blank(command.name())) issues.add(new Issue("NAME_REQUIRED", "名称不能为空"));
        if (blank(command.description())) issues.add(new Issue("DESCRIPTION_REQUIRED", "描述不能为空"));
        if (blank(command.changeNote())) issues.add(new Issue("CHANGE_NOTE_REQUIRED", "变更说明不能为空"));
        checkSelection(command.scenarioIds(), SCENARIOS, "SCENARIO_REQUIRED", "UNKNOWN_SCENARIO", issues);
        checkSelection(command.toolIds(), TOOLS, "TOOL_REQUIRED", "UNKNOWN_TOOL", issues);
        if (command.modelChain() == null || command.modelChain().isEmpty()) issues.add(new Issue("MODEL_REQUIRED", "至少选择一个主模型"));
        else {
            Set<String> unique = new LinkedHashSet<>(command.modelChain());
            if (unique.size() != command.modelChain().size()) issues.add(new Issue("DUPLICATE_MODEL", "模型故障链不能包含重复模型"));
            Set<String> known = models().stream().map(ModelOption::modelId).collect(java.util.stream.Collectors.toSet());
            for (String value : unique) if (!known.contains(value)) issues.add(new Issue("UNKNOWN_MODEL", "模型不在后端批准目录中：" + value));
        }
        if (command.switchOn() == null || command.switchOn().isEmpty()) issues.add(new Issue("SWITCH_CONDITION_REQUIRED", "至少选择一个技术故障切换条件"));
        else for (String value : command.switchOn()) if (!SWITCHABLE_FAILURES.contains(value)) issues.add(new Issue("UNSAFE_SWITCH_CONDITION", "该错误不得触发模型切换：" + value));
        return issues;
    }

    private static void checkSelection(List<String> values, List<Option> catalog, String emptyCode, String unknownCode, List<Issue> issues) {
        if (values == null || values.isEmpty()) { issues.add(new Issue(emptyCode, "至少选择一项")); return; }
        Set<String> known = catalog.stream().map(Option::id).collect(java.util.stream.Collectors.toSet());
        for (String value : values) if (!known.contains(value)) issues.add(new Issue(unknownCode, "引用不在后端目录中：" + value));
    }

    private void authorize(String requester) {
        if (!authorization.maySearch(requester)) throw error("ACCESS_DENIED", "当前开发身份无权维护智能体草稿", HttpStatus.FORBIDDEN);
    }

    private List<ModelOption> models() {
        return modelRegistry.models().stream().map(ConsultationAgentDraftService::modelOption).toList();
    }

    private static ModelOption modelOption(RegisteredModel model) {
        return new ModelOption(model.modelId(), model.provider(), model.modelName(), model.displayName(),
                model.enabled(), model.configured(), model.dataChannel(), model.availability());
    }

    private static Option option(String id, String label) { return new Option(id, label); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String required(String value, String label) { if (blank(value)) throw new IllegalArgumentException(label + "不能为空"); return value.trim(); }
    private static DraftException error(String code, String message, HttpStatus status) { return new DraftException(code, message, status, List.of()); }

    public record Option(String id, String label) {}
    public record ModelOption(String modelId, String provider, String modelName, String displayName,
                              boolean enabled, boolean configured, String dataChannel, String availability) {}
    public record Catalog(List<ModelOption> models, List<Option> scenarios, List<Option> tools, List<String> switchableFailures, String limitation, Instant generatedAt) {}
    public record AgentDraft(String agentId, String name, String description, List<String> scenarioIds, List<String> modelChain,
                             List<String> switchOn, List<String> toolIds, long version, String status, String changeNote, Instant updatedAt) {}
    public record SaveDraft(String agentId, String name, String description, List<String> scenarioIds, List<String> modelChain,
                            List<String> switchOn, List<String> toolIds, long expectedVersion, String changeNote) {}
    public record Issue(String code, String message) {}
    public record ValidationResult(boolean publishReady, long draftVersion, List<Issue> issues, String limitation, Instant validatedAt) {}

    public static final class DraftException extends RuntimeException {
        private final String code; private final HttpStatus status; private final List<Issue> issues;
        public DraftException(String code, String message, HttpStatus status, List<Issue> issues) { super(message); this.code = code; this.status = status; this.issues = issues; }
        public String code() { return code; } public HttpStatus status() { return status; } public List<Issue> issues() { return issues; }
    }
}
