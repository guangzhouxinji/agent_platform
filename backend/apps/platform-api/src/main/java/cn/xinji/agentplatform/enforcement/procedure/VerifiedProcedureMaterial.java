package cn.xinji.agentplatform.enforcement.procedure;

import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/** Resolves exact upstream product originals without taking ownership of MATERIAL state. */
final class VerifiedProcedureMaterial implements FileProcedureSource.MaterialVerification {
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final MaterialAccess access;
    private final String actor;
    private final Clock clock;

    VerifiedProcedureMaterial(MaterialRepository materials, MaterialFiles files, MaterialAccess access,
                              String actor, Clock clock) {
        this.materials = materials;
        this.files = files;
        this.access = access;
        this.actor = actor;
        this.clock = clock;
    }

    @Override public void require(String ref, String organization) {
        try {
            var pair = ref.split("@", -1);
            if (pair.length != 2 || pair[0].isBlank()) throw new SecurityException("Exact material required");
            long version = Long.parseLong(pair[1]);
            if (version < 1) throw new SecurityException("Exact material version required");
            var material = materials.material(pair[0])
                    .orElseThrow(() -> new SecurityException("Procedure material unavailable"));
            if (!"VERIFIED".equals(material.state()) || material.version() != version ||
                    !organization.equals(material.organization()) ||
                    !material.uses().contains("ANALYZE") || !material.expiresAt().isAfter(clock.instant()))
                throw new SecurityException("Procedure material use unavailable");
            access.require(actor, organization, "EVIDENCE", material.id(), version,
                    "READ", "ANALYZE");
            var actions = materials.materialActions(material.id());
            if (actions.size() != 3 || !"UPLOAD".equals(actions.get(0).action()) ||
                    !"COLLECT_ATTEST".equals(actions.get(1).action()) ||
                    !"VERIFY".equals(actions.get(2).action()) ||
                    !material.collector().equals(actions.get(1).actor()) ||
                    actions.get(1).actor().equals(actions.get(2).actor()))
                throw new SecurityException("Procedure material collection unavailable");
            var clue = materials.clue(material.clueId()).orElseThrow();
            var observation = materials.observation(clue.observationId()).orElseThrow();
            var source = materials.source(observation.sourceId()).orElseThrow();
            if (!source.active() || !source.expiresAt().isAfter(clock.instant()) ||
                    !source.uses().contains("ANALYZE") || !organization.equals(source.organization()))
                throw new SecurityException("Procedure source use unavailable");
            access.requireSource(source.id(), "ANALYZE", source.epoch());
            var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(files.read(material.fileKey())));
            if (!material.sha256().equals(digest)) throw new SecurityException("Procedure original changed");
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception invalid) {
            throw new IllegalStateException("Procedure original verification unavailable", invalid);
        }
    }
}
