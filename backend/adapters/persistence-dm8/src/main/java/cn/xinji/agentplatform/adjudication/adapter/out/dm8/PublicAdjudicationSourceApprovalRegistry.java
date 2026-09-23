package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

@FunctionalInterface
public interface PublicAdjudicationSourceApprovalRegistry {
    boolean approves(PublicAdjudicationReferenceImporter.SourceRegistration source);

    static PublicAdjudicationSourceApprovalRegistry denyAll() { return source -> false; }

    static PublicAdjudicationSourceApprovalRegistry approvedPublicJudgmentPilot() {
        var approved = PublicAdjudicationReferenceImporter.approvedPublicJudgmentPilot();
        return approved::equals;
    }
}
