package cn.xinji.agentplatform.consultation.patentcomparison.application;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import java.util.Optional;

public interface PatentComparisonSourcePort {
    Optional<PatentDocument> findPatent(String sourceId);
    String sourceVersion();
}
