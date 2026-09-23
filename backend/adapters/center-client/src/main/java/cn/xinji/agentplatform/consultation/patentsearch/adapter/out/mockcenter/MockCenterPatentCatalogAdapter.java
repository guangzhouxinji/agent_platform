package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterCatalogClient.PatentRecord;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentCatalogPort;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.*;

/** Maps the local PUBLIC_PILOT catalog to the provider-neutral patent search boundary. */
public final class MockCenterPatentCatalogAdapter implements PatentCatalogPort {
    private static final String RANKING_VERSION = "MOCK-CENTER-LEXICAL-V1";
    private static final String LIMITATION = "仅在本地公开试验目录的标题、IPC 和已采集摘要内检索；非中心权威数据，未检索全文或权利要求，不构成专业相关性或法律结论。";
    private final MockCenterCatalogClient catalog;

    public MockCenterPatentCatalogAdapter(MockCenterCatalogClient catalog) { this.catalog = catalog; }

    @Override public CatalogDescriptor descriptor() {
        MockCenterCatalogClient.Metadata metadata = catalog.metadata();
        return new CatalogDescriptor("PUBLIC_PILOT", metadata.catalogVersion() + ":" + RANKING_VERSION, LIMITATION);
    }

    @Override public CatalogResult search(PatentSearchCommand command) {
        MockCenterCatalogClient.Metadata metadata = catalog.metadata();
        LinkedHashMap<String, PatentRecord> records = new LinkedHashMap<>();
        List<String> features = features(command);
        if (command.inputMode() == PatentSearchCommand.InputMode.PATENT_NUMBER) {
            add(records, catalog.search(null, command.input(), 1, 50).items());
        } else {
            for (String feature : features) {
                add(records, catalog.search(feature, null, 1, 50).items());
            }
        }
        boolean unsupportedTypeFilter = !command.scope().patentTypes().isEmpty();
        List<PatentSearchCandidate> candidates = records.values().stream()
                .filter(record -> inScope(record, command.scope()))
                .map(record -> candidate(record, features))
                .sorted(Comparator.comparingInt(PatentSearchCandidate::score).reversed()
                        .thenComparing(PatentSearchCandidate::sourceId))
                .limit(20)
                .toList();
        String scope = "数据集=" + metadata.catalogVersion() + "(" + metadata.recordCount() + "条); 数据模式=PUBLIC_PILOT; 地域=CN; IPC="
                + display(command.scope().classifications()) + "; 申请日=" + range(command.scope().applicationFrom(), command.scope().applicationTo())
                + "; 公开日=" + range(command.scope().publicationFrom(), command.scope().publicationTo())
                + (unsupportedTypeFilter ? "; 专利类型=未执行(源未提供)" : "");
        return new CatalogResult(candidates, scope, LIMITATION, unsupportedTypeFilter);
    }

    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return catalog.find(sourceId).map(record -> record.sourceVersion().equals(sourceVersion)).orElse(false);
    }

    private static void add(Map<String, PatentRecord> target, List<PatentRecord> values) {
        for (PatentRecord value : values) target.putIfAbsent(value.recordId(), value);
    }

    private static List<String> features(PatentSearchCommand command) {
        if (!command.confirmedFeatures().isEmpty()) return command.confirmedFeatures();
        return Arrays.stream(command.input().split("[\\s，,、；;。:：/]+")).map(String::trim)
                .filter(value -> value.length() >= 2).distinct().limit(8).toList();
    }

    private static PatentSearchCandidate candidate(PatentRecord record, List<String> features) {
        List<String> matches = features.stream().filter(feature -> contains(record.title(), feature)
                || contains(record.abstractText(), feature)
                || record.ipc().stream().anyMatch(value -> contains(value, feature))).toList();
        int score = 0;
        for (String feature : matches) {
            if (contains(record.title(), feature)) score += 25;
            else if (contains(record.abstractText(), feature)) score += 12;
            else score += 8;
        }
        score = Math.min(100, score);
        String label = score >= 70 ? "高(未标定)" : score >= 40 ? "中(未标定)" : "低(未标定)";
        String excerpt = excerpt(record.abstractText(), matches);
        String location = excerpt.isBlank() ? record.abstractState() : "公开摘要";
        return new PatentSearchCandidate(record.recordId(), value(record.applicationNumber()), record.publicationNumber(),
                record.title(), "SOURCE_NOT_PROVIDED", record.sourceVersion(), score, label, matches, excerpt, location);
    }

    private static boolean inScope(PatentRecord record, PatentSearchCommand.SearchScope scope) {
        if (!scope.regions().isEmpty() && !scope.regions().contains("CN")) return false;
        if (!scope.classifications().isEmpty() && scope.classifications().stream()
                .noneMatch(prefix -> record.ipc().stream().anyMatch(value -> value.startsWith(prefix)))) return false;
        return inRange(record.applicationDate(), scope.applicationFrom(), scope.applicationTo())
                && inRange(record.publicationDate(), scope.publicationFrom(), scope.publicationTo());
    }

    private static boolean inRange(LocalDate value, LocalDate from, LocalDate to) {
        if (value == null) return from == null && to == null;
        return (from == null || !value.isBefore(from)) && (to == null || !value.isAfter(to));
    }

    private static String excerpt(String text, List<String> matches) {
        if (text == null || text.isBlank() || matches.isEmpty()) return "";
        int at = text.indexOf(matches.getFirst());
        if (at < 0) return text.substring(0, Math.min(120, text.length()));
        return text.substring(Math.max(0, at - 30), Math.min(text.length(), at + matches.getFirst().length() + 70));
    }

    private static boolean contains(String value, String feature) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(feature.toLowerCase(Locale.ROOT));
    }
    private static String display(List<String> values) { return values.isEmpty() ? "全部" : values.toString(); }
    private static String range(LocalDate from, LocalDate to) { return from + "~" + to; }
    private static String value(String value) { return value == null ? "" : value; }
}
