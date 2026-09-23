package cn.xinji.agentplatform.consultation.trademarklibrary.application;

import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord.HistoryResult;
import java.util.List;
import java.util.Optional;

public interface TrademarkLibraryPort {
    List<TrademarkLibraryRecord> records();
    Optional<TrademarkLibraryRecord> find(String sourceId);
    HistoryResult history(String sourceId, String type);
    String sourceVersion();
    default String dataMode() { return "DEVELOPMENT_MIXED"; }
}
