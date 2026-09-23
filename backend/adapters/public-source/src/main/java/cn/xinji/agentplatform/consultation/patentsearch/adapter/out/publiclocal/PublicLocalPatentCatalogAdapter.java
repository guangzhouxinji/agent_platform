package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.publiclocal;

import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonSourcePort;
import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentCatalogPort;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("patentSearchCatalog")
@Profile("!test-synthetic")
public final class PublicLocalPatentCatalogAdapter implements PatentCatalogPort, PatentComparisonSourcePort {
    private static final int MAX_CANDIDATES = 100;
    private static final String SCORING_VERSION = "PUBLIC_LOCAL_TEXT_MATCH_V1";
    private static final String LIMITATION = "仅检索本地保存的公开开发样本；字段不完整，不代表权威全库、现有技术穷尽或专业结论。";
    private final List<PatentDocument> documents;

    public PublicLocalPatentCatalogAdapter(PatentLibraryPort library) {
        documents = library.records().stream().map(PublicLocalPatentCatalogAdapter::document).toList();
    }

    @Override public CatalogDescriptor descriptor() { return new CatalogDescriptor("PUBLIC_LOCAL", SCORING_VERSION, LIMITATION); }
    @Override public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return documents.stream().anyMatch(value -> value.sourceId().equals(sourceId) && value.version().equals(sourceVersion));
    }
    @Override public Optional<PatentDocument> findPatent(String sourceId) { return documents.stream().filter(v -> v.sourceId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-patent-v1"; }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
    @Override public String ruleVersion() { return "PUBLIC-LOCAL-LITERAL-CLAIM-COMPARE-V1"; }
    @Override public List<String> limitations() { return List.of("仅使用本地公开记录中实际保存的权利要求文本逐字对照", "目标记录未提供权利要求技术特征时必须阻断，不能从标题或摘要推断", "未标定数值评分，不构成新颖性、侵权或保护范围结论，报告未经专业人员复核"); }

    @Override public CatalogResult search(PatentSearchCommand command) {
        List<String> terms = terms(command);
        List<PatentSearchCandidate> matched = documents.stream().filter(v -> inScope(v, command.scope()))
                .map(v -> candidate(v, terms, command)).filter(v -> v.score() > 0)
                .sorted(Comparator.comparingInt(PatentSearchCandidate::score).reversed().thenComparing(PatentSearchCandidate::sourceId))
                .toList();
        boolean truncated = matched.size() > MAX_CANDIDATES;
        List<PatentSearchCandidate> candidates = matched.stream().limit(MAX_CANDIDATES).toList();
        String actualScope = "数据模式=PUBLIC_LOCAL; 记录数=" + documents.size()
                + "; 类型=" + requested(command.scope().patentTypes())
                + "; 地域=" + requested(command.scope().regions())
                + "; 分类=" + requested(command.scope().classifications())
                + "; 申请日=" + command.scope().applicationFrom() + "~" + command.scope().applicationTo()
                + "; 公开日=" + command.scope().publicationFrom() + "~" + command.scope().publicationTo()
                + "; 命中=" + matched.size() + "; 返回=" + candidates.size();
        String limitation = truncated ? LIMITATION + " 本次命中超过 " + MAX_CANDIDATES + " 条，已截断返回。" : LIMITATION;
        return new CatalogResult(candidates, actualScope, limitation, truncated);
    }

    private static PatentSearchCandidate candidate(PatentDocument value, List<String> terms, PatentSearchCommand command) {
        List<String> matches = new ArrayList<>();
        int score = 0;
        String haystack = (value.title() + " " + value.abstractText() + " " + value.claimsText()).toLowerCase(Locale.ROOT);
        for (String term : terms) if (haystack.contains(term.toLowerCase(Locale.ROOT))) { matches.add(term); score += value.title().contains(term) ? 30 : 15; }
        if (command.inputMode() == PatentSearchCommand.InputMode.PATENT_NUMBER
                && normalize(value.publicationNumber()).equals(normalize(command.input()))) score = 100;
        String excerpt = value.abstractText().isBlank() ? value.title() : value.abstractText();
        if (excerpt.length() > 180) excerpt = excerpt.substring(0, 180);
        return new PatentSearchCandidate(value.sourceId(), text(value.applicationNumber()), text(value.publicationNumber()), text(value.title()),
                text(value.patentType()), text(value.version()), Math.min(score, 100), score >= 60 ? "高" : score >= 30 ? "中" : "低",
                matches, excerpt, value.claimsText().isBlank() ? "标题/摘要" : "权利要求节选");
    }

    private static List<String> terms(PatentSearchCommand command) {
        if (!command.confirmedFeatures().isEmpty()) return command.confirmedFeatures();
        return List.of(command.input().split("[\\s，,、；;。:：/]+")).stream().filter(v -> v.length() >= 2).distinct().toList();
    }

    private static boolean inScope(PatentDocument value, PatentSearchCommand.SearchScope scope) {
        return (scope.patentTypes().isEmpty() || scope.patentTypes().contains(value.patentType()))
                && (scope.regions().isEmpty() || scope.regions().contains(value.region()))
                && (scope.classifications().isEmpty() || scope.classifications().stream().anyMatch(value.classification()::startsWith))
                && notBefore(value.applicationDate(), scope.applicationFrom())
                && notAfter(value.applicationDate(), scope.applicationTo())
                && notBefore(value.publicationDate(), scope.publicationFrom())
                && notAfter(value.publicationDate(), scope.publicationTo());
    }

    private static boolean notBefore(java.time.LocalDate value, java.time.LocalDate lower) {
        return lower == null || value != null && !value.isBefore(lower);
    }

    private static boolean notAfter(java.time.LocalDate value, java.time.LocalDate upper) {
        return upper == null || value != null && !value.isAfter(upper);
    }

    private static String requested(List<String> values) { return values.isEmpty() ? "全部" : String.join(",", values); }

    private static PatentDocument document(PatentLibraryRecord value) {
        var version = value.versions().getFirst();
        String abstractText = text(version.abstractSection());
        String claimsText = text(version.claimsSection());
        String classification = !value.ipcClassifications().isEmpty() ? value.ipcClassifications().getFirst()
                : !value.cpcClassifications().isEmpty() ? value.cpcClassifications().getFirst() : "";
        String region = normalize(value.publicationNumber()).startsWith("CN") ? "CN" : "UNKNOWN";
        return new PatentDocument(value.sourceRecordId(), value.applicationNumber(), value.publicationNumber(), value.title(),
                value.patentType(), region, classification, value.applicationDate(), value.publicationDate(), value.sourceVersion(),
                abstractText, claimsText, List.of());
    }

    private static String text(PatentLibraryRecord.ContentSection section) {
        return section.texts().stream().map(PatentLibraryRecord.LocatedText::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }
    private static String text(String value) { return value == null ? "" : value; }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT); }
}
