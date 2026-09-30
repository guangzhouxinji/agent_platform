package cn.xinji.agentplatform.enforcement.conduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

/** Deterministic relation coverage, not a legal finding or semantic similarity classifier. */
public final class ConductEvaluator {
    public Trial evaluate(Rule rule, Input input) {
        if (rule == null || input == null || input.facts() == null || input.links() == null ||
                input.defenseRefs() == null || input.behaviorAt() == null || input.behaviorAt().isBlank() ||
                rule.elements() == null || rule.elements().isEmpty())
            throw new IllegalArgumentException("Incomplete conduct input");
        Set<String> elementIds = new HashSet<>();
        for (Element element : rule.elements()) {
            if (element == null || blank(element.id()) || !elementIds.add(element.id()))
                throw new IllegalArgumentException("Invalid rule elements");
        }
        Set<String> factRefs = new HashSet<>();
        for (Fact fact : input.facts()) {
            if (fact == null || blank(fact.ref()) ||
                    !Set.of("MACHINE_CANDIDATE", "PARTY_STATEMENT", "HUMAN_CONFIRMED")
                            .contains(fact.kind()) ||
                    blank(fact.sourceRef()) || blank(fact.locator()) || !factRefs.add(fact.ref()))
                throw new IllegalArgumentException("Untraceable fact");
        }
        for (Link link : input.links()) {
            if (link == null || !elementIds.contains(link.elementId()) ||
                    !factRefs.contains(link.factRef()) || blank(link.evidenceRef()) ||
                    blank(link.locator()) || link.relation() == null)
                throw new IllegalArgumentException("Untraceable element relation");
        }
        List<ElementFinding> findings = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (Element element : rule.elements()) {
            var support = input.links().stream().filter(link -> link.elementId().equals(element.id()) &&
                    link.relation() == Relation.SUPPORT).toList();
            var contradiction = input.links().stream().filter(link -> link.elementId().equals(element.id()) &&
                    link.relation() == Relation.CONTRADICT).toList();
            FindingState state = support.isEmpty() ? (contradiction.isEmpty() ? FindingState.MISSING
                    : FindingState.CONTRADICTED) : contradiction.isEmpty() ? FindingState.SUPPORTED
                    : FindingState.CONFLICT;
            List<String> itemGaps = state == FindingState.MISSING ? List.of("ELEMENT_INPUT_MISSING")
                    : state == FindingState.CONFLICT ? List.of("CONFLICT_REVIEW_REQUIRED") : List.of();
            if (element.required() && state != FindingState.SUPPORTED)
                gaps.add("ELEMENT:" + element.id() + ":" + state);
            findings.add(new ElementFinding(element.id(), state, support, contradiction, itemGaps));
        }
        if (input.right() == null || !"VERIFIED_AT_BEHAVIOR_TIME".equals(input.right().status()) ||
                !input.behaviorAt().equals(input.right().behaviorAt()) ||
                blank(input.right().ref()) || blank(input.right().version()) ||
                blank(input.right().sourceRef())) gaps.add("RIGHT_AT_BEHAVIOR_TIME_UNKNOWN");
        if (rule.defenses() == null || (!rule.noDefensesConfirmed() && rule.defenses().isEmpty()) ||
                (rule.noDefensesConfirmed() && blank(rule.noDefensesSource())))
            gaps.add("DEFENSE_STANDARD_UNKNOWN");
        if (input.defenseRefs().isEmpty() && !rule.noDefensesConfirmed())
            gaps.add("DEFENSE_UNASSESSED");
        else if (!input.defenseRefs().isEmpty()) gaps.add("DEFENSE_REVIEW_REQUIRED");
        if (rule.basis() == null || !"VERIFIED".equals(rule.basis().effectStatus()))
            gaps.add("BASIS_EFFECT_UNKNOWN");
        boolean unresolved = gaps.stream().anyMatch(gap -> !gap.endsWith(":CONTRADICTED"));
        Outcome outcome = unresolved ? Outcome.INSUFFICIENT :
                findings.stream().anyMatch(f -> f.state() == FindingState.CONTRADICTED)
                        ? Outcome.EXCLUSION_INDICATED : Outcome.SUSPECTED;
        return new Trial(List.copyOf(findings), List.copyOf(gaps), outcome);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
