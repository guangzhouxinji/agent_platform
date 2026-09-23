package cn.xinji.agentplatform.knowledgebase.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryPort;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord.ClassDetail;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test-synthetic")
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "dm8")
public final class Dm8TrademarkLibraryAdapter implements TrademarkLibraryPort {
    private final List<TrademarkLibraryRecord> records;
    public Dm8TrademarkLibraryAdapter(Dm8KnowledgeBaseJsonReader reader) { records = reader.records("TRADEMARK").stream().map(Dm8TrademarkLibraryAdapter::map).toList(); }
    @Override public List<TrademarkLibraryRecord> records() { return records; }
    @Override public Optional<TrademarkLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-dm8-v1"; }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
    @Override public TrademarkLibraryRecord.HistoryResult history(String sourceId, String type) {
        if (find(sourceId).isEmpty()) throw new IllegalArgumentException("trademark not found");
        return new TrademarkLibraryRecord.HistoryResult(type, "SOURCE_NOT_PROVIDED", List.of(), "当前公开快照未采集流程历史");
    }
    private static TrademarkLibraryRecord map(JsonNode item) {
        List<ClassDetail> classes = StreamSupport.stream(item.path("classes").spliterator(), false)
                .map(v -> new ClassDetail(text(v,"code"), nullable(v,"name"), strings(v,"goodsServices"), strings(v,"similarGroups"))).toList();
        String event = nullable(item,"gazetteEvent");
        return new TrademarkLibraryRecord(text(item,"sourceId"), text(item,"sourceRecordId"), text(item,"sourceVersion"),
                nullable(item,"registrationNumber"), text(item,"name"), nullable(item,"imageRef"), strings(item,"applicants"),
                nullable(item,"applicantAddress"), classes, date(item,"registrationDate"), date(item,"validUntil"),
                nullable(item,"sourceStatusCode"), event==null?"来源事件未采集":"公报事件："+event,
                nullable(item,"sourceUpdatedAt"), text(item,"sourceRef"), "PUBLIC_LOCAL");
    }
    private static List<String> strings(JsonNode n,String f){return StreamSupport.stream(n.path(f).spliterator(),false).map(JsonNode::asText).toList();}
    private static String text(JsonNode n,String f){String v=nullable(n,f);if(v==null)throw new IllegalArgumentException(f+" is required");return v;}
    private static String nullable(JsonNode n,String f){JsonNode v=n.get(f);return v==null||v.isNull()||v.asText().isBlank()?null:v.asText();}
    private static LocalDate date(JsonNode n,String f){String v=nullable(n,f);return v==null?null:LocalDate.parse(v);}
}
