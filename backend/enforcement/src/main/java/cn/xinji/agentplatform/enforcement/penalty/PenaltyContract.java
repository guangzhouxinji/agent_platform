package cn.xinji.agentplatform.enforcement.penalty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The single frozen ENF-15/16 product contract. Review is not an official penalty decision. */
public final class PenaltyContract {
    private PenaltyContract() {}
    public enum RuleState { DRAFT, ACTIVE, DISABLED }
    public enum SuggestionState { WAITING_INPUT, PENDING_REVIEW, REVIEWED, RETURNED }
    public record Ref(String kind,String id,long revision,String sha256) {}
    public record Condition(String kind,String description,List<Ref> evidence,String basisRef,long basisRevision) {}
    public record ConfirmedFact(String ref,String sourceRef,String sourceVersion,String locator,
                                String state,String confirmedBy,String confirmationRef) {}
    public record Scope(String conductType,String authorityRef,String procedure,String region,
                        LocalDate effectiveFrom,LocalDate effectiveUntil) {}
    public record Rule(String id,long revision,String organization,String name,RuleState state,Scope scope,
                       String discretionBasis,List<Condition> conditions,PenaltyPublishChecks.Input calculation) {}
    public record CaseInput(String caseId,long caseRevision,String organization,LocalDate applicableDate,
                            String conductType,String procedure,String region,List<Ref> dependencies,
                            Map<String,PenaltyFormula.Amount> amounts,List<Ref> factors,List<ConfirmedFact> confirmedFacts) {}
    /** Supplied by existing authorized upstream services, never by a request's confirmation booleans. */
    public record CaseStatus(boolean authorityApplicable,boolean procedureApplicable,boolean factsConfirmed,
                             List<String> gaps,List<Ref> resolvedDependencies) {}
    public record Option(String ruleId,long ruleRevision,String basisRef,long basisRevision,
                         PenaltyFormula.Range range,PenaltyFormula.Semantics semantics,
                         PenaltyFormula.Result calculation,BigDecimal adjustedAmount,String reason,String adjustmentBasis) {}
    public record Suggestion(String id,long revision,String organization,SuggestionState state,CaseInput input,
                             List<Ref> ruleRefs,List<Option> options,List<String> gaps,String reviewReason,
                             String reviewedBy,Instant reviewedAt) {}
    public record Receipt(String requestId,String operation,String resourceKind,String resourceId,long revision) {}
    public interface Access { void require(String actor,String organization,String kind,String id,long revision,String action); }
    public interface Inputs extends PenaltyPublishChecks.Inputs {
        CaseStatus caseStatus(String actor,CaseInput input);
        void assertCaseCurrent(String actor,CaseInput input);
        void assertRuleCurrent(String actor,Rule rule);
        default void assertRuleTransitionCompleted(String actor,Rule successor) { assertRuleCurrent(actor,successor); }
    }
    public interface Store {
        Optional<Rule> rule(String id,long revision);
        Optional<Rule> latestRule(String id);
        List<Rule> ruleHistory(String id);
        Optional<Suggestion> suggestion(String id,long revision);
        Optional<Suggestion> latestSuggestion(String id);
        List<Suggestion> suggestionHistory(String id);
        void appendRule(Rule rule,long expected,String actor);
        void appendSuggestion(Suggestion suggestion,long expected,String actor);
        Receipt command(String key,String fingerprint,String actor,String operation,Supplier<Receipt> mutation);
    }
}
