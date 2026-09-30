package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

final class DevComparisonInputPolicy {
    private static final String MANIFEST_SHA = "a9ffd6d954f0e946907dfdb6f9073573b146154dbcbdb94cd2a0e56a0b2f28b2";
    private final ObjectMapper mapper;
    private final Path manifestPath;
    private final DevelopmentSubjectPolicy subjects;

    DevComparisonInputPolicy(ObjectMapper mapper, Path manifestPath, DevelopmentSubjectPolicy subjects) {
        this.mapper = mapper;
        this.manifestPath = manifestPath;
        this.subjects = subjects;
        if (!manifestPath.isAbsolute() || !Files.isRegularFile(manifestPath) || !signed()) {
            throw new IllegalArgumentException("Approved comparison input manifest required");
        }
    }

    boolean allowsDisplay(DevelopmentSubjectPolicy.Subject subject, String kind, String text, Instant now) {
        if (subject == null || !subjects.current(subject, now)
                || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN) return false;
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            if (!MANIFEST_SHA.equals(sha(manifestBytes))) return false;
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (!"isolated-development-only".equals(manifest.path("environment").asText())
                    || !"DEV-CON-COMPARE-INPUT-01".equals(manifest.path("manifestId").asText())
                    || !"SYNTHETIC_DEV_ONLY".equals(manifest.path("status").asText())
                    || manifest.path("withdrawalVersion").asLong(-1) != 0
                    || !"dev-con-isolated".equals(manifest.path("organizationRef").asText())
                    || !subject.organizationRef().equals(manifest.path("organizationRef").asText())
                    || !contains(manifest.path("allowedPrincipalRefs"), subject.stableSubjectId())) return false;
            Instant from = OffsetDateTime.parse(manifest.path("validFrom").asText()).toInstant();
            Instant until = OffsetDateTime.parse(manifest.path("validUntil").asText()).toInstant();
            if (now.isBefore(from) || !now.isBefore(until)) return false;
            String field = switch (kind) {
                case "GOAL" -> "goal";
                case "SUPPLEMENT" -> "supplement";
                default -> null;
            };
            if (field == null || !contains(manifest.path("fields").path("target." + field), "DISPLAY")
                    || !"input.json".equals(manifest.path("payloadFile").asText())) return false;
            byte[] payloadBytes = Files.readAllBytes(manifestPath.getParent().resolve("input.json"));
            if (!manifest.path("payloadSha256").asText().equals(sha(payloadBytes))) return false;
            JsonNode payload = mapper.readTree(payloadBytes);
            return "DEV-CON-COMPARE-INPUT-01".equals(payload.path("datasetId").asText())
                    && text.equals(payload.path("target").path(field).asText(null));
        } catch (Exception failure) {
            return false;
        }
    }

    DevFeatureConfirmationStore.Source permittedFeatures(DevelopmentSubjectPolicy.Subject subject, Actor actor,
                                                           boolean display, Instant now) {
        if (subject == null || actor == null || !subjects.current(subject, now)
                || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN
                || !actor.principal().equals(subject.stableSubjectId())
                || !actor.organization().equals(subject.organizationRef())
                || actor.authorizationVersion() != subject.credentialVersion()) return null;
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            if (!MANIFEST_SHA.equals(sha(manifestBytes))) return null;
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (!"DEV-CON-COMPARE-INPUT-01".equals(manifest.path("manifestId").asText())
                    || !"PROJECT_AUTHORED_SYNTHETIC".equals(manifest.path("origin").asText())
                    || !"isolated-development-only".equals(manifest.path("environment").asText())
                    || !"SYNTHETIC_DEV_ONLY".equals(manifest.path("status").asText())
                    || manifest.path("withdrawalVersion").asLong(-1) != 0
                    || !actor.organization().equals(manifest.path("organizationRef").asText())
                    || !contains(manifest.path("allowedPrincipalRefs"), actor.principal())
                    || !"input.json".equals(manifest.path("payloadFile").asText())) return null;
            Instant from = OffsetDateTime.parse(manifest.path("validFrom").asText()).toInstant();
            Instant until = OffsetDateTime.parse(manifest.path("validUntil").asText()).toInstant();
            if (now.isBefore(from) || !now.isBefore(until)
                    || !contains(manifest.path("fields").path("target.supplement"), "SAVE")
                    || !contains(manifest.path("fields").path("featureCandidates"), "SAVE")
                    || (display && !contains(manifest.path("fields").path("featureCandidates"), "DISPLAY"))) {
                return null;
            }
            byte[] payloadBytes = Files.readAllBytes(manifestPath.resolveSibling("input.json"));
            if (!sha(payloadBytes).equals(manifest.path("payloadSha256").asText())) return null;
            JsonNode payload = mapper.readTree(payloadBytes);
            if (!"DEV-CON-COMPARE-INPUT-01".equals(payload.path("datasetId").asText())) return null;
            String supplement = payload.path("target").path("supplement").asText(null);
            JsonNode features = payload.path("featureCandidates");
            if (supplement == null || !features.isArray() || features.size() != 3) return null;
            byte[] source = supplement.getBytes(StandardCharsets.UTF_8);
            List<DevFeatureConfirmationStore.Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                JsonNode feature = features.get(i);
                int start = feature.path("byteStart").asInt(-1), end = feature.path("byteEnd").asInt(-1);
                String id = "F" + (i + 1), value = feature.path("exactText").asText(null);
                if (value == null || !id.equals(feature.path("featureId").asText())
                        || !"UNCONFIRMED".equals(feature.path("status").asText())
                        || start < 0 || end <= start || end > source.length
                        || !value.equals(new String(source, start, end - start, StandardCharsets.UTF_8))) return null;
                candidates.add(new DevFeatureConfirmationStore.Candidate(id, value,
                        sha(mapper.writeValueAsBytes(feature)), start, end));
            }
            byte[] length = {(byte)(source.length >>> 24), (byte)(source.length >>> 16),
                    (byte)(source.length >>> 8), (byte)source.length};
            MessageDigest fingerprint = MessageDigest.getInstance("SHA-256");
            fingerprint.update(length);
            return new DevFeatureConfirmationStore.Source("DEV-CON-COMPARE-INPUT-01", MANIFEST_SHA,
                    sha(source), HexFormat.of().formatHex(fingerprint.digest(source)), 0, List.copyOf(candidates));
        } catch (Exception failure) {
            return null;
        }
    }

    DevFeatureConfirmationStore.Source permittedComparisonFeatures(DevelopmentSubjectPolicy.Subject subject,
                                                                     Actor actor, Instant now) {
        var source = permittedFeatures(subject, actor, true, now);
        if (source == null) return null;
        try {
            byte[] raw = Files.readAllBytes(manifestPath);
            if (!MANIFEST_SHA.equals(sha(raw))) return null;
            JsonNode fields = mapper.readTree(raw).path("fields");
            return contains(fields.path("featureCandidates"), "COMPARE_INPUT")
                    && contains(fields.path("target.supplement"), "DISPLAY")
                    && contains(fields.path("target.supplement"), "COMPARE_INPUT") ? source : null;
        } catch (Exception failure) {
            return null;
        }
    }

    DevTextResultStore.Source permittedComparisonClaim(DevelopmentSubjectPolicy.Subject subject, Actor actor,
            String recordId, String version, String digest, Instant now) {
        if (permittedComparisonFeatures(subject, actor, now) == null) return null;
        try {
            byte[] raw = Files.readAllBytes(manifestPath);
            if (!MANIFEST_SHA.equals(sha(raw))) return null;
            JsonNode manifest = mapper.readTree(raw);
            if (!contains(manifest.path("fields").path("claimCandidates"), "DISPLAY")
                    || !contains(manifest.path("fields").path("claimCandidates"), "COMPARE_INPUT")) return null;
            byte[] bytes = Files.readAllBytes(manifestPath.resolveSibling("input.json"));
            if (!manifest.path("payloadSha256").asText().equals(sha(bytes))) return null;
            JsonNode payload = mapper.readTree(bytes);
            for (JsonNode claim : payload.path("claimCandidates")) {
                String text = claim.path("claimText").asText(null);
                if (text != null && recordId.equals(claim.path("recordId").asText())
                        && version.equals(claim.path("sourceVersion").asText())
                        && "claim:1".equals(claim.path("locator").asText())
                        && digest.equals(sha(text.getBytes(StandardCharsets.UTF_8)))) {
                    return new DevTextResultStore.Source(recordId, version, digest, "claim:1", text);
                }
            }
        } catch (Exception failure) {
            return null;
        }
        return null;
    }

    private boolean signed() {
        try {
            byte[] raw = Files.readAllBytes(manifestPath);
            JsonNode manifest = mapper.readTree(raw);
            byte[] payload = Files.readAllBytes(manifestPath.getParent().resolve("input.json"));
            return MANIFEST_SHA.equals(sha(raw))
                    && "DEV-CON-COMPARE-INPUT-01".equals(manifest.path("manifestId").asText())
                    && manifest.path("payloadSha256").asText().equals(sha(payload));
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean contains(JsonNode values, String value) {
        if (!values.isArray()) return false;
        for (JsonNode item : values) if (value.equals(item.asText())) return true;
        return false;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
