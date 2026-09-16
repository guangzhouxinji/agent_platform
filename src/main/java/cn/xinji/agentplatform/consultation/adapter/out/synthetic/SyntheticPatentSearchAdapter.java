package cn.xinji.agentplatform.consultation.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class SyntheticPatentSearchAdapter implements PatentSearchPort {
    private final Set<String> unavailableSources = new HashSet<>();
    private SearchResult.Type forcedType = SearchResult.Type.SUCCESS;
    private int calls;

    @Override public SearchResult search(String confirmedInput) {
        calls++;
        if (forcedType == SearchResult.Type.NO_RESULT) return new SearchResult(forcedType, java.util.List.of(), "在 V0 合成范围内未返回候选，不等于不存在相关技术");
        if (forcedType == SearchResult.Type.TOOL_FAILURE) return new SearchResult(forcedType, java.util.List.of(), "合成检索工具故障，不等于无结果");
        var candidates = new ArrayList<ConsultationCandidate>();
        if (!unavailableSources.contains("V0-SYN-PAT-001")) candidates.add(new ConsultationCandidate("V0-SYN-PAT-001", "v1", "权利要求 1", "水性粘结剂、双区烘干"));
        if (!unavailableSources.contains("V0-SYN-PAT-002")) candidates.add(new ConsultationCandidate("V0-SYN-PAT-002", "v1", "权利要求 2", "在线测厚反馈"));
        if (candidates.isEmpty()) return new SearchResult(SearchResult.Type.SOURCE_UNAVAILABLE, java.util.List.of(), "来源当前不可用，不等于没有相关技术");
        return new SearchResult(SearchResult.Type.SUCCESS, java.util.List.copyOf(candidates), unavailableSources.isEmpty() ? "" : "部分来源不可用，已排除。");
    }

    @Override public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return !unavailableSources.contains(sourceId);
    }

    public void markSourceUnavailable(String sourceId) { unavailableSources.add(sourceId); }
    public void force(SearchResult.Type type) { forcedType = type; }
    public void reset() { unavailableSources.clear(); forcedType = SearchResult.Type.SUCCESS; calls = 0; }
    public int calls() { return calls; }
}
