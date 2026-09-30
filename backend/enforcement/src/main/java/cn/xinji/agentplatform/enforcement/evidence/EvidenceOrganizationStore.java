package cn.xinji.agentplatform.enforcement.evidence;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface EvidenceOrganizationStore {
    record Link(String matterId, String evidenceId, long evidenceVersion, String sha256,
                String locator, String value, String relation, String originKind, String reason) { }
    record Matter(String id, String coverage) { }
    record Time(String evidenceId, String eventTime, String collectionTime, String uploadTime,
                String eventUnknown, String collectionUnknown, String uploadUnknown) { }
    record Exclusion(String evidenceId, String reason, String actor) { }
    record Revision(String id, long version, String organization, String caseRef, String caseVersion,
                    String ruleId, long ruleVersion, String state, List<EvidenceTrialSource.Ref> evidenceSet,
                    List<Matter> matters, List<Link> links, List<Time> times,
                    List<String> unrelated, List<String> unprocessed, String lastActor,
                    String reviewedBy, String reviewReason, List<Exclusion> exclusions) {
        public Revision {
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        }
    }
    record Receipt(String requestId, String id, long revision) { }

    Optional<Revision> revision(String id, long version);
    long latest(String id);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    Supplier<Receipt> body);
    void append(Revision revision, String actor);
}
