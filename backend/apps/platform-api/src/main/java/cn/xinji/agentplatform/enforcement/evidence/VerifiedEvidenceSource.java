package cn.xinji.agentplatform.enforcement.evidence;

import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Case adapter binds exact IDs; MATERIAL remains the sole owner of verified file bytes. */
public final class VerifiedEvidenceSource implements EvidenceTrialSource {
    private final Path caseFile;
    private final ObjectMapper json;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final MaterialAccess current;
    private final Clock clock;

    public VerifiedEvidenceSource(Path caseFile, ObjectMapper json, MaterialRepository materials,
                                  MaterialFiles files, MaterialAccess current, Clock clock) {
        this.caseFile = caseFile;
        this.json = json;
        this.materials = materials;
        this.files = files;
        this.current = current;
        this.clock = clock;
    }

    @Override public Snapshot read(String caseRef, String version, List<Ref> refs) {
        try {
            var caseInput = json.readTree(Files.readAllBytes(caseFile));
            if (!caseRef.equals(caseInput.path("caseRef").asText()) ||
                    !version.equals(caseInput.path("version").asText()) ||
                    caseInput.path("caseType").asText().isBlank() ||
                    caseInput.path("organization").asText().isBlank() ||
                    caseInput.path("eventRef").asText().isBlank())
                throw new SecurityException("Case snapshot unavailable");
            var result = new ArrayList<Material>();
            for (var ref : refs) {
                boolean admitted = false;
                for (var item : caseInput.path("materials"))
                    if (ref.id().equals(item.path("id").asText()) &&
                            ref.version().equals(item.path("version").asText()) &&
                            ref.sha256().equals(item.path("sha256").asText())) admitted = true;
                if (!admitted) throw new SecurityException("Evidence not in case snapshot");
                var material = materials.material(ref.id())
                        .orElseThrow(() -> new SecurityException("Evidence unavailable"));
                if (!"VERIFIED".equals(material.state()) ||
                        Long.parseLong(ref.version()) != material.version() ||
                        !ref.sha256().equals(material.sha256()) ||
                        !caseInput.path("organization").asText().equals(material.organization()) ||
                        !material.uses().contains("ANALYZE") || !material.expiresAt().isAfter(clock.instant()))
                    throw new SecurityException("Verified evidence use unavailable");
                var actions = materials.materialActions(material.id());
                if (actions.size() != 3 || !"UPLOAD".equals(actions.get(0).action()) ||
                        !"COLLECT_ATTEST".equals(actions.get(1).action()) ||
                        !"VERIFY".equals(actions.get(2).action()) ||
                        !material.collector().equals(actions.get(1).actor()) ||
                        actions.get(1).actor().equals(actions.get(2).actor()))
                    throw new SecurityException("Collection chain unavailable");
                var clue = materials.clue(material.clueId()).orElseThrow();
                var observation = materials.observation(clue.observationId()).orElseThrow();
                var source = materials.source(observation.sourceId()).orElseThrow();
                if (!source.active() || !source.expiresAt().isAfter(clock.instant()) ||
                        !source.uses().contains("ANALYZE") ||
                        !source.organization().equals(material.organization()))
                    throw new SecurityException("Source use unavailable");
                current.requireSource(source.id(), "ANALYZE", source.epoch());
                byte[] bytes = files.read(material.fileKey());
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!ref.sha256().equals(digest)) throw new SecurityException("Original bytes changed");
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException unparseable) {
                    result.add(new Material(ref, null, null, actions.get(1).occurredAt().toString(),
                            actions.get(0).occurredAt().toString(), "UNPARSEABLE_UTF8"));
                    continue;
                }
                String event = text.lines().filter(line -> line.startsWith("Reference: "))
                        .map(line -> line.substring("Reference: ".length()).replaceFirst("\\.$", "").trim())
                        .filter(value -> !value.isBlank()).findFirst().orElse(null);
                result.add(new Material(ref, text, event, actions.get(1).occurredAt().toString(),
                        actions.get(0).occurredAt().toString(), event == null ? "EVENT_REFERENCE_MISSING" : null));
            }
            return new Snapshot(caseRef, version, caseInput.path("caseType").asText(),
                    caseInput.path("organization").asText(),
                    caseInput.path("eventRef").asText(), List.copyOf(result));
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Verified evidence source unavailable", unavailable);
        }
    }
}
