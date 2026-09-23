package cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryPort;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord;
import cn.xinji.agentplatform.consultation.trademarklibrary.domain.TrademarkLibraryRecord.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.adapter.out.publicpilot.PublicPilotTrademarkLoader;
import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.synthetic.SyntheticTrademarkCatalogAdapter;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("test-synthetic")
public final class SyntheticTrademarkLibraryAdapter implements TrademarkLibraryPort {
    private static final String VERSION = "synthetic-trademark-library-v1+public-pilot-v1";
    private static final String[] APPLICANTS = {"宁江瑞衡日化有限公司", "临川知禾科技有限公司", "东港澄岳实业有限公司", "海陵启辰商贸有限公司"};
    private final List<TrademarkLibraryRecord> records;

    public SyntheticTrademarkLibraryAdapter(SyntheticTrademarkCatalogAdapter catalog, PublicPilotTrademarkLoader publicPilot) {
        List<TrademarkLibraryRecord> combined = new ArrayList<>(catalog.documents().stream().map(SyntheticTrademarkLibraryAdapter::record).toList());
        combined.addAll(publicPilot.records());
        records = List.copyOf(combined);
    }

    @Override public List<TrademarkLibraryRecord> records() { return records; }
    @Override public Optional<TrademarkLibraryRecord> find(String sourceId) { return records.stream().filter(value -> value.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return VERSION; }

    @Override public HistoryResult history(String sourceId, String type) {
        TrademarkLibraryRecord record = find(sourceId).orElseThrow();
        if ("PUBLIC_PILOT".equals(record.dataMode())) {
            return result(type, "SOURCE_NOT_PROVIDED", List.of(), "公开公报快照未采集变更、异议或无效历史");
        }
        int index = index(sourceId);
        return switch (type) {
            case "changes" -> index % 3 == 0
                    ? result(type, "HAS_RECORDS", List.of(item(sourceId, type, index, "注册人地址变更", "已记录", null, "原合成地址", "现合成地址")), null)
                    : result(type, "NO_RECORDS", List.of(), null);
            case "oppositions" -> index % 7 == 0
                    ? result(type, "SOURCE_UNAVAILABLE", List.of(), "合成来源暂不可访问")
                    : index % 5 == 0
                        ? result(type, "HAS_RECORDS", List.of(item(sourceId, type, index, "异议申请", "处理中", "尚无决定结果", null, null)), null)
                        : result(type, "NO_RECORDS", List.of(), null);
            case "invalidations" -> index % 11 == 0
                    ? result(type, "QUERY_FAILED", List.of(), "合成子项查询失败")
                    : index % 8 == 0
                        ? result(type, "HAS_RECORDS", List.of(item(sourceId, type, index, "无效宣告申请", "已受理", "尚无决定结果", null, null)), null)
                        : result(type, "NO_RECORDS", List.of(), null);
            default -> throw new IllegalArgumentException("unknown history type");
        };
    }

    private static TrademarkLibraryRecord record(cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkDocument value) {
        int index = index(value.sourceId());
        LocalDate registered = LocalDate.of(2015, 1, 1).plusDays(index * 23L);
        List<ClassDetail> classes = new ArrayList<>();
        classes.add(new ClassDetail(value.categories().getFirst(), className(value.categories().getFirst()), value.goodsServices(), List.of("SYN-" + value.categories().getFirst() + "01")));
        if (index % 10 == 0) classes.add(new ClassDetail("35", "广告与商业服务", List.of("广告", "商业管理辅助"), List.of("3501", "3502")));
        List<String> applicants = index % 12 == 0 ? List.of(APPLICANTS[index % APPLICANTS.length], "云岑联合创新中心") : List.of(APPLICANTS[index % APPLICANTS.length]);
        String registrationNumber = index == 1 ? "0A76810001" : value.registrationNumber();
        String sourceStatus = switch (value.status()) { case "有效" -> "REGISTERED_ACTIVE"; case "待审" -> "APPLICATION_PENDING"; case "已失效" -> "RIGHT_EXPIRED"; default -> "UNRECOGNIZED_" + value.status(); };
        return new TrademarkLibraryRecord(value.dataSource(), value.sourceId(), value.version(), registrationNumber, value.name(), null,
                applicants, index % 13 == 0 ? null : "宁江市创新大道" + (20 + index) + "号", classes, registered,
                registered.plusYears(10), sourceStatus, value.status(), value.updatedAt(), "synthetic://trademarks/" + value.sourceId(), "SYNTHETIC_V0");
    }

    private static HistoryResult result(String type, String state, List<HistoryItem> records, String error) { return new HistoryResult(type, state, records, error); }
    private static HistoryItem item(String sourceId, String type, int index, String matter, String status, String result, String before, String after) {
        return new HistoryItem(sourceId + "-" + type.toUpperCase(Locale.ROOT) + "-001", LocalDate.of(2024, 1, 1).plusDays(index).toString(), matter, status, result, before, after, "合成业务机构", "synthetic://history/" + sourceId + "/" + type);
    }
    private static int index(String sourceId) { return Integer.parseInt(sourceId.substring(sourceId.lastIndexOf('-') + 1)); }
    private static String className(String code) { return switch (code) { case "3" -> "日化用品"; case "7" -> "机械设备"; case "9" -> "科学仪器"; case "30" -> "方便食品"; case "35" -> "广告销售"; case "42" -> "科技服务"; default -> "第 " + code + " 类"; }; }
}
