package cn.xinji.agentplatform.enforcement.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.StreamSupport;

/** A configured, read-only source adapter; accepts only its recorded bytes and trial use. */
public final class FileEvidenceTrialSource implements EvidenceTrialSource {
    private final Path root;
    private final ObjectMapper json;

    public FileEvidenceTrialSource(Path root, ObjectMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
    }

    @Override public Snapshot read(String caseRef, String version, List<Ref> refs) {
        try {
            JsonNode manifest = json.readTree(Files.readAllBytes(root.resolve("manifest.json")));
            JsonNode declared = manifest.path("caseInput");
            if (!caseRef.equals(declared.path("caseRef").asText()) ||
                    !version.equals(declared.path("caseVersion").asText()) ||
                    declared.path("caseType").asText().isBlank() ||
                    declared.path("organization").asText().isBlank() ||
                    declared.path("eventRef").asText().isBlank() ||
                    manifest.path("rightsBasis").asText().isBlank() ||
                    !StreamSupport.stream(manifest.path("allowedUses").spliterator(), false)
                            .anyMatch(use -> "ANALYZE".equals(use.asText())) ||
                    manifest.path("withdrawn").asBoolean(false) ||
                    (manifest.hasNonNull("expiresAt") &&
                            !java.time.Instant.parse(manifest.path("expiresAt").asText())
                                    .isAfter(java.time.Instant.now())))
                throw new SecurityException("Trial source unavailable");
            var results = new ArrayList<Material>();
            for (var ref : refs) {
                JsonNode item = null;
                for (JsonNode entry : manifest.path("materials"))
                    if (ref.id().equals(entry.path("ref").asText())) item = entry;
                if (item == null || !ref.version().equals(item.path("version").asText()) ||
                        !ref.sha256().equals(item.path("sha256").asText()) ||
                        !"INPUT_ONLY_NOT_VERIFIED".equals(item.path("status").asText()))
                    throw new SecurityException("Trial material unavailable");
                Path file = root.resolve(item.path("path").asText()).normalize();
                if (!file.startsWith(root)) throw new SecurityException("Invalid material path");
                byte[] bytes = Files.readAllBytes(file);
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (bytes.length != item.path("byteLength").asInt(-1) || !actual.equals(ref.sha256()))
                    throw new SecurityException("Trial material digest changed");
                String body = new String(bytes, StandardCharsets.UTF_8);
                String event = body.lines().filter(line -> line.startsWith("Reference: "))
                        .map(line -> line.substring("Reference: ".length()).replaceFirst("\\.$", ""))
                        .findFirst().orElseThrow(() -> new SecurityException("Material event missing"));
                results.add(new Material(ref, body, event));
            }
            return new Snapshot(caseRef, version, declared.path("caseType").asText(),
                    declared.path("organization").asText(),
                    declared.path("eventRef").asText(), List.copyOf(results));
        } catch (SecurityException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Trial source unavailable", unavailable);
        }
    }
}
