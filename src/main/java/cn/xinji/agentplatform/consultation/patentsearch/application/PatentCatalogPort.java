package cn.xinji.agentplatform.consultation.patentsearch.application;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.util.List;

public interface PatentCatalogPort {
    CatalogResult search(PatentSearchCommand command);

    record CatalogResult(List<PatentSearchCandidate> candidates, String actualScope, String limitation, boolean partial) {
        public CatalogResult { candidates = List.copyOf(candidates); }
    }
}
