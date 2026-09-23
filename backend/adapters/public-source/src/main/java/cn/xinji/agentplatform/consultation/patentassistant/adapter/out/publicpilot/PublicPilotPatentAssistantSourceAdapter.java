package cn.xinji.agentplatform.consultation.patentassistant.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot.PublicPilotPatentLoader;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PublicPilotPatentAssistantSourceAdapter implements PatentAssistantSourcePort {
    private static final String DATA_VERSION = "public-pilot-v0.1";
    private final List<PatentLibraryRecord> records;

    public PublicPilotPatentAssistantSourceAdapter(PublicPilotPatentLoader loader) {
        this.records = loader.records();
    }

    @Override
    public SearchPage search(SearchRequest request) {
        Set<String> terms = terms(request.question());
        List<Scored> scored = records.stream()
                .filter(record -> request.country() == null || value(record.publicationNumber()).startsWith(request.country().toLowerCase(Locale.ROOT)))
                .map(record -> score(record, terms))
                .filter(value -> value.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(value -> value.record().sourceRecordId()))
                .toList();
        int from = Math.min((request.page() - 1) * request.pageSize(), scored.size());
        int to = Math.min(from + request.pageSize(), scored.size());
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        List<PatentAssistantSource> sources = new ArrayList<>();
        for (int index = from; index < to; index++) {
            Scored value = scored.get(index);
            matchedTerms.addAll(value.matches());
            PatentLibraryRecord record = value.record();
            String excerpt = abstractText(record);
            sources.add(new PatentAssistantSource("P" + (index - from + 1), record.sourceRecordId(),
                    record.publicationNumber(), record.applicationNumber(), record.title(),
                    String.join("、", record.applicants()),
                    record.publicationDate() == null ? null : record.publicationDate().toString(), record.sourceRef(),
                    record.versions().getFirst().abstractSection().state(), String.join("、", value.matches()), excerpt));
        }
        return new SearchPage(matchedTerms.stream().limit(12).toList(), sources, request.page(), request.pageSize(),
                (long) scored.size(), to < scored.size());
    }

    @Override
    public SourceDetail detail(String publicationNumber) {
        return records.stream().filter(record -> publicationNumber.equals(record.publicationNumber())).findFirst()
                .map(record -> {
                    String excerpt = abstractText(record);
                    return new SourceDetail(publicationNumber, excerpt.isBlank() ? "SOURCE_NOT_PROVIDED" : "PARTIAL",
                            excerpt.isBlank() ? null : excerpt);
                })
                .orElse(new SourceDetail(publicationNumber, "SOURCE_NOT_PROVIDED", null));
    }

    @Override public SourceStatus status() {
        return new SourceStatus("public-static", "PUBLIC_PILOT", DATA_VERSION, records.size(), true,
                "NON_CENTER_PUBLIC_SOURCE", "USER_APPROVED_DEVELOPMENT_PILOT");
    }

    private static Scored score(PatentLibraryRecord record, Set<String> terms) {
        String title = value(record.title());
        String abstractText = value(abstractText(record));
        String classifications = value(String.join(" ", record.ipcClassifications()));
        List<String> matches = terms.stream()
                .filter(term -> title.contains(term) || abstractText.contains(term) || classifications.contains(term))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .limit(8).toList();
        int score = matches.stream().mapToInt(term -> (title.contains(term) ? 5 : 0)
                + (abstractText.contains(term) ? 3 : 0) + (classifications.contains(term) ? 1 : 0)
                + Math.min(term.length(), 4)).sum();
        return new Scored(record, score, matches);
    }

    private static Set<String> terms(String question) {
        String normalized = value(question).replaceAll("[^\\p{IsHan}a-z0-9]+", " ").trim();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String segment : normalized.split("\\s+")) {
            if (segment.isBlank()) continue;
            if (segment.matches(".*[a-z0-9].*")) result.add(segment);
            int max = Math.min(4, segment.length());
            for (int size = max; size >= 2; size--) {
                for (int start = 0; start + size <= segment.length(); start++) {
                    String term = segment.substring(start, start + size);
                    if (!Set.of("一下", "相关", "技术", "方案", "专利", "进行", "一种").contains(term)) result.add(term);
                }
            }
        }
        return result;
    }

    private static String abstractText(PatentLibraryRecord record) {
        if (record.versions().isEmpty()) return "";
        return record.versions().getFirst().abstractSection().texts().stream()
                .map(PatentLibraryRecord.LocatedText::text).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String value(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private record Scored(PatentLibraryRecord record, int score, List<String> matches) {}
}
