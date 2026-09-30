package cn.xinji.agentplatform.enforcement.material;

import static cn.xinji.agentplatform.enforcement.material.MaterialDigests.hash;

/** Publication evidence is checked against immutable saved source observations. */
final class MaterialRuleReview {
    private MaterialRuleReview() { }

    static MaterialWork.RuleReview check(MaterialRepository repository, MaterialWork.Rule rule,
                                         String positiveId, String negativeId) {
        if (positiveId == null || negativeId == null || positiveId.equals(negativeId))
            throw new IllegalArgumentException("Distinct positive and negative observations required");
        var positive = repository.observation(positiveId)
                .orElseThrow(() -> new IllegalArgumentException("Positive observation unavailable"));
        var negative = repository.observation(negativeId)
                .orElseThrow(() -> new IllegalArgumentException("Negative observation unavailable"));
        if (!rule.sourceId().equals(positive.sourceId()) || !rule.sourceId().equals(negative.sourceId())
                || !rule.organization().equals(positive.organization())
                || !rule.organization().equals(negative.organization())
                || !positive.textSha256().equals(hash(positive.text()))
                || !negative.textSha256().equals(hash(negative.text()))
                || !positive.text().contains(rule.literal()) || negative.text().contains(rule.literal()))
            throw new IllegalArgumentException("Rule examples do not prove the proposed condition within scope");
        return new MaterialWork.RuleReview(positive.id(), negative.id(),
                positive.textSha256(), negative.textSha256());
    }

}
