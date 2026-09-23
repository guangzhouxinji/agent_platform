package cn.xinji.agentplatform.consultation.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.Dm8KnowledgeBaseJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

public final class Dm8ConsultationPatentSearchAdapter implements PatentSearchPort {
    private static final String LIMITATION = "仅检索 DM8 中的本地公开开发数据；非中心权威全库，不代表检索完备性或专业结论。";
    private final Supplier<List<JsonNode>> records;

    public Dm8ConsultationPatentSearchAdapter(Dm8KnowledgeBaseJsonReader reader) {
        this(() -> reader.records("PATENT"));
    }

    public Dm8ConsultationPatentSearchAdapter(Supplier<List<JsonNode>> records) {
        this.records = records;
    }

    @Override
    public SearchResult search(String confirmedInput) {
        try {
            List<JsonNode> snapshot = records.get();
            List<String> terms = Arrays.stream(confirmedInput.split("[\\s，,、；;。:：/]+"))
                    .map(String::trim).filter(value -> value.length() >= 2).distinct().toList();
            List<ConsultationCandidate> matches = snapshot.stream().map(value -> match(value, terms))
                    .filter(value -> !value.fields().isEmpty())
                    .sorted(Comparator.comparingInt(Match::score).reversed()
                            .thenComparing(value -> required(value.record(), "sourceRecordId")))
                    .limit(10).map(Dm8ConsultationPatentSearchAdapter::candidate).toList();
            String scope = "PUBLIC_LOCAL / DM8 KB_RECORD_VERSION / " + snapshot.size() + " 件专利";
            if (matches.isEmpty()) {
                return new SearchResult(SearchResult.Type.NO_RESULT, List.of(),
                        LIMITATION + " 当前数据库未返回候选，不等于不存在相关技术。", scope);
            }
            return new SearchResult(SearchResult.Type.SUCCESS, matches, LIMITATION, scope);
        } catch (RuntimeException failure) {
            return new SearchResult(SearchResult.Type.TOOL_FAILURE, List.of(),
                    "DM8 专利知识库当前不可用，未回退到合成数据或模拟中心。", "DM8 PUBLIC_LOCAL");
        }
    }

    @Override
    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        try {
            return records.get().stream().anyMatch(value -> required(value, "sourceRecordId").equals(sourceId)
                    && required(value, "sourceVersion").equals(sourceVersion));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Match match(JsonNode value, List<String> terms) {
        List<FieldText> fields = List.of(
                new FieldText("标题", optional(value, "title"), 8),
                new FieldText("申请号/公开号", text(optional(value, "applicationNumber"), optional(value, "publicationNumber")), 10),
                new FieldText("摘要", optional(value, "abstract"), 5),
                new FieldText("权利要求", optional(value, "claims"), 6),
                new FieldText("分类号", arrays(value, "ipc", "cpc"), 7),
                new FieldText("申请人/发明人", arrays(value, "applicants", "inventors"), 4));
        List<String> matched = fields.stream()
                .filter(field -> terms.stream().anyMatch(term -> contains(field.text(), term)))
                .map(FieldText::name).toList();
        int score = fields.stream().filter(field -> matched.contains(field.name())).mapToInt(FieldText::weight).sum();
        return new Match(value, matched, score);
    }

    private static ConsultationCandidate candidate(Match match) {
        JsonNode value = match.record();
        String basis = "数据库命中字段：" + String.join("、", match.fields())
                + "；标题：" + required(value, "title");
        return new ConsultationCandidate(required(value, "sourceRecordId"), required(value, "sourceVersion"),
                "DM8 PUBLIC_LOCAL / " + String.join("+", match.fields()), basis,
                "标题：" + required(value, "title")
                        + "\n公开（公告）号：" + optional(value, "publicationNumber")
                        + "\nIPC/CPC：" + arrays(value, "ipc", "cpc")
                        + "\n摘要：" + optional(value, "abstract")
                        + "\n权利要求：" + optional(value, "claims"));
    }

    private static String arrays(JsonNode value, String... fields) {
        return Arrays.stream(fields).flatMap(field -> StreamSupport.stream(value.path(field).spliterator(), false))
                .map(JsonNode::asText).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String optional(JsonNode value, String field) {
        JsonNode item = value.get(field);
        return item == null || item.isNull() ? "" : item.asText("").trim();
    }

    private static String required(JsonNode value, String field) {
        String item = optional(value, field);
        if (item.isBlank()) throw new IllegalStateException("DM8 patent payload is missing " + field);
        return item;
    }

    private static boolean contains(String value, String term) {
        return value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    private static String text(String... values) {
        return Arrays.stream(values).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + " " + right).orElse("");
    }

    private record FieldText(String name, String text, int weight) {}
    private record Match(JsonNode record, List<String> fields, int score) {}
}
