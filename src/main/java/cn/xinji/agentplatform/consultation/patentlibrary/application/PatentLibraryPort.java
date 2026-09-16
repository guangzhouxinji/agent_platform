package cn.xinji.agentplatform.consultation.patentlibrary.application;

import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.EventResult;
import java.util.List;
import java.util.Optional;

public interface PatentLibraryPort {
    List<PatentLibraryRecord> records();
    Optional<PatentLibraryRecord> find(String sourceId);
    EventResult events(String sourceId);
    String sourceVersion();
}
