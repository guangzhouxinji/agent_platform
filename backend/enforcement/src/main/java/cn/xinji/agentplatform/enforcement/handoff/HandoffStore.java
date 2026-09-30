package cn.xinji.agentplatform.enforcement.handoff;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface HandoffStore {
    record Package(String id, long version, String workId, String organization,
                   String caseRef, String caseVersion, String evidenceOrganizationId,
                   long evidenceOrganizationVersion, String evidenceSetDigest,
                   List<String> gaps, List<String> unprocessed, String purposeRef,
                   String contentDigest, String creator) { }
    record Request(String id, String packageId, long packageVersion, long version,
                   String state, String purposeRef, String claimant) { }
    record Result(String requestId, long version, String packageId, long packageVersion,
                  String actor, String opinion, String reason) { }
    record Receipt(String requestId, String objectId, long version, String operation) { }

    Optional<Package> findPackage(String id, long version);
    long latestPackage(String id);
    Optional<Request> findRequest(String id);
    List<Request> findRequests();
    Optional<Result> findResult(String requestId);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    Supplier<Receipt> body);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    String reason, Supplier<Receipt> body);
    void addPackage(Package value);
    void addRequest(Request value);
    void updateRequest(Request previous, Request next);
    void addResult(Result value);
}
