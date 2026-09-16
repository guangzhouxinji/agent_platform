package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.synthetic;

import cn.xinji.agentplatform.consultation.patentsearch.application.PatentCatalogPort;
import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonSourcePort;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public final class SyntheticPatentCatalogAdapter implements PatentCatalogPort, PatentComparisonSourcePort {
    private static final int CATALOG_SIZE = 120;
    private static final String CATALOG_VERSION = "synthetic-patent-catalog-v2";
    private static final String LIMITATION = "仅在 " + CATALOG_VERSION + " 的 " + CATALOG_SIZE + " 条拟真合成文献内检索；不代表全库、真实专利或 Recall@K。";
    private final List<PatentDocument> documents = catalog();
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    @Override public CatalogResult search(PatentSearchCommand command) {
        if (failNext.compareAndSet(true, false)) throw new IllegalStateException("synthetic one-shot failure");
        PatentDocument seed = command.inputMode() == PatentSearchCommand.InputMode.PATENT_NUMBER ? findByNumber(command.input()) : null;
        List<String> features = queryFeatures(command, seed);
        List<PatentSearchCandidate> candidates = documents.stream()
                .filter(document -> seed == null || !document.sourceId().equals(seed.sourceId()))
                .filter(document -> inScope(document, command.scope()))
                .map(document -> candidate(document, features))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(PatentSearchCandidate::score).reversed()
                        .thenComparing(PatentSearchCandidate::sourceId))
                .limit(20)
                .toList();
        return new CatalogResult(candidates, actualScope(command.scope()), LIMITATION, false);
    }

    public List<PatentDocument> documents() { return documents; }
    @Override public java.util.Optional<PatentDocument> findPatent(String sourceId) { return documents.stream().filter(document -> document.sourceId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return CATALOG_VERSION; }
    public void failNextSearch() { failNext.set(true); }

    private PatentDocument findByNumber(String number) {
        String normalized = normalize(number);
        return documents.stream().filter(document -> normalize(document.applicationNumber()).equals(normalized)
                || normalize(document.publicationNumber()).equals(normalized)).findFirst().orElse(null);
    }

    private List<String> queryFeatures(PatentSearchCommand command, PatentDocument seed) {
        if (!command.confirmedFeatures().isEmpty()) return command.confirmedFeatures();
        if (seed != null) return seed.featureTags();
        Set<String> features = new LinkedHashSet<>();
        for (PatentDocument document : documents) {
            for (String tag : document.featureTags()) if (command.input().contains(tag)) features.add(tag);
        }
        for (String token : command.input().split("[\\s，,、；;。:：/]+")) if (token.length() >= 2) features.add(token);
        return List.copyOf(features);
    }

    private static PatentSearchCandidate candidate(PatentDocument document, List<String> features) {
        List<String> matches = new ArrayList<>();
        int score = 0;
        for (String feature : features) {
            int featureScore = 0;
            if (document.title().contains(feature)) featureScore = Math.max(featureScore, 25);
            if (document.featureTags().stream().anyMatch(tag -> tag.contains(feature) || feature.contains(tag))) featureScore = Math.max(featureScore, 20);
            if (document.abstractText().contains(feature)) featureScore = Math.max(featureScore, 12);
            if (document.claimsText().contains(feature)) featureScore = Math.max(featureScore, 8);
            if (featureScore > 0) { matches.add(feature); score += featureScore; }
        }
        score = Math.min(100, score);
        String excerpt = excerpt(document, matches);
        String label = score >= 70 ? "高" : score >= 40 ? "中" : "低";
        return new PatentSearchCandidate(document.sourceId(), document.applicationNumber(), document.publicationNumber(),
                document.title(), document.patentType(), document.version(), score, label, matches, excerpt,
                document.claimsText().contains(matches.isEmpty() ? "\u0000" : matches.get(0)) ? "权利要求 1" : "摘要");
    }

    private static String excerpt(PatentDocument document, List<String> matches) {
        if (matches.isEmpty()) return "";
        String feature = matches.get(0);
        String text = document.claimsText().contains(feature) ? document.claimsText() : document.abstractText();
        int at = text.indexOf(feature);
        int from = Math.max(0, at - 18);
        int to = Math.min(text.length(), at + feature.length() + 28);
        return text.substring(from, to);
    }

    private static boolean inScope(PatentDocument document, PatentSearchCommand.SearchScope scope) {
        return containsOrEmpty(scope.patentTypes(), document.patentType())
                && containsOrEmpty(scope.regions(), document.region())
                && (scope.classifications().isEmpty() || scope.classifications().stream().anyMatch(document.classification()::startsWith))
                && notBefore(document.applicationDate(), scope.applicationFrom()) && notAfter(document.applicationDate(), scope.applicationTo())
                && notBefore(document.publicationDate(), scope.publicationFrom()) && notAfter(document.publicationDate(), scope.publicationTo());
    }

    private static boolean containsOrEmpty(List<String> values, String target) { return values.isEmpty() || values.contains(target); }
    private static boolean notBefore(LocalDate value, LocalDate lower) { return lower == null || !value.isBefore(lower); }
    private static boolean notAfter(LocalDate value, LocalDate upper) { return upper == null || !value.isAfter(upper); }
    private static String normalize(String value) { return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }
    private static String actualScope(PatentSearchCommand.SearchScope scope) {
        return "数据集=" + CATALOG_VERSION + "(" + CATALOG_SIZE + "条); 类型=" + (scope.patentTypes().isEmpty() ? "全部" : scope.patentTypes())
                + "; 地域=" + (scope.regions().isEmpty() ? "全部" : scope.regions())
                + "; 分类=" + (scope.classifications().isEmpty() ? "全部" : scope.classifications())
                + "; 申请日=" + scope.applicationFrom() + "~" + scope.applicationTo()
                + "; 公开日=" + scope.publicationFrom() + "~" + scope.publicationTo();
    }

    private static List<PatentDocument> catalog() {
        List<PatentDocument> values = new ArrayList<>(List.of(
                doc("SYN-PAT-001", "SYN202010001", "SYNCN110001A", "水性粘结剂电极涂布方法", "发明", "CN", "H01M4/04", "2020-01-10", "2021-07-10", "水性粘结剂用于电极浆料并进行双区烘干。", "权利要求1：采用水性粘结剂、双区烘干和张力控制完成涂布。", "水性粘结剂", "双区烘干", "电极涂布"),
                doc("SYN-PAT-002", "SYN202010002", "SYNCN110002A", "在线测厚反馈控制装置", "实用新型", "CN", "G01B11/06", "2020-03-12", "2021-09-12", "在线测厚传感器输出用于闭环调节涂布间隙。", "权利要求1：在线测厚反馈控制器连接激光测厚单元和执行机构。", "在线测厚", "反馈控制", "激光测量"),
                doc("SYN-PAT-003", "SYN201910003", "SYNCN109003A", "多温区薄膜烘干系统", "发明", "CN", "F26B13/10", "2019-06-08", "2020-12-08", "薄膜依次经过低温和高温烘干区并回收热量。", "权利要求1：双区烘干系统分别控制温度、风速和停留时间。", "双区烘干", "温度控制", "热量回收"),
                doc("SYN-PAT-004", "SYN202110004", "SYNCN113004A", "锂电极浆料混合及涂覆工艺", "发明", "CN", "H01M4/139", "2021-02-18", "2022-08-18", "浆料混合后进行连续涂覆和干燥。", "权利要求1：水性浆料经分散、过滤、涂覆和分段干燥。", "水性浆料", "电极涂布", "分段干燥"),
                doc("SYN-PAT-005", "SYN202120005", "SYNCN215005U", "卷材恒张力输送机构", "实用新型", "CN", "B65H23/188", "2021-04-20", "2022-01-20", "浮辊与伺服电机配合保持卷材张力。", "权利要求1：张力传感器、浮辊和伺服控制器构成闭环。", "张力控制", "卷材输送", "反馈控制"),
                doc("SYN-PAT-006", "SYN201880006", "SYNEP350006A1", "Polymer film coating process", "发明", "EP", "B05D3/02", "2018-09-02", "2020-03-02", "A polymer film is coated and dried in multiple controlled zones.", "Claim 1: coating thickness feedback adjusts a multi-zone drying process.", "coating thickness", "multi-zone drying", "feedback"),
                doc("SYN-PAT-007", "SYN202210007", "SYNCN115007A", "激光位移测量校准方法", "发明", "CN", "G01B11/02", "2022-05-06", "2023-11-06", "利用标准件对激光位移传感器进行在线校准。", "权利要求1：激光测量结果经温度补偿后输出厚度值。", "激光测量", "在线校准", "温度补偿"),
                doc("SYN-PAT-008", "SYN202020008", "SYNCN212008U", "胶黏剂分段固化设备", "实用新型", "CN", "B29C35/04", "2020-08-15", "2021-05-15", "胶黏剂经过预热、固化和冷却区。", "权利要求1：分区温控组件实现胶黏剂连续固化。", "胶黏剂", "分区温控", "连续固化"),
                doc("SYN-PAT-009", "SYN201910009", "SYNCN108009A", "涂布缺陷视觉检测方法", "发明", "CN", "G06T7/00", "2019-01-19", "2020-07-19", "图像模型识别涂布条纹、孔洞和边缘缺陷。", "权利要求1：采集卷材图像并输出缺陷位置，不改变厚度控制。", "视觉检测", "涂布缺陷", "卷材图像"),
                doc("SYN-PAT-010", "SYN202110010", "SYNCN114010A", "烘干尾气热量回收装置", "发明", "CN", "F28D21/00", "2021-10-11", "2023-04-11", "换热器回收烘干尾气热量并预热新风。", "权利要求1：热量回收回路连接烘干区和新风入口。", "热量回收", "烘干尾气", "新风预热"),
                doc("SYN-PAT-011", "SYN202230011", "SYNCN308011S", "涂布机操作面板", "外观设计", "CN", "14-02", "2022-07-23", "2023-02-23", "产品名称为涂布机操作面板，设计要点在图形界面布局。", "外观设计图片未接入，本条仅支持名称和分类检索。", "涂布机", "操作面板", "图形界面"),
                doc("SYN-PAT-012", "SYN202230012", "SYNCN308012S", "测厚仪外壳", "外观设计", "CN", "10-04", "2022-09-30", "2023-03-30", "产品名称为测厚仪外壳，设计要点在整体形状。", "外观设计图片未接入，本条仅支持名称和分类检索。", "测厚仪", "外壳", "整体形状")
        ));
        String[][] topics = {
                {"工业视觉缺陷定位方法","G06T7/00","视觉检测","缺陷定位","图像分割"},
                {"仓储移动机器人调度系统","G05D1/02","路径规划","任务调度","避障控制"},
                {"高效换热器流道结构","F28F3/02","换热流道","压降控制","热交换"},
                {"建筑能耗预测与控制方法","G06Q50/08","能耗预测","负荷调节","建筑控制"},
                {"农作物病害识别装置","A01G7/06","病害识别","多光谱采集","生长监测"},
                {"低噪声离心风机叶轮","F04D29/28","离心风机","叶轮结构","降噪"},
                {"可穿戴生理信号采集终端","A61B5/00","生理信号","佩戴检测","低功耗"},
                {"配电网故障区段定位方法","H02J13/00","故障定位","配电网","状态估计"},
                {"食品冷链温度追踪系统","G01K13/00","温度追踪","冷链运输","异常告警"}
        };
        for (int index = 13; index <= CATALOG_SIZE; index++) {
            String[] topic = topics[(index - 13) % topics.length]; int year = 2017 + index % 8;
            String suffix = String.format("%06d", index); String type = index % 5 == 0 ? "实用新型" : "发明";
            values.add(doc("SYN-PAT-" + String.format("%03d", index), "CN" + year + "10" + suffix + "." + index % 10,
                    "CN" + (110000000 + index) + ("实用新型".equals(type) ? "U" : "A"), topic[0] + "（实施例" + (index - 12) + "）",
                    type, index % 11 == 0 ? "EP" : "CN", topic[1], year + "-" + String.format("%02d", index % 12 + 1) + "-15",
                    (year + 1) + "-" + String.format("%02d", index % 12 + 1) + "-20",
                    "本实施例针对" + topic[2] + "场景，结合" + topic[3] + "与运行状态采集，提高处理稳定性。",
                    "权利要求1：一种" + topic[0] + "，包括数据采集模块、" + topic[3] + "模块以及结果输出模块。", topic[2], topic[3], topic[4]));
        }
        return List.copyOf(values);
    }

    private static PatentDocument doc(String id, String application, String publication, String title, String type,
                                      String region, String classification, String applicationDate, String publicationDate,
                                      String abstractText, String claims, String... tags) {
        return new PatentDocument(id, application, publication, title, type, region, classification,
                LocalDate.parse(applicationDate), LocalDate.parse(publicationDate), "synthetic-v1",
                abstractText, claims, List.of(tags));
    }
}
