package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

/** Reads the frozen Evidence version and original bytes; it never edits upstream objects. */
final class LockedConductEvidence implements ConductAnalysisService.LockedEvidence {
    private final EvidenceOrganizationService organizations;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final ProductEnforcementAccess access;
    private final ObjectMapper json;
    private final String caseInput;

    LockedConductEvidence(EvidenceOrganizationService organizations, MaterialRepository materials,
                          MaterialFiles files, ProductEnforcementAccess access,
                          ObjectMapper json, String caseInput) {
        this.organizations = organizations;
        this.materials = materials;
        this.files = files;
        this.access = access;
        this.json = json;
        this.caseInput = caseInput;
    }

    @Override public void require(String actor, Input input) {
        var revision = checked(actor, input.evidenceOrganizationId(),
                input.evidenceOrganizationVersion(), input.organization(),
                input.caseRef(), input.caseVersion());
        var refs = new HashSet<String>();
        for (var ref : revision.evidenceSet()) refs.add(ref.id());
        for (var fact : input.facts()) {
            if (!refs.contains(fact.sourceRef())) throw new SecurityException("Fact source outside locked evidence");
            if ("HUMAN_CONFIRMED".equals(fact.kind()))
                throw new SecurityException("Confirmed fact authority unavailable");
        }
        for (var link : input.links()) {
            if (!refs.contains(link.evidenceRef()) ||
                    revision.links().stream().noneMatch(locked ->
                            locked.evidenceId().equals(link.evidenceRef()) &&
                            locked.locator().equals(link.locator())))
                throw new SecurityException("Conduct relation outside locked evidence");
        }
        if (input.right() != null && "VERIFIED_AT_BEHAVIOR_TIME".equals(input.right().status()))
            throw new SecurityException("Historical right authority unavailable");
    }

    @Override public void require(String actor, Report report) {
        checked(actor, report.evidenceOrganizationId(), report.evidenceOrganizationVersion(),
                report.organization(), report.caseRef(), report.caseVersion());
        if ("VERIFIED_AT_BEHAVIOR_TIME".equals(report.rightStatus()))
            throw new SecurityException("Historical right authority unavailable");
    }

    private EvidenceOrganizationStore.Revision checked(String actor, String id, long version,
                                                        String org, String caseRef, String caseVersion) {
        var revision = organizations.read(actor, id, version);
        if (!revision.organization().equals(org) || !revision.caseRef().equals(caseRef) ||
                !revision.caseVersion().equals(caseVersion))
            throw new SecurityException("Evidence case mismatch");
        if (caseInput == null || caseInput.isBlank())
            throw new IllegalStateException("Evidence case source unavailable");
        new VerifiedEvidenceSource(Path.of(caseInput), json, materials, files, access, Clock.systemUTC())
                .read(caseRef, caseVersion, revision.evidenceSet());
        return revision;
    }
}
