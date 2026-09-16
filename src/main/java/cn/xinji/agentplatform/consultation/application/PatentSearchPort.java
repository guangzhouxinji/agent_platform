package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.List;

public interface PatentSearchPort {
    SearchResult search(String confirmedInput);

    boolean isSourceAvailable(String sourceId, String sourceVersion);

    record SearchResult(Type type, List<ConsultationCandidate> candidates, String limitation) {
        public enum Type { SUCCESS, NO_RESULT, SOURCE_UNAVAILABLE, TOOL_FAILURE }
    }
}
