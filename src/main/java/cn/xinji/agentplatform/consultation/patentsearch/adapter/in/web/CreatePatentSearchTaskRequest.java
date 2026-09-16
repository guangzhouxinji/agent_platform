package cn.xinji.agentplatform.consultation.patentsearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.List;

public record CreatePatentSearchTaskRequest(
        String inputMode,
        String input,
        String purpose,
        List<String> confirmedFeatures,
        ScopeRequest scope) {

    PatentSearchCommand toCommand() {
        try {
            return new PatentSearchCommand(PatentSearchCommand.InputMode.valueOf(inputMode), input, purpose,
                    confirmedFeatures, scope == null ? PatentSearchCommand.SearchScope.empty() : scope.toScope());
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid patent search request", exception);
        }
    }

    public record ScopeRequest(
            List<String> patentTypes,
            List<String> regions,
            List<String> classifications,
            LocalDate applicationFrom,
            LocalDate applicationTo,
            LocalDate publicationFrom,
            LocalDate publicationTo) {
        PatentSearchCommand.SearchScope toScope() {
            return new PatentSearchCommand.SearchScope(patentTypes, regions, classifications,
                    applicationFrom, applicationTo, publicationFrom, publicationTo);
        }
    }
}
