package cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.publiclocal;

import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonSourcePort;
import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryPort;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkCatalogPort;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkDocument;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"public-local-test", "dm8-integration"})
public final class PublicLocalTrademarkCatalogAdapter implements TrademarkCatalogPort, TrademarkComparisonSourcePort {
    private static final String VERSION = "public-local-trademark-v1";
    private static final String LIMITATION = "仅检索 100 条 USPTO 派生公开开发样本；不是中国商标库，不代表权威状态或近似结论。";
    private final List<TrademarkDocument> documents;

    public PublicLocalTrademarkCatalogAdapter(TrademarkLibraryPort library) {
        documents = library.records().stream().map(PublicLocalTrademarkCatalogAdapter::document).toList();
    }

    @Override public Optional<TrademarkDocument> findTrademark(String sourceId) { return documents.stream().filter(v -> v.sourceId().equals(sourceId)).findFirst(); }
    @Override public String comparisonSourceVersion() { return VERSION; }
    @Override public CatalogDescriptor descriptor() { return new CatalogDescriptor("PUBLIC_LOCAL", "PUBLIC-LOCAL-TRADEMARK-TEXT-V1", LIMITATION); }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
    @Override public String ruleVersion() { return "PUBLIC-LOCAL-TRADEMARK-OBSERVATION-V1"; }
    @Override public List<String> limitations() { return List.of("仅使用本地 USPTO 派生公开记录中实际存在的名称、类别和商品服务字段", "读音、含义、图形和整体视觉材料未配置时标记为未分析，不作推断", "不是中国商标库，不提供近似、注册成功率或审查结论，报告未经专业人员复核"); }

    @Override public CatalogResult search(TrademarkSearchCommand command) {
        List<Scored> all = documents.stream().filter(v -> inScope(v, command)).map(v -> score(v, command))
                .filter(v -> v.score() > 0).sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(v -> v.document().sourceId())).toList();
        int from = Math.min((command.page() - 1) * command.pageSize(), all.size());
        int to = Math.min(from + command.pageSize(), all.size());
        List<TrademarkSearchCandidate> candidates = all.subList(from, to).stream().map(v -> candidate(v, command)).toList();
        return new CatalogResult("本地 USPTO 公开快照 100 条", all.size(), to < all.size(), candidates, LIMITATION, false);
    }

    private static boolean inScope(TrademarkDocument value, TrademarkSearchCommand command) {
        return !java.util.Collections.disjoint(value.categories(), command.categories())
                && (command.scope().statuses().isEmpty() || command.scope().statuses().contains(value.status()))
                && (command.scope().regions().isEmpty() || command.scope().regions().contains(value.region()))
                && (command.scope().dataSources().isEmpty() || command.scope().dataSources().contains(value.dataSource()));
    }

    private static Scored score(TrademarkDocument value, TrademarkSearchCommand command) {
        String name = value.name().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String query = command.normalizedName();
        int score = name.equals(query) ? 100 : name.contains(query) || query.contains(name) ? 70 : 0;
        return new Scored(value, score);
    }

    private static TrademarkSearchCandidate candidate(Scored scored, TrademarkSearchCommand command) {
        List<TrademarkSearchCandidate.DimensionResult> dimensions = new ArrayList<>();
        for (TrademarkSearchCommand.Dimension dimension : command.dimensions()) {
            if (dimension == TrademarkSearchCommand.Dimension.TEXT) dimensions.add(new TrademarkSearchCandidate.DimensionResult(
                    dimension, TrademarkSearchCandidate.State.EXECUTED, "公开快照名称文字匹配"));
            else dimensions.add(new TrademarkSearchCandidate.DimensionResult(dimension, TrademarkSearchCandidate.State.NOT_ANALYZED,
                    "当前公开快照未提供该维度所需材料"));
        }
        TrademarkDocument value = scored.document();
        return new TrademarkSearchCandidate(value.sourceId(), value.registrationNumber(), value.name(), value.categories(),
                value.goodsServices(), value.status(), value.region(), value.dataSource(), value.version(), value.updatedAt(),
                scored.score() == 100 ? "文字完全匹配候选" : "文字包含候选", dimensions);
    }

    private static TrademarkDocument document(TrademarkLibraryRecord value) {
        List<String> categories = value.classes().stream().map(TrademarkLibraryRecord.ClassDetail::code).toList();
        List<String> goods = value.classes().stream().flatMap(v -> v.goodsServices().stream()).toList();
        return new TrademarkDocument(value.sourceRecordId(), value.registrationNumber(), value.name(), "", categories, goods,
                value.sourceStatus(), "US", value.sourceId(), value.sourceVersion(), value.sourceUpdatedAt());
    }

    private record Scored(TrademarkDocument document, int score) {}
}
