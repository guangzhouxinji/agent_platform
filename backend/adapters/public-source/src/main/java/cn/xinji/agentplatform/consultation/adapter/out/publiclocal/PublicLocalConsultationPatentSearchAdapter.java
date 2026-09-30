package cn.xinji.agentplatform.consultation.adapter.out.publiclocal;

import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile({"public-local-test", "dm8-integration"})
@ConditionalOnProperty(name = "consultation.search.provider", havingValue = "public-local")
public final class PublicLocalConsultationPatentSearchAdapter implements PatentSearchPort {
    private final List<PatentLibraryRecord> records;
    private final String actualScope;

    public PublicLocalConsultationPatentSearchAdapter(PatentLibraryPort library) {
        records = List.copyOf(library.records());
        actualScope = library.dataMode() + " / " + library.sourceVersion() + " / " + records.size() + " 件专利";
    }

    @Override
    public SearchResult search(String confirmedInput) {
        List<String> terms = Arrays.stream(confirmedInput.split("[\\s，,、；;。:：/]+"))
                .map(String::trim).filter(value -> value.length() >= 2).distinct().toList();
        List<ConsultationCandidate> matches = records.stream().map(value -> match(value, terms))
                .filter(value -> !value.fields().isEmpty())
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing(value -> value.record().sourceRecordId()))
                .limit(10).map(PublicLocalConsultationPatentSearchAdapter::candidate).toList();
        String limitation = "仅检索 DM8 中的本地公开开发数据；非中心权威全库，不代表检索完备性或专业结论。";
        if (matches.isEmpty()) {
            return new SearchResult(SearchResult.Type.NO_RESULT, List.of(),
                    limitation + " 当前数据库未返回候选，不等于不存在相关技术。", actualScope);
        }
        return new SearchResult(SearchResult.Type.SUCCESS, matches, limitation, actualScope);
    }

    @Override
    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        return records.stream().anyMatch(value -> value.sourceRecordId().equals(sourceId)
                && value.sourceVersion().equals(sourceVersion));
    }

    private static Match match(PatentLibraryRecord value, List<String> terms) {
        List<FieldText> fields = List.of(
                new FieldText("标题", value.title(), 8),
                new FieldText("申请号/公开号", text(value.applicationNumber(), value.publicationNumber()), 10),
                new FieldText("摘要", sections(value, true), 5),
                new FieldText("权利要求", sections(value, false), 6),
                new FieldText("分类号", text(value.ipcClassifications(), value.cpcClassifications()), 7),
                new FieldText("申请人/发明人", text(value.applicants(), value.inventors()), 4));
        List<String> matched = fields.stream()
                .filter(field -> terms.stream().anyMatch(term -> contains(field.text(), term)))
                .map(FieldText::name).toList();
        int score = fields.stream().filter(field -> matched.contains(field.name())).mapToInt(FieldText::weight).sum();
        return new Match(value, matched, score);
    }

    private static ConsultationCandidate candidate(Match match) {
        PatentLibraryRecord value = match.record();
        String basis = "数据库命中字段：" + String.join("、", match.fields()) + "；标题：" + value.title();
        return new ConsultationCandidate(value.sourceRecordId(), value.sourceVersion(),
                "DM8 PUBLIC_LOCAL / " + String.join("+", match.fields()), basis,
                "标题：" + value.title() + "\n公开（公告）号：" + value.publicationNumber()
                        + "\nIPC：" + String.join("、", value.ipcClassifications())
                        + "\nCPC：" + String.join("、", value.cpcClassifications())
                        + "\n摘要：" + sections(value, true) + "\n权利要求：" + sections(value, false));
    }

    private static String sections(PatentLibraryRecord value, boolean abstractSection) {
        return value.versions().stream()
                .map(version -> abstractSection ? version.abstractSection() : version.claimsSection())
                .flatMap(section -> section.texts().stream()).map(PatentLibraryRecord.LocatedText::text)
                .reduce((left, right) -> left + " " + right).orElse("");
    }

    private static boolean contains(String value, String term) {
        return value.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    @SafeVarargs
    private static String text(List<String>... values) {
        return Arrays.stream(values).flatMap(List::stream).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String text(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " " + right).orElse("");
    }

    private record FieldText(String name, String text, int weight) {}
    private record Match(PatentLibraryRecord record, List<String> fields, int score) {}
}
