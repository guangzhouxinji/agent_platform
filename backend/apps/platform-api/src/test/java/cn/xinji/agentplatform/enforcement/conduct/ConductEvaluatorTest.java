package cn.xinji.agentplatform.enforcement.conduct;

import java.util.List;
import org.junit.jupiter.api.Test;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ConductEvaluatorTest {
    private final ConductEvaluator evaluator = new ConductEvaluator();

    private Rule rule(Behavior behavior) {
        return new Rule("rule", 1, "org", behavior, "DRAFT",
                new Basis("basis", "1", "section 1", "UNVERIFIED", "scope", "district", "procedure"),
                List.of(new Element("RIGHT_AT_BEHAVIOR_TIME", "historical right", true),
                        new Element("BEHAVIOR_FACT", "behavior", true)),
                List.of("POSSIBLE_DEFENSE"), false, null, null, "author");
    }

    private Input input(List<Link> links, RightAtTime right, List<String> defenses) {
        return new Input("analysis", "org", "case", "1", "evidence", 1, "rule", 1,
                Behavior.PATENT_INFRINGEMENT,
                "2026-09-14", right,
                List.of(new Fact("fact-a", "MACHINE_CANDIDATE", "record-a", "line 3"),
                        new Fact("fact-b", "PARTY_STATEMENT", "record-b", "line 3")), links, defenses);
    }

    @Test void historicalRightUnknownAndContradictionDoNotBecomeInfringement() {
        var links = List.of(new Link("BEHAVIOR_FACT", "fact-a", "record-a", "line 3", Relation.SUPPORT),
                new Link("BEHAVIOR_FACT", "fact-b", "record-b", "line 3", Relation.CONTRADICT));
        var trial = evaluator.evaluate(rule(Behavior.TRADEMARK_INFRINGEMENT), input(links, null, List.of()));
        assertEquals(Outcome.INSUFFICIENT, trial.outcome());
        assertEquals(FindingState.CONFLICT, trial.elements().get(1).state());
        assertEquals(1, trial.elements().get(1).support().size());
        assertEquals(1, trial.elements().get(1).contradiction().size());
        assertTrue(trial.gaps().contains("RIGHT_AT_BEHAVIOR_TIME_UNKNOWN"));
        assertTrue(trial.gaps().contains("DEFENSE_UNASSESSED"));
        assertTrue(trial.gaps().contains("BASIS_EFFECT_UNKNOWN"));
    }

    @Test void behaviorBranchesRemainIndependentAndUnverified() {
        var patent = evaluator.evaluate(rule(Behavior.PATENT_INFRINGEMENT), input(List.of(), null, List.of()));
        var counterfeit = evaluator.evaluate(new Rule("counterfeit", 1, "org", Behavior.COUNTERFEIT_PATENT,
                "DRAFT", null, List.of(new Element("RIGHT_REPRESENTATION", "representation", true)),
                List.of(), false, null, null, "author"), input(List.of(), null, List.of()));
        assertEquals("RIGHT_AT_BEHAVIOR_TIME", patent.elements().getFirst().elementId());
        assertEquals("RIGHT_REPRESENTATION", counterfeit.elements().getFirst().elementId());
        assertEquals(Outcome.INSUFFICIENT, counterfeit.outcome());
    }

    @Test void unknownEvidenceRefRejectsWithoutResult() {
        var link = new Link("BEHAVIOR_FACT", "missing", "record-a", "line 3", Relation.SUPPORT);
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate(rule(Behavior.PATENT_INFRINGEMENT), input(List.of(link), null, List.of())));
    }

    @Test void explicitContradictionWithoutUnknownsIndicatesExclusionOnly() {
        var rule = new Rule("rule", 1, "org", Behavior.COUNTERFEIT_PATENT, "ACTIVE",
                new Basis("project", "1", "section 1", "VERIFIED", "scope", "area", "procedure"),
                List.of(new Element("BEHAVIOR_FACT", "behavior", true)), List.of(),
                true, "confirmed exception scope", "review", "author");
        var right = new RightAtTime("right", "1", "VERIFIED_AT_BEHAVIOR_TIME",
                "2026-09-14", "project-source");
        var result = evaluator.evaluate(rule, input(List.of(new Link("BEHAVIOR_FACT", "fact-b",
                "record-b", "line 3", Relation.CONTRADICT)), right, List.of()));
        assertEquals(Outcome.EXCLUSION_INDICATED, result.outcome());
        assertEquals(FindingState.CONTRADICTED, result.elements().getFirst().state());
    }

    @Test void unlinkedSimilarFactCannotSatisfyElementOrInventBehaviorBranch() {
        var candidate = input(List.of(), null, List.of());
        var patent = evaluator.evaluate(rule(Behavior.PATENT_INFRINGEMENT), candidate);
        var counterfeit = evaluator.evaluate(new Rule("counterfeit", 1, "org",
                Behavior.COUNTERFEIT_PATENT, "DRAFT", null,
                List.of(new Element("RIGHT_REPRESENTATION", "representation", true)),
                List.of("POSSIBLE_DEFENSE"), false, null, null, "author"), candidate);
        assertEquals(FindingState.MISSING, patent.elements().get(1).state());
        assertEquals(FindingState.MISSING, counterfeit.elements().getFirst().state());
        assertTrue(counterfeit.gaps().contains("ELEMENT:RIGHT_REPRESENTATION:MISSING"));
        assertEquals(Outcome.INSUFFICIENT, patent.outcome());
        assertEquals(Outcome.INSUFFICIENT, counterfeit.outcome());
    }

    @Test void suppliedDefenseAndContradictoryEvidenceRemainVisibleWithoutSeverity() {
        var candidate = input(List.of(
                new Link("BEHAVIOR_FACT", "fact-a", "record-a", "line 3", Relation.SUPPORT),
                new Link("BEHAVIOR_FACT", "fact-b", "record-b", "line 3", Relation.CONTRADICT)),
                new RightAtTime("right", "1", "VERIFIED_AT_BEHAVIOR_TIME",
                        "2026-09-14", "source"), List.of("defense-record"));
        var result = evaluator.evaluate(rule(Behavior.PATENT_INFRINGEMENT), candidate);
        assertEquals(FindingState.CONFLICT, result.elements().get(1).state());
        assertEquals(1, result.elements().get(1).support().size());
        assertEquals(1, result.elements().get(1).contradiction().size());
        assertTrue(result.gaps().contains("DEFENSE_REVIEW_REQUIRED"));
        assertEquals(Outcome.INSUFFICIENT, result.outcome());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result).has("severityGrade"));
    }
}
