package cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryPort;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Profile("!test-synthetic")
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "classpath")
public final class PublicLocalTrademarkLibraryAdapter implements TrademarkLibraryPort {
    private final List<TrademarkLibraryRecord> records;
    public PublicLocalTrademarkLibraryAdapter(PublicPilotTrademarkLoader loader) { records = List.copyOf(loader.records()); }
    @Override public List<TrademarkLibraryRecord> records() { return records; }
    @Override public Optional<TrademarkLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-trademark-v1"; }
    @Override public TrademarkLibraryRecord.HistoryResult history(String sourceId, String type) {
        if (find(sourceId).isEmpty()) throw new IllegalArgumentException("trademark not found");
        return new TrademarkLibraryRecord.HistoryResult(type, "SOURCE_NOT_PROVIDED", List.of(), "当前公开快照未采集流程历史");
    }
}
