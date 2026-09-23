package cn.xinji.agentplatform.consultation.trademarksearch.application;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import java.util.List;

public interface TrademarkCatalogPort {
    CatalogResult search(TrademarkSearchCommand command);
    default CatalogDescriptor descriptor() {
        return new CatalogDescriptor("SYNTHETIC", "SYNTHETIC-TRADEMARK-RANK-V1",
                "合成商标数据仅用于功能验证，不代表真实商标库、近似结论或注册成功率。");
    }
    record CatalogDescriptor(String dataMode, String rankingVersion, String limitation) {}
    record CatalogResult(String actualScope, int totalCandidates, boolean truncated, List<TrademarkSearchCandidate> candidates, String limitation, boolean partial) {}
}
