package cn.xinji.agentplatform.enforcement.evidence;

import java.util.Optional;

public interface EvidenceRuleStore {
    record Matter(String id, boolean required, String expectedMaterial) { }
    record Rule(String id, long revision, String organization, String caseType,
                String basisRef, String state, java.util.List<Matter> matters) { }
    record Receipt(String requestId, String operation, String resultId, long revision) { }
    record PublicationReview(String ruleId, long revision, String basisVersion, String scope,
                             String reviewRef, String reviewDigest, String reviewedBy) { }

    Optional<Rule> rule(String id, long revision);
    Optional<Rule> latest(String id);
    Optional<PublicationReview> publication(String id, long revision);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    java.util.function.Supplier<Receipt> action);
    void add(Rule rule, String actor);
    void disable(String id, long revision);
    void recordReview(String id, long revision, String basisVersion, String scope,
                      String reviewRef, String reviewDigest, String actor);
}
