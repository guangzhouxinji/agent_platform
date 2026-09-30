package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class DevFeatureCandidatePolicy {
    private static final String ID = "DEV-CON-FEATURE-CANDIDATES-01";
    private static final List<String> IDS = List.of("F1", "F2", "F3");
    private static final List<String> TEXTS = List.of("温度传感器", "自动控制器", "阀门");
    private static final String INPUT_SHA = "f715fa600f5ba46d4e7b15dfc42d29d58d55366271d0196444ec98fb59def7af";
    private static final Set<String> FIELDS = Set.of("featureId", "exactText", "byteStart", "byteEnd", "status");
    private final ObjectMapper mapper;
    private final Path manifestPath;
    private final Path inputPath;
    private final DevelopmentSubjectPolicy subjects;
    private final String pinnedManifestDigest;
    private final String pinnedInputDigest;

    DevFeatureCandidatePolicy(ObjectMapper mapper, Path manifestPath, Path inputPath,
                              DevelopmentSubjectPolicy subjects) {
        this.mapper = mapper;
        this.manifestPath = manifestPath;
        this.inputPath = inputPath;
        this.subjects = subjects;
        try {
            pinnedManifestDigest = sha(Files.readAllBytes(manifestPath));
            pinnedInputDigest = sha(Files.readAllBytes(inputPath));
            if (load(null, Instant.EPOCH, false) == null) {
                throw new IllegalArgumentException("Invalid feature candidate manifest");
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot load signed feature candidates", failure);
        }
    }

    DevFeatureConfirmationStore.Source permitted(DevelopmentSubjectPolicy.Subject subject, Actor actor,
                                                  boolean display, Instant now) {
        if (subject == null || actor == null || !subjects.current(subject, now)
                || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN
                || !actor.principal().equals(subject.stableSubjectId())
                || !actor.organization().equals(subject.organizationRef())
                || actor.authorizationVersion() != subject.credentialVersion()) return null;
        try {
            return load(actor, now, display);
        } catch (Exception failure) {
            return null;
        }
    }

    private DevFeatureConfirmationStore.Source load(Actor actor, Instant now, boolean display) throws Exception {
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        byte[] inputBytes = Files.readAllBytes(inputPath);
        if (!pinnedManifestDigest.equals(sha(manifestBytes)) || !pinnedInputDigest.equals(sha(inputBytes))) return null;
        JsonNode manifest = mapper.readTree(manifestBytes), input = mapper.readTree(inputBytes);
        if (manifest.path("schemaVersion").asInt() != 1 || !ID.equals(manifest.path("manifestId").asText())
                || !"PROJECT_AUTHORED_SYNTHETIC".equals(manifest.path("origin").asText())
                || !"isolated-development-only".equals(manifest.path("environment").asText())
                || !"SYNTHETIC_DEV_ONLY".equals(manifest.path("status").asText())
                || manifest.path("withdrawalVersion").asLong(-1) != 0
                || !"docs/08-总开发实现计划.md#DATA-CON-FEATURE-INPUT-01"
                        .equals(manifest.path("approvalRef").asText())
                || !"DEV-CON-INPUT-01".equals(input.path("manifestId").asText())
                || input.path("manifestVersion").asInt() != 1
                || !"SYNTHETIC_DEV_ONLY".equals(input.path("status").asText())
                || !input.path("withdrawalRef").isNull()
                || !manifest.path("organizationRef").asText().equals(input.path("organizationRef").asText())
                || !"candidates.json".equals(manifest.path("payloadFile").asText())) return null;
        Instant from = OffsetDateTime.parse(manifest.path("validFrom").asText()).toInstant();
        Instant until = OffsetDateTime.parse(manifest.path("validUntil").asText()).toInstant();
        Instant inputFrom = OffsetDateTime.parse(input.path("validFrom").asText()).toInstant();
        Instant inputUntil = OffsetDateTime.parse(input.path("validUntil").asText()).toInstant();
        if (!from.isBefore(until) || !inputFrom.isBefore(inputUntil)
                || (actor != null && (now.isBefore(from) || !now.isBefore(until)
                || now.isBefore(inputFrom) || !now.isBefore(inputUntil)
                || !actor.organization().equals(manifest.path("organizationRef").asText())
                || !contains(manifest.path("allowedPrincipalRefs"), actor.principal())
                || !contains(input.path("allowedPrincipalRefs"), actor.principal())))) return null;
        JsonNode uses = manifest.path("fields");
        if (!uses.isObject() || uses.size() != FIELDS.size()
                || !FIELDS.stream().allMatch(field -> contains(uses.path(field), "SAVE")
                        && (!display || contains(uses.path(field), "DISPLAY")))) return null;
        JsonNode entries = input.path("entries");
        if (!entries.isArray() || entries.size() != 2) return null;
        JsonNode supplement = entries.get(1);
        String text = supplement.path("text").asText();
        String inputSha = sha(text.getBytes(StandardCharsets.UTF_8));
        if (!"SUPPLEMENT".equals(supplement.path("kind").asText())
                || !INPUT_SHA.equals(inputSha)
                || !inputSha.equals(supplement.path("sha256").asText())
                || supplement.path("uses").size() != 1
                || !"SAVE".equals(supplement.path("uses").get(0).asText())) return null;
        byte[] payloadBytes = Files.readAllBytes(manifestPath.resolveSibling("candidates.json"));
        JsonNode payload = mapper.readTree(payloadBytes);
        if (!sha(payloadBytes).equals(manifest.path("payloadSha256").asText())
                || !ID.equals(payload.path("datasetId").asText())
                || !"DEV-CON-INPUT-01".equals(payload.path("source").path("inputManifestId").asText())
                || !"SUPPLEMENT".equals(payload.path("source").path("kind").asText())
                || !inputSha.equals(payload.path("source").path("inputSha256").asText())) return null;
        JsonNode candidates = payload.path("candidates"), digests = manifest.path("candidateDigests");
        if (!candidates.isArray() || candidates.size() != 3 || !digests.isArray() || digests.size() != 3) return null;
        List<DevFeatureConfirmationStore.Candidate> result = new ArrayList<>();
        StringBuilder batch = new StringBuilder();
        byte[] source = text.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < 3; index++) {
            JsonNode candidate = candidates.get(index), digest = digests.get(index);
            String id = IDS.get(index);
            String value = candidate.path("exactText").asText();
            int start = candidate.path("byteStart").asInt(-1), end = candidate.path("byteEnd").asInt(-1);
            String candidateSha = sha(mapper.writeValueAsBytes(candidate));
            if (!candidate.isObject() || candidate.size() != FIELDS.size()
                    || !FIELDS.stream().allMatch(candidate::has)
                    || !id.equals(candidate.path("featureId").asText())
                    || !TEXTS.get(index).equals(value)
                    || !"UNCONFIRMED".equals(candidate.path("status").asText())
                    || !id.equals(digest.path("featureId").asText())
                    || !candidateSha.equals(digest.path("sha256").asText())
                    || start < 0 || end <= start || end > source.length
                    || !value.equals(new String(source, start, end - start, StandardCharsets.UTF_8))) return null;
            batch.append(id).append('\t').append(candidateSha).append('\n');
            result.add(new DevFeatureConfirmationStore.Candidate(id, value, candidateSha, start, end));
        }
        if (!sha(batch.toString().getBytes(StandardCharsets.UTF_8)).equals(manifest.path("batchSha256").asText())) {
            return null;
        }
        return new DevFeatureConfirmationStore.Source(ID, pinnedManifestDigest, inputSha,
                fingerprint(text), 0, List.copyOf(result));
    }

    private static boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fingerprint(String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(new byte[] {(byte)(bytes.length >>> 24), (byte)(bytes.length >>> 16),
                (byte)(bytes.length >>> 8), (byte)bytes.length});
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
