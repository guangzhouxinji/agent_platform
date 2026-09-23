package cn.xinji.agentplatform.consultation.application;

import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.List;

public interface PatentSearchPort {
    SearchResult search(String confirmedInput);

    /**
     * The consultation path supplies identity and an idempotency key when the
     * adapter can create a durable retrieval snapshot. Legacy adapters remain
     * valid for the narrow V0 path.
     */
    default SearchResult search(String requester, String idempotencyKey, String confirmedInput) {
        return search(confirmedInput);
    }

    boolean isSourceAvailable(String sourceId, String sourceVersion);

    record SearchResult(Type type, List<ConsultationCandidate> candidates, String limitation, String actualScope,
                        String retrievalTaskId) {
        public SearchResult(Type type, List<ConsultationCandidate> candidates, String limitation) {
            this(type, candidates, limitation, "V0 合成中国发明专利集", "");
        }
        public SearchResult(Type type, List<ConsultationCandidate> candidates, String limitation, String actualScope) {
            this(type, candidates, limitation, actualScope, "");
        }
        public SearchResult {
            candidates = List.copyOf(candidates);
            limitation = limitation == null ? "" : limitation;
            actualScope = actualScope == null ? "" : actualScope;
            retrievalTaskId = retrievalTaskId == null ? "" : retrievalTaskId;
        }
        public enum Type { SUCCESS, NO_RESULT, SOURCE_UNAVAILABLE, TOOL_FAILURE }
    }
}
