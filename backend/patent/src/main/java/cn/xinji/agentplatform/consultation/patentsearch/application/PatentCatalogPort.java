package cn.xinji.agentplatform.consultation.patentsearch.application;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.util.List;

public interface PatentCatalogPort {
    CatalogDescriptor descriptor();

    CatalogResult search(PatentSearchCommand command);

    /** Current source availability is checked separately from an immutable task snapshot. */
    default boolean isSourceAvailable(String sourceId, String sourceVersion) { return true; }

    record CatalogResult(List<PatentSearchCandidate> candidates, String actualScope, String limitation, boolean partial) {
        public CatalogResult { candidates = List.copyOf(candidates); }
    }
    record CatalogDescriptor(String dataMode, String scoringVersion, String limitation) {}
}
