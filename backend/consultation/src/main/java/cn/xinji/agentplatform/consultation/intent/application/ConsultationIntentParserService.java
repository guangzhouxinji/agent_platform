package cn.xinji.agentplatform.consultation.intent.application;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseCommand;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseCommand.ContextTurn;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult.ExtractedSlot;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult.IntentCandidate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ConsultationIntentParserService {
    public static final String VERSION = "LOCAL-INTENT-V1";
    private static final String LIMITATION = "开发期固定版本解析器；结果是路由建议，不执行工具、不扩大权限，未经过冻结标注集评测，不代表识别准确率。";
    private static final Pattern PATENT_NUMBER = Pattern.compile("(?i)\\bCN\\s?[0-9]{6,}(?:\\.[0-9])?[A-Z]?[0-9]?\\b");
    private static final Pattern REGISTRATION_NUMBER = Pattern.compile("(?:注册号|申请号)[为是：:\\s]*([0-9]{5,})");
    private static final Pattern CATEGORY = Pattern.compile("第?\\s*([0-9]{1,2})\\s*类");
    private static final Pattern QUOTED = Pattern.compile("[“\"]([^”\"]{2,30})[”\"]");
    private static final Pattern BARE_TRADEMARK_SUBJECT = Pattern.compile("(?:检索|查询|搜索)\\s*([^，。；;]{2,30}?)(?:商标|品牌)");
    private static final Pattern PATENT_SEARCH_INPUT = Pattern.compile(
            "(?:检索|查找|搜索)(?:与)?([^，。；;]{2,80}?)(?:相关|相似|类似)?(?:的)?(?:中国)?专利");
    private static final Pattern PATENT_TOPIC_QUESTION = Pattern.compile("^(.{4,100}?)(?:有几种|有哪些|是什么|如何(?:实现|判断|检索)?|怎么(?:实现|判断|检索)?)\\??$");
    private static final Set<String> GENERIC_SEARCH_INPUTS = Set.of("相关", "相似", "类似", "有关", "一些", "几个");
    private static final Set<String> IP_TERMS = Set.of("知识产权", "商标", "专利", "著作权", "版权", "技术秘密", "侵权", "注册", "授权", "案例", "法规");
    private static final Set<String> TECHNICAL_TOPIC_TERMS = Set.of("材料", "粘结剂", "涂布", "烘干", "电池", "工艺", "装置", "方法", "制备", "配方", "技术", "系统", "应用");

    private final ConsultationAuthorizationPort authorization;
    private final Clock clock;

    public ConsultationIntentParserService(ConsultationAuthorizationPort authorization, Clock clock) {
        this.authorization = authorization;
        this.clock = clock;
    }

    public IntentParseResult parse(String requester, IntentParseCommand command) {
        if (!authorization.maySearch(requester)) {
            throw new IntentException("ACCESS_DENIED", "当前身份无权使用咨询意图解析", HttpStatus.FORBIDDEN);
        }
        List<String> clauses = split(command.question());
        List<IntentCandidate> candidates = new ArrayList<>();
        int order = 1;
        for (String clause : clauses) {
            IntentCandidate candidate = classify(clause, command.context(), order);
            if (!"UNKNOWN".equals(candidate.intentId())) candidates.add(candidate);
            order++;
        }
        if (candidates.isEmpty()) {
            candidates = List.of(unknown(command.question(), 1));
        } else {
            candidates = deduplicate(candidates);
        }

        boolean unknown = candidates.size() == 1 && "UNKNOWN".equals(candidates.getFirst().intentId());
        boolean unavailable = candidates.stream().anyMatch(value -> "UNAVAILABLE".equals(value.routeState()));
        boolean missing = candidates.stream().anyMatch(value -> !value.missingSlots().isEmpty());
        String status = unknown ? "UNKNOWN" : unavailable ? "ROUTE_UNAVAILABLE" : missing ? "NEEDS_CLARIFICATION"
                : candidates.size() > 1 ? "MULTI_INTENT" : "RESOLVED";
        List<String> questions = candidates.stream().flatMap(value -> value.missingSlots().stream()
                .map(slot -> clarification(value.label(), slot))).distinct().toList();
        return new IntentParseResult(status, VERSION, command.question(), unknown ? null : candidates.getFirst().intentId(),
                candidates, questions, LIMITATION, UUID.randomUUID().toString(), clock.instant());
    }

    private static IntentCandidate classify(String clause, List<ContextTurn> context, int order) {
        String text = clause.trim();
        List<ExtractedSlot> slots = extractSlots(text, 0);
        for (int index = context.size() - 1; index >= 0; index--) {
            ContextTurn turn = context.get(index);
            mergeMissing(slots, extractSlots(turn.content(), turn.round()));
        }
        if (isBareTechnicalTopic(text)) {
            slots.add(new ExtractedSlot("technicalInput", text, 0, "USER_EXPLICIT"));
            slots.add(new ExtractedSlot("analysisPurpose", "TECHNICAL_REFERENCE", 0, "DERIVED_FROM_TECHNICAL_TOPIC"));
        }
        enrichSemanticSlots(slots);
        boolean trademark = containsAny(text, "商标", "品牌", "标识");
        boolean patent = containsAny(text, "专利", "发明", "实用新型", "技术方案", "权利要求") || slots.stream()
                .anyMatch(slot -> slot.name().equals("patentNumber") || slot.name().equals("technicalInput"));
        boolean caseReference = containsAny(text, "案例", "判决", "裁判", "判例", "参考案件");
        boolean compare = containsAny(text, "比对", "对比", "是否近似", "是否相同", "落入保护范围")
                || text.contains("判断") && count(slots, "patentNumber") + count(slots, "namedSubject") >= 2;
        boolean search = containsAny(text, "相似", "近似", "检索", "查重", "类似", "相关专利") || isBareTechnicalTopic(text);
        boolean information = containsAny(text, "查询", "查一下", "状态", "有效期", "申请人", "注册人", "发明人", "信息");
        boolean lawOrProcess = containsAny(text, "法规", "法律规定", "办理", "流程", "需要什么材料", "申请手续");

        if (caseReference) return candidate("CASE_REFERENCE", "案例参考", "CASE", order, signals(text, "案例", "判决", "裁判", "侵权"),
                slots, concreteCaseQuestion(text) || !slots.isEmpty() ? List.of() : List.of("specificIssueOrFacts"), "/search/cases", "AVAILABLE", "问题明确请求案例或裁判参考");
        if (lawOrProcess) return candidate("LAW_PROCESS_CONSULTATION", "办理/法规咨询", "CONSULTATION", order,
                signals(text, "法规", "法律规定", "办理", "流程"), slots, List.of(), null, "UNAVAILABLE", "当前没有已授权的办事指南或法规工具");
        if (trademark && compare) return candidate("TRADEMARK_COMPARISON", "商标辅助比对", "TRADEMARK", order,
                signals(text, "商标", "品牌", "比对", "判断", "近似"), slots, missing(slots, "targetTrademark", "candidateTrademark", "goodsServices"), "/compare/trademarks", "AVAILABLE", "同时包含商标对象和比对诉求");
        if (patent && compare) return candidate("PATENT_COMPARISON", "专利辅助比对", "PATENT", order,
                signals(text, "专利", "技术方案", "比对", "判断", "保护范围"), slots, missing(slots, "targetPatent", "candidatePatent", "analysisPurpose"), "/compare/patents", "AVAILABLE", "同时包含专利对象和比对诉求");
        if (trademark && search) return candidate("TRADEMARK_SIMILAR_SEARCH", "商标相似检索", "TRADEMARK", order,
                signals(text, "商标", "品牌", "相似", "近似", "检索"), slots, missing(slots, "trademarkName", "categoryOrGoodsServices"), "/search/trademarks", "AVAILABLE", "同时包含商标对象和相似检索诉求");
        if (patent && search) return candidate("PATENT_SIMILAR_SEARCH", "专利相似检索", "PATENT", order,
                signals(text, "专利", "技术方案", "相似", "检索", "查重"), slots, patentSearchMissing(slots, text), "/search/patents", "AVAILABLE", "同时包含专利对象和相似检索诉求");
        if (trademark && (information || has(slots, "registrationNumber"))) return candidate("TRADEMARK_INFORMATION", "商标信息查询", "TRADEMARK", order,
                signals(text, "商标", "品牌", "查询", "状态", "注册号"), slots, has(slots, "registrationNumber") || text.length() > 8 ? List.of() : List.of("registrationNumberOrCondition"), "/libraries/trademarks", "AVAILABLE", "商标对象包含查询诉求或注册号形态");
        if (patent && (information || has(slots, "patentNumber"))) return candidate("PATENT_INFORMATION", "专利信息查询", "PATENT", order,
                signals(text, "专利", "查询", "状态", "申请人", "发明人"), slots, has(slots, "patentNumber") || text.length() > 8 ? List.of() : List.of("patentNumberOrCondition"), "/libraries/patents", "AVAILABLE", "专利对象包含查询诉求或专利号形态");
        if ((trademark || patent) && !information && !search && !compare) {
            return candidate(trademark ? "TRADEMARK_UNCLEAR" : "PATENT_UNCLEAR", trademark ? "商标咨询待澄清" : "专利咨询待澄清",
                    trademark ? "TRADEMARK" : "PATENT", order, signals(text, "商标", "专利", "品牌", "技术方案"), slots,
                    List.of("consultationPurpose"), null, "NOT_READY", "已识别知识产权对象，但未明确查询、检索、比对或案例诉求");
        }
        return unknown(text, order);
    }

    private static IntentCandidate unknown(String text, int order) {
        String reason = IP_TERMS.stream().anyMatch(text::contains) ? "当前固定分类无法确定具体诉求" : "输入未显示可识别的知识产权诉求";
        return candidate("UNKNOWN", "无法识别/超出范围", "UNKNOWN", order, List.of(), List.of(), List.of(), null, "NOT_READY", reason);
    }

    private static List<String> split(String question) {
        List<String> values = Pattern.compile("(?:同时|另外|并且|以及|；|;|，\\s*并(?=(?:找|检索|查询|比对|对比|判断)))").splitAsStream(question)
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        return values.isEmpty() ? List.of(question) : values;
    }

    private static List<IntentCandidate> deduplicate(List<IntentCandidate> values) {
        Map<String, IntentCandidate> unique = new LinkedHashMap<>();
        for (IntentCandidate value : values) unique.putIfAbsent(value.intentId(), value);
        int[] order = {1};
        return unique.values().stream().map(value -> new IntentCandidate(value.intentId(), value.label(), value.category(), order[0]++,
                value.matchedSignals(), value.slots(), value.missingSlots(), value.plannedRoute(), value.routeState(), value.reason())).toList();
    }

    private static List<ExtractedSlot> extractSlots(String text, int round) {
        List<ExtractedSlot> slots = new ArrayList<>();
        match(PATENT_NUMBER, text, "patentNumber", round, slots);
        match(REGISTRATION_NUMBER, text, "registrationNumber", round, slots);
        match(CATEGORY, text, "category", round, slots);
        matchPatentSearchInput(text, round, slots);
        matchPatentTopicQuestion(text, round, slots);
        Matcher quoted = QUOTED.matcher(text);
        while (quoted.find()) slots.add(new ExtractedSlot("namedSubject", quoted.group(1), round, "USER_EXPLICIT"));
        matchBareTrademarkSubject(text, round, slots);
        if (containsAny(text, "新颖性", "侵权风险", "技术参考")) {
            String purpose = text.contains("新颖性") ? "NOVELTY_PREPARATION" : text.contains("侵权风险") ? "INFRINGEMENT_PREPARATION" : "TECHNICAL_REFERENCE";
            slots.add(new ExtractedSlot("analysisPurpose", purpose, round, "USER_EXPLICIT"));
        }
        return slots;
    }

    private static void match(Pattern pattern, String text, String name, int round, List<ExtractedSlot> slots) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) slots.add(new ExtractedSlot(name, matcher.group(matcher.groupCount() == 0 ? 0 : 1).replace(" ", ""), round, "USER_EXPLICIT"));
    }

    private static void matchPatentSearchInput(String text, int round, List<ExtractedSlot> slots) {
        Matcher matcher = PATENT_SEARCH_INPUT.matcher(text);
        if (!matcher.find()) return;
        String value = matcher.group(1).trim().replaceFirst("^(?:一下|有关|关于)", "");
        if (!value.isBlank() && !GENERIC_SEARCH_INPUTS.contains(value) && !containsAny(value, "帮我")) {
            slots.add(new ExtractedSlot("technicalInput", value, round, "USER_EXPLICIT"));
        }
    }

    private static void matchPatentTopicQuestion(String text, int round, List<ExtractedSlot> slots) {
        if (!containsAny(text, "专利", "发明", "技术方案") || !containsAny(text, "相似", "检索", "查重", "相关专利")) return;
        Matcher matcher = PATENT_TOPIC_QUESTION.matcher(text.trim());
        if (!matcher.matches()) return;
        slots.add(new ExtractedSlot("technicalInput", matcher.group(1).trim(), round, "USER_EXPLICIT"));
        slots.add(new ExtractedSlot("analysisPurpose", "TECHNICAL_REFERENCE", round, "DERIVED_FROM_QUESTION"));
    }

    private static void matchBareTrademarkSubject(String text, int round, List<ExtractedSlot> slots) {
        Matcher matcher = BARE_TRADEMARK_SUBJECT.matcher(text);
        if (!matcher.find()) return;
        String value = matcher.group(1).trim().replaceFirst("^(?:一下|有关|关于)", "");
        if (!value.isBlank() && !GENERIC_SEARCH_INPUTS.contains(value) && !containsAny(value, "相似", "近似", "查询")) {
            slots.add(new ExtractedSlot("namedSubject", value, round, "USER_EXPLICIT"));
        }
    }

    private static void mergeMissing(List<ExtractedSlot> target, List<ExtractedSlot> context) {
        Set<String> names = target.stream().map(ExtractedSlot::name).collect(java.util.stream.Collectors.toSet());
        for (ExtractedSlot slot : context) if (names.add(slot.name())) target.add(slot);
    }

    private static void enrichSemanticSlots(List<ExtractedSlot> slots) {
        List<ExtractedSlot> subjects = slots.stream().filter(slot -> slot.name().equals("namedSubject")).toList();
        if (!subjects.isEmpty()) slots.add(new ExtractedSlot("trademarkName", subjects.getFirst().value(), subjects.getFirst().sourceRound(), "USER_EXPLICIT"));
        if (subjects.size() > 1) {
            slots.add(new ExtractedSlot("targetTrademark", subjects.get(0).value(), subjects.get(0).sourceRound(), "USER_EXPLICIT"));
            slots.add(new ExtractedSlot("candidateTrademark", subjects.get(1).value(), subjects.get(1).sourceRound(), "USER_EXPLICIT"));
        }
        List<ExtractedSlot> patents = slots.stream().filter(slot -> slot.name().equals("patentNumber")).toList();
        if (patents.size() > 1) {
            slots.add(new ExtractedSlot("targetPatent", patents.get(0).value(), patents.get(0).sourceRound(), "USER_EXPLICIT"));
            slots.add(new ExtractedSlot("candidatePatent", patents.get(1).value(), patents.get(1).sourceRound(), "USER_EXPLICIT"));
        }
        slots.stream().filter(slot -> slot.name().equals("category")).findFirst().ifPresent(slot ->
                slots.add(new ExtractedSlot("categoryOrGoodsServices", slot.value(), slot.sourceRound(), "USER_EXPLICIT")));
    }

    private static List<String> patentSearchMissing(List<ExtractedSlot> slots, String text) {
        List<String> missing = new ArrayList<>();
        if (!has(slots, "patentNumber") && !has(slots, "technicalInput")
                && !containsAny(text, "技术方案", "技术特征", "权利要求", "发明")) missing.add("technicalInputOrPatentNumber");
        if (!has(slots, "analysisPurpose")) missing.add("searchPurpose");
        return missing;
    }

    private static List<String> missing(List<ExtractedSlot> slots, String... names) {
        List<String> missing = new ArrayList<>();
        for (String name : names) if (!has(slots, name)) missing.add(name);
        return missing;
    }

    private static boolean has(List<ExtractedSlot> slots, String name) { return slots.stream().anyMatch(slot -> slot.name().equals(name)); }
    private static long count(List<ExtractedSlot> slots, String name) { return slots.stream().filter(slot -> slot.name().equals(name)).count(); }
    private static boolean concreteCaseQuestion(String text) { return text.length() >= 10 && containsAny(text, "侵权", "注册", "无效", "许可", "合同", "赔偿", "争议", "纠纷", "技术秘密"); }
    private static boolean isBareTechnicalTopic(String text) {
        if (text.length() < 4 || text.length() > 100 || IP_TERMS.stream().anyMatch(text::contains)) return false;
        return TECHNICAL_TOPIC_TERMS.stream().anyMatch(text::contains);
    }
    private static boolean containsAny(String text, String... values) { for (String value : values) if (text.contains(value)) return true; return false; }
    private static List<String> signals(String text, String... values) { LinkedHashSet<String> result = new LinkedHashSet<>(); for (String value : values) if (text.contains(value)) result.add(value); return List.copyOf(result); }
    private static IntentCandidate candidate(String id, String label, String category, int order, List<String> signals,
                                             List<ExtractedSlot> slots, List<String> missing, String route, String routeState, String reason) {
        return new IntentCandidate(id, label, category, order, signals, slots, missing, missing.isEmpty() ? route : null,
                missing.isEmpty() ? routeState : "NOT_READY", reason);
    }
    private static String clarification(String label, String slot) { return label + "还需要补充：" + slotLabel(slot); }
    private static String slotLabel(String slot) {
        return switch (slot) {
            case "specificIssueOrFacts" -> "具体争议或关键事实";
            case "consultationPurpose" -> "希望查询、检索、比对还是参考案例";
            case "registrationNumberOrCondition" -> "注册号或明确查询条件";
            case "patentNumberOrCondition" -> "专利号或明确查询条件";
            case "trademarkName" -> "商标名称";
            case "categoryOrGoodsServices" -> "类别或商品/服务";
            case "technicalInputOrPatentNumber" -> "技术方案、技术特征或专利号";
            case "searchPurpose", "analysisPurpose" -> "检索或分析目的";
            case "targetTrademark", "targetPatent" -> "目标对象";
            case "candidateTrademark", "candidatePatent" -> "候选对象";
            case "goodsServices" -> "商品或服务项目";
            default -> slot;
        };
    }

    public static final class IntentException extends RuntimeException {
        private final String code;
        private final HttpStatus status;
        public IntentException(String code, String message, HttpStatus status) { super(message); this.code = code; this.status = status; }
        public String code() { return code; }
        public HttpStatus status() { return status; }
    }
}
