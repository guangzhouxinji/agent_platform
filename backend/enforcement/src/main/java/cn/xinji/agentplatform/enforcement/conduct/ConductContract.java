package cn.xinji.agentplatform.enforcement.conduct;

import java.util.List;

public final class ConductContract {
    private ConductContract() { }

    public enum Behavior { PATENT_INFRINGEMENT, COUNTERFEIT_PATENT, TRADEMARK_INFRINGEMENT }
    public enum Relation { SUPPORT, CONTRADICT }
    public enum FindingState { SUPPORTED, CONTRADICTED, MISSING, CONFLICT }
    public enum Outcome { SUSPECTED, EXCLUSION_INDICATED, INSUFFICIENT }
    public enum Applicability { MATCH, MISMATCH, UNKNOWN }
    public record ScopeVerification(Applicability state, String sourceRef, String version) { }

    public record Element(String id, String description, boolean required) { }
    public record Basis(String ref, String version, String locator, String effectStatus,
                        String scope, String jurisdiction, String procedure) { }
    public record Rule(String id, long version, String organization, Behavior behavior,
                       String state, Basis basis, List<Element> elements,
                       List<String> defenses, boolean noDefensesConfirmed,
                       String noDefensesSource, String reviewRef, String author) { }
    public record Fact(String ref, String kind, String sourceRef, String locator) { }
    public record Link(String elementId, String factRef, String evidenceRef,
                       String locator, Relation relation) { }
    public record RightAtTime(String ref, String version, String status,
                              String behaviorAt, String sourceRef) { }
    public record Input(String id, String organization, String caseRef, String caseVersion,
                        String evidenceOrganizationId, long evidenceOrganizationVersion,
                        String ruleId, long ruleVersion, Behavior behavior, String behaviorAt,
                        RightAtTime right, List<Fact> facts, List<Link> links,
                        List<String> defenseRefs) { }
    public record ElementFinding(String elementId, FindingState state,
                                 List<Link> support, List<Link> contradiction,
                                 List<String> gaps) { }
    public record Report(String id, long version, String organization, String caseRef,
                         String caseVersion, String evidenceOrganizationId,
                         long evidenceOrganizationVersion, String ruleId, long ruleVersion,
                         Behavior behavior, ScopeVerification scope, String rightRef, String rightVersion,
                         String rightStatus, List<Fact> facts, List<ElementFinding> elements,
                         List<String> gaps, Outcome outcome, String reviewState,
                         String reviewedBy, String reviewReason, long previousVersion) { }
    public record Receipt(String requestId, String operation, String resultId, long version) { }
    public record Trial(List<ElementFinding> elements, List<String> gaps, Outcome outcome) { }
}
