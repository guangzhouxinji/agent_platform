package cn.xinji.agentplatform.security.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;

final class DevCorrectionInputPolicy {
    private static final String SIGNED_SHA = "7380e15b30b98feec234fd1195bac64446d5420902333cd694de13b3ba0cd533";
    private final ObjectMapper mapper;
    private final Path manifestPath;
    private final Path comparisonManifestPath;
    private final DevelopmentSubjectPolicy subjects;

    record Source(String manifestId, String manifestSha, String oldSha, String newSha,
                  String utteranceSha, String oldText, String newText, String utterance) { }

    DevCorrectionInputPolicy(ObjectMapper mapper, Path manifestPath, Path comparisonManifestPath,
                             DevelopmentSubjectPolicy subjects) {
        this.mapper = mapper;
        this.manifestPath = manifestPath;
        this.comparisonManifestPath = comparisonManifestPath;
        this.subjects = subjects;
        if (!manifestPath.isAbsolute() || !comparisonManifestPath.isAbsolute()
                || !Files.isRegularFile(manifestPath) || !Files.isRegularFile(comparisonManifestPath) || !signed()) {
            throw new IllegalArgumentException("Approved correction input manifest required");
        }
    }

    Source permitted(DevelopmentSubjectPolicy.Subject subject, Instant now) {
        if (subject == null || !subjects.current(subject, now)
                || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN) return null;
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            if (!SIGNED_SHA.equals(sha(manifestBytes))) return null;
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (!"DEV-CON-CORRECTION-INPUT-01".equals(manifest.path("manifestId").asText())
                    || !"SYNTHETIC_DEV_ONLY".equals(manifest.path("status").asText())
                    || manifest.path("withdrawalVersion").asLong(-1) != 0
                    || !subject.organizationRef().equals(manifest.path("organizationRef").asText())
                    || !contains(manifest.path("allowedPrincipalRefs"), subject.stableSubjectId())
                    || now.isBefore(OffsetDateTime.parse(manifest.path("validFrom").asText()).toInstant())
                    || !now.isBefore(OffsetDateTime.parse(manifest.path("validUntil").asText()).toInstant())) {
                return null;
            }
            byte[] raw = Files.readAllBytes(manifestPath.resolveSibling("input.json"));
            if (!manifest.path("payloadSha256").asText().equals(sha(raw))) return null;
            byte[] parentRaw = Files.readAllBytes(comparisonManifestPath);
            if (!manifest.path("sourceManifest").path("sha256").asText().equals(sha(parentRaw))) return null;
            JsonNode parent = mapper.readTree(parentRaw);
            if (!"DEV-CON-COMPARE-INPUT-01".equals(parent.path("manifestId").asText())
                    || !parent.path("manifestId").asText().equals(manifest.path("sourceManifest").path("manifestId").asText())
                    || !"SYNTHETIC_DEV_ONLY".equals(parent.path("status").asText())
                    || !subject.organizationRef().equals(parent.path("organizationRef").asText())
                    || !contains(parent.path("allowedPrincipalRefs"), subject.stableSubjectId())
                    || parent.path("withdrawalVersion").asLong(-1) != 0
                    || now.isBefore(OffsetDateTime.parse(parent.path("validFrom").asText()).toInstant())
                    || !now.isBefore(OffsetDateTime.parse(parent.path("validUntil").asText()).toInstant())) return null;
            byte[] parentInput = Files.readAllBytes(comparisonManifestPath.resolveSibling("input.json"));
            if (!parent.path("payloadSha256").asText().equals(sha(parentInput))) return null;
            JsonNode data = mapper.readTree(raw).path("correction");
            String oldText = data.path("oldText").asText(null);
            String newText = data.path("newText").asText(null);
            String utterance = data.path("userMessage").asText(null);
            if (oldText == null || newText == null || utterance == null
                    || !oldText.equals(mapper.readTree(parentInput).path("target").path("supplement").asText())
                    || !"阀门".equals(data.path("oldFragment").asText())
                    || !"电磁阀".equals(data.path("newFragment").asText())
                    || !newText.equals(oldText.replace("阀门", "电磁阀"))
                    || !"纠正：补充里的阀门应为电磁阀，其他不变。".equals(utterance)) return null;
            JsonNode fields = manifest.path("fields");
            if (!contains(fields.path("correction.oldText"), "CORRECTION_INPUT")
                    || !contains(fields.path("correction.newText"), "SAVE")
                    || !contains(fields.path("correction.newText"), "DISPLAY")
                    || !contains(fields.path("correction.newText"), "CORRECTION_INPUT")
                    || !contains(fields.path("correction.userMessage"), "SAVE")
                    || !contains(fields.path("correction.userMessage"), "DISPLAY")
                    || !contains(fields.path("correction.userMessage"), "CORRECTION_INPUT")) return null;
            return new Source("DEV-CON-CORRECTION-INPUT-01", SIGNED_SHA, sha(oldText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    sha(newText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    sha(utterance.getBytes(java.nio.charset.StandardCharsets.UTF_8)), oldText, newText, utterance);
        } catch (Exception failure) {
            return null;
        }
    }

    boolean allows(DevelopmentSubjectPolicy.Subject subject, String field, String text, Instant now) {
        Source source = permitted(subject, now);
        return source != null && switch (field) {
            case "NEW_TEXT" -> source.newText().equals(text);
            case "UTTERANCE" -> source.utterance().equals(text);
            case "MANIFEST_SHA" -> source.manifestSha().equals(text);
            case "OLD_SHA" -> source.oldSha().equals(text);
            default -> false;
        };
    }

    private boolean signed() {
        try { return SIGNED_SHA.equals(sha(Files.readAllBytes(manifestPath))); }
        catch (Exception failure) { return false; }
    }

    private static boolean contains(JsonNode values, String value) {
        if (!values.isArray()) return false;
        for (JsonNode item : values) if (value.equals(item.asText())) return true;
        return false;
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
