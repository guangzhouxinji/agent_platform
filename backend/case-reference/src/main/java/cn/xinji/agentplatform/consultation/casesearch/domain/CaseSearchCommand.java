package cn.xinji.agentplatform.consultation.casesearch.domain;

import java.time.LocalDate;
import java.util.List;

public record CaseSearchCommand(
        String question,
        List<String> confirmedKeywords,
        List<String> caseTypes,
        List<String> intellectualPropertyTypes,
        List<String> institutions,
        List<String> procedures,
        LocalDate decisionFrom,
        LocalDate decisionTo,
        int resultLimit) {

    public CaseSearchCommand {
        question = question == null ? "" : question.trim();
        if (question.isBlank()) throw new IllegalArgumentException("question is required");
        confirmedKeywords = copy(confirmedKeywords);
        if (confirmedKeywords.isEmpty()) throw new IllegalArgumentException("confirmedKeywords is required");
        caseTypes = copy(caseTypes);
        intellectualPropertyTypes = copy(intellectualPropertyTypes);
        institutions = copy(institutions);
        procedures = copy(procedures);
        if (decisionFrom != null && decisionTo != null && decisionFrom.isAfter(decisionTo)) {
            throw new IllegalArgumentException("decision date range is invalid");
        }
        if (resultLimit == 0) resultLimit = 10;
        if (resultLimit < 1 || resultLimit > 50) throw new IllegalArgumentException("resultLimit must be between 1 and 50");
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }
}
