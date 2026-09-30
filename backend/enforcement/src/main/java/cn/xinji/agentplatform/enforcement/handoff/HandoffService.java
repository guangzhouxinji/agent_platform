package cn.xinji.agentplatform.enforcement.handoff;

import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** A saved request is not a human acceptance; only a claimed event is. */
public final class HandoffService {
    public interface Upstream {
        EvidenceOrganizationStore.Revision read(String actor, String id, long version);
    }
    public interface Access {
        void require(String actor, String org, String type, String id, long version,
                     String action, String purpose);
    }
    public record PackageInput(String id, String evidenceOrganizationId,
                               long evidenceOrganizationVersion, String purposeRef) { }
    public record RevisionInput(long expectedVersion, String evidenceOrganizationId,
                                long evidenceOrganizationVersion, String reason) { }
    public record RequestInput(String id, String packageId, long packageVersion,
                               String purposeRef) { }
    public record ClaimInput(long expectedVersion) { }
    public record ResultInput(long expectedVersion, String opinion, String reason) { }
    public record ReturnInput(long expectedVersion, String reason) { }
    public record WithdrawalInput(long expectedVersion, String reason) { }

    private final HandoffStore store;
    private final Upstream upstream;
    private final Access access;

    public HandoffService(HandoffStore store, Upstream upstream, Access access) {
        this.store = store;
        this.upstream = upstream;
        this.access = access;
    }

    public HandoffStore.Receipt create(String actor, String key, PackageInput input) {
        if (blank(actor) || blank(key) || input == null || blank(input.id()) ||
                blank(input.evidenceOrganizationId()) || input.evidenceOrganizationVersion() < 1 ||
                blank(input.purposeRef())) throw new IllegalArgumentException("Invalid package");
        var evidence = upstream.read(actor, input.evidenceOrganizationId(),
                input.evidenceOrganizationVersion());
        access.require(actor, evidence.organization(), "WORK_PACKAGE", input.id(), 1,
                "CREATE", "ANALYZE");
        var gaps = evidence.matters().stream().filter(m -> !"PRESENT".equals(m.coverage()))
                .map(m -> m.id() + ":" + m.coverage()).toList();
        var digest = hash(evidence.evidenceSet().toString());
        var value = new HandoffStore.Package(input.id(), 1, input.id(), evidence.organization(),
                evidence.caseRef(), evidence.caseVersion(), evidence.id(), evidence.version(),
                digest, gaps, evidence.unprocessed(), input.purposeRef(), hash(evidence.toString()), actor);
        var receipt = store.command(key, hash(input.toString()), actor, "CREATE_PACKAGE", () -> {
            upstream.read(actor, input.evidenceOrganizationId(), input.evidenceOrganizationVersion());
            access.require(actor, evidence.organization(), "WORK_PACKAGE", input.id(), 1,
                    "CREATE", "ANALYZE");
            store.addPackage(value);
            return new HandoffStore.Receipt(key, value.id(), value.version(), "CREATE_PACKAGE");
        });
        readPackage(actor, receipt.objectId(), receipt.version());
        return receipt;
    }

    public HandoffStore.Package readPackage(String actor, String id, long version) {
        var value = store.findPackage(id, version)
                .orElseThrow(() -> new SecurityException("Package unavailable"));
        access.require(actor, value.organization(), "WORK_PACKAGE", id, version, "READ", "DISPLAY");
        upstream.read(actor, value.evidenceOrganizationId(), value.evidenceOrganizationVersion());
        return value;
    }

    public HandoffStore.Receipt revise(String actor, String key, String id, RevisionInput input) {
        if (blank(actor) || blank(key) || blank(id) || input == null || input.expectedVersion() < 1 ||
                blank(input.evidenceOrganizationId()) || input.evidenceOrganizationVersion() < 1 ||
                blank(input.reason())) throw new IllegalArgumentException("Invalid package revision");
        var prior = readPackage(actor, id, input.expectedVersion());
        if (input.evidenceOrganizationId().equals(prior.evidenceOrganizationId()) &&
                input.evidenceOrganizationVersion() == prior.evidenceOrganizationVersion())
            throw new IllegalArgumentException("Changed evidence revision required");
        var evidence = upstream.read(actor, input.evidenceOrganizationId(),
                input.evidenceOrganizationVersion());
        if (!prior.organization().equals(evidence.organization()) ||
                !prior.caseRef().equals(evidence.caseRef()) ||
                !prior.caseVersion().equals(evidence.caseVersion()))
            throw new SecurityException("Package target changed");
        access.require(actor, prior.organization(), "WORK_PACKAGE", id, input.expectedVersion(),
                "REVISE", "ANALYZE");
        var next = new HandoffStore.Package(id, prior.version() + 1, prior.workId(),
                prior.organization(), prior.caseRef(), prior.caseVersion(), evidence.id(),
                evidence.version(), hash(evidence.evidenceSet().toString()),
                evidence.matters().stream().filter(m -> !"PRESENT".equals(m.coverage()))
                        .map(m -> m.id() + ":" + m.coverage()).toList(),
                evidence.unprocessed(), prior.purposeRef(), hash(evidence.toString()), actor);
        var receipt = store.command(key, hash(id + input), actor, "REVISE_PACKAGE", () -> {
            if (store.latestPackage(id) != input.expectedVersion())
                throw new IllegalArgumentException("Package version conflict");
            readPackage(actor, id, input.expectedVersion());
            upstream.read(actor, input.evidenceOrganizationId(), input.evidenceOrganizationVersion());
            access.require(actor, prior.organization(), "WORK_PACKAGE", id, input.expectedVersion(),
                    "REVISE", "ANALYZE");
            store.addPackage(next);
            return new HandoffStore.Receipt(key, id, next.version(), "REVISE_PACKAGE");
        });
        readPackage(actor, id, receipt.version());
        return receipt;
    }

    public HandoffStore.Receipt request(String actor, String key, RequestInput input) {
        if (blank(actor) || blank(key) || input == null || blank(input.id()) ||
                blank(input.packageId()) || input.packageVersion() < 1 || blank(input.purposeRef()))
            throw new IllegalArgumentException("Invalid request");
        var pack = readPackage(actor, input.packageId(), input.packageVersion());
        if (!pack.purposeRef().equals(input.purposeRef()))
            throw new SecurityException("Request purpose changed");
        access.require(actor, pack.organization(), "HUMAN_REQUEST", input.id(), 1,
                "REQUEST", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(input.toString()), actor, "REQUEST_HUMAN", () -> {
            readPackage(actor, pack.id(), pack.version());
            access.require(actor, pack.organization(), "HUMAN_REQUEST", input.id(), 1,
                    "REQUEST", "HUMAN_REVIEW");
            store.addRequest(new HandoffStore.Request(input.id(), pack.id(), pack.version(),
                    1, "PENDING", input.purposeRef(), null));
            return new HandoffStore.Receipt(key, input.id(), 1, "REQUEST_HUMAN");
        });
        readRequest(actor, receipt.objectId());
        return receipt;
    }

    public HandoffStore.Request readRequest(String actor, String id) {
        var value = store.findRequest(id)
                .orElseThrow(() -> new SecurityException("Request unavailable"));
        readPackage(actor, value.packageId(), value.packageVersion());
        var pack = store.findPackage(value.packageId(), value.packageVersion()).orElseThrow();
        access.require(actor, pack.organization(), "HUMAN_REQUEST", id, 1,
                "READ", "HUMAN_REVIEW");
        return value;
    }

    public List<HandoffStore.Request> tasks(String actor) {
        if (blank(actor)) throw new SecurityException("Actor required");
        return store.findRequests().stream().filter(request -> {
            try {
                readRequest(actor, request.id());
                var pack = readPackage(actor, request.packageId(), request.packageVersion());
                access.require(actor, pack.organization(), "HUMAN_TASK", request.id(), 1,
                        "CLAIM", "HUMAN_REVIEW");
                return true;
            } catch (SecurityException denied) { return false; }
        }).toList();
    }

    public HandoffStore.Receipt claim(String actor, String key, String id, ClaimInput input) {
        if (blank(actor) || blank(key) || blank(id) || input == null || input.expectedVersion() < 1)
            throw new IllegalArgumentException("Invalid claim");
        var prior = readRequest(actor, id);
        var pack = readPackage(actor, prior.packageId(), prior.packageVersion());
        access.require(actor, pack.organization(), "HUMAN_TASK", id, 1,
                "CLAIM", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(id + input), actor, "CLAIM_HUMAN", () -> {
            readPackage(actor, pack.id(), pack.version());
            access.require(actor, pack.organization(), "HUMAN_TASK", id, 1,
                    "CLAIM", "HUMAN_REVIEW");
            var current = store.findRequest(id).orElseThrow();
            if (current.version() != input.expectedVersion() || !"PENDING".equals(current.state()))
                throw new IllegalArgumentException("Claim version conflict");
            store.updateRequest(current, new HandoffStore.Request(id, pack.id(), pack.version(),
                    current.version() + 1, "ACCEPTED", current.purposeRef(), actor));
            return new HandoffStore.Receipt(key, id, current.version() + 1, "CLAIM_HUMAN");
        });
        readRequest(actor, id);
        return receipt;
    }

    public HandoffStore.Receipt submit(String actor, String key, String id, ResultInput input) {
        if (blank(actor) || blank(key) || blank(id) || input == null || input.expectedVersion() < 1 ||
                blank(input.opinion()) || blank(input.reason()))
            throw new IllegalArgumentException("Invalid result");
        var prior = readRequest(actor, id);
        var pack = readPackage(actor, prior.packageId(), prior.packageVersion());
        access.require(actor, pack.organization(), "HUMAN_TASK", id, 1,
                "SUBMIT", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(id + input), actor, "SUBMIT_HUMAN", () -> {
            readPackage(actor, pack.id(), pack.version());
            access.require(actor, pack.organization(), "HUMAN_TASK", id, 1,
                    "SUBMIT", "HUMAN_REVIEW");
            var current = store.findRequest(id).orElseThrow();
            if (current.version() != input.expectedVersion() || !"ACCEPTED".equals(current.state()) ||
                    !actor.equals(current.claimant()))
                throw new IllegalArgumentException("Result version or claimant conflict");
            store.addResult(new HandoffStore.Result(id, 1, pack.id(), pack.version(), actor,
                    input.opinion(), input.reason()));
            store.updateRequest(current, new HandoffStore.Request(id, pack.id(), pack.version(),
                    current.version() + 1, "RETURNED", current.purposeRef(), actor));
            return new HandoffStore.Receipt(key, id, current.version() + 1, "SUBMIT_HUMAN");
        });
        readRequest(actor, id);
        return receipt;
    }

    public HandoffStore.Result readResult(String actor, String id) {
        var request = readRequest(actor, id);
        var pack = readPackage(actor, request.packageId(), request.packageVersion());
        access.require(actor, pack.organization(), "HUMAN_REQUEST", id, 1,
                "READ_RESULT", "HUMAN_REVIEW");
        return store.findResult(id).orElseThrow(() -> new SecurityException("Result unavailable"));
    }

    public HandoffStore.Receipt returnTask(String actor, String key, String id, ReturnInput input) {
        if (blank(actor) || blank(key) || blank(id) || input == null || input.expectedVersion() < 1 ||
                blank(input.reason())) throw new IllegalArgumentException("Return reason required");
        var prior = readRequest(actor, id);
        var pack = readPackage(actor, prior.packageId(), prior.packageVersion());
        access.require(actor, pack.organization(), "HUMAN_TASK", id, 1, "SUBMIT", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(id + input), actor, "RETURN_HUMAN", input.reason(), () -> {
            readPackage(actor, pack.id(), pack.version());
            access.require(actor, pack.organization(), "HUMAN_TASK", id, 1, "SUBMIT", "HUMAN_REVIEW");
            var current = store.findRequest(id).orElseThrow();
            if (current.version() != input.expectedVersion() || !"ACCEPTED".equals(current.state()) ||
                    !actor.equals(current.claimant()))
                throw new IllegalArgumentException("Return version or claimant conflict");
            store.updateRequest(current, new HandoffStore.Request(id, pack.id(), pack.version(),
                    current.version() + 1, "PENDING", current.purposeRef(), null));
            return new HandoffStore.Receipt(key, id, current.version() + 1, "RETURN_HUMAN");
        });
        readRequest(actor, id);
        return receipt;
    }

    public HandoffStore.Receipt withdraw(String actor, String key, String id, WithdrawalInput input) {
        if (blank(actor) || blank(key) || blank(id) || input == null || input.expectedVersion() < 1 ||
                blank(input.reason())) throw new IllegalArgumentException("Withdrawal reason required");
        var prior = readRequest(actor, id);
        var pack = readPackage(actor, prior.packageId(), prior.packageVersion());
        access.require(actor, pack.organization(), "HUMAN_REQUEST", id, 1,
                "WITHDRAW", "HUMAN_REVIEW");
        var receipt = store.command(key, hash(id + input), actor, "WITHDRAW_HUMAN", input.reason(), () -> {
            readPackage(actor, pack.id(), pack.version());
            access.require(actor, pack.organization(), "HUMAN_REQUEST", id, 1,
                    "WITHDRAW", "HUMAN_REVIEW");
            var current = store.findRequest(id).orElseThrow();
            if (current.version() != input.expectedVersion() ||
                    !("PENDING".equals(current.state()) || "ACCEPTED".equals(current.state())))
                throw new IllegalArgumentException("Withdrawal version conflict");
            store.updateRequest(current, new HandoffStore.Request(id, pack.id(), pack.version(),
                    current.version() + 1, "WITHDRAWN", current.purposeRef(), current.claimant()));
            return new HandoffStore.Receipt(key, id, current.version() + 1, "WITHDRAW_HUMAN");
        });
        readRequest(actor, id);
        return receipt;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
