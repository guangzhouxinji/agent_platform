package cn.xinji.agentplatform.consultation.caselibrary.application;

import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import java.util.List;
import java.util.Optional;

public interface CaseLibraryPort {
    List<CaseLibraryRecord> records();
    Optional<CaseLibraryRecord> find(String sourceId);
    String sourceVersion();
    default String dataMode() { return "DEVELOPMENT_MIXED"; }
}
