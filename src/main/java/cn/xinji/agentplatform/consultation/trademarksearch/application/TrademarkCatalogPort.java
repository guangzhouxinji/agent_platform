package cn.xinji.agentplatform.consultation.trademarksearch.application;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import java.util.List;

public interface TrademarkCatalogPort {
    CatalogResult search(TrademarkSearchCommand command);
    record CatalogResult(String actualScope, int totalCandidates, boolean truncated, List<TrademarkSearchCandidate> candidates, String limitation, boolean partial) {}
}
