package cn.xinji.agentplatform.consultation.patentsearch.domain;

import java.time.LocalDate;
import java.util.List;

public record PatentSearchCommand(
        InputMode inputMode,
        String input,
        String purpose,
        List<String> confirmedFeatures,
        SearchScope scope) {

    public static final String TECHNICAL_REFERENCE = "TECHNICAL_REFERENCE";

    public PatentSearchCommand {
        if (inputMode == null) throw new IllegalArgumentException("inputMode is required");
        input = input == null ? "" : input.trim();
        if (input.isBlank()) throw new IllegalArgumentException("input is required");
        purpose = purpose == null ? "" : purpose.trim();
        if (purpose.isBlank()) throw new IllegalArgumentException("purpose is required");
        if (!TECHNICAL_REFERENCE.equals(purpose)) {
            throw new IllegalArgumentException("only TECHNICAL_REFERENCE is available in the V0 pilot");
        }
        confirmedFeatures = confirmedFeatures == null ? List.of() : confirmedFeatures.stream()
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        scope = scope == null ? SearchScope.empty() : scope;
    }

    public enum InputMode { TECHNICAL_SOLUTION, CORE_FEATURES, PATENT_NUMBER }

    public record SearchScope(
            List<String> patentTypes,
            List<String> regions,
            List<String> classifications,
            LocalDate applicationFrom,
            LocalDate applicationTo,
            LocalDate publicationFrom,
            LocalDate publicationTo) {
        public SearchScope {
            patentTypes = copy(patentTypes);
            regions = copy(regions);
            classifications = copy(classifications);
            if (applicationFrom != null && applicationTo != null && applicationFrom.isAfter(applicationTo)) {
                throw new IllegalArgumentException("application date range is invalid");
            }
            if (publicationFrom != null && publicationTo != null && publicationFrom.isAfter(publicationTo)) {
                throw new IllegalArgumentException("publication date range is invalid");
            }
        }

        public static SearchScope empty() { return new SearchScope(List.of(), List.of(), List.of(), null, null, null, null); }
        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : values.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        }
    }
}
