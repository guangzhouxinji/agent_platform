package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;

final class DevHumanGapPolicy {
    private static final String MANIFEST_SHA = "6931666dd71b0fe08623017210fe81d39a586e406b82b59e592aa20e882f0713";
    private static final String PAYLOAD_SHA = "57f30e61e524d7f3a2f2b2fcf87f0be3c77b0c470247655bd333aa1394cb47e6";
    private static final String TEXT = "请人工核对当前分析目的与证据缺口。";
    private static final String OPINION = "当前分析目的尚未确认，逐项专业对应方法尚无获准依据；本次不形成技术对应结论。";
    private final ObjectMapper mapper;
    private final Path manifestPath;
    private final DevelopmentSubjectPolicy subjects;

    DevHumanGapPolicy(ObjectMapper mapper, Path manifestPath, DevelopmentSubjectPolicy subjects) {
        this.mapper = mapper;
        this.manifestPath = manifestPath;
        this.subjects = subjects;
        if (!manifestPath.isAbsolute() || !Files.isRegularFile(manifestPath) || !signed()) {
            throw new IllegalArgumentException("Signed development human input required");
        }
    }

    DevHumanRequestStore.Source permitted(DevelopmentSubjectPolicy.Subject subject, Actor actor,
                                           boolean save, boolean display, Instant now) {
        return permittedField(subject, actor, save, display, now, false);
    }

    DevHumanRequestStore.Source opinionPermitted(DevelopmentSubjectPolicy.Subject subject, Actor actor,
                                                  boolean save, boolean display, Instant now) {
        return permittedField(subject, actor, save, display, now, true);
    }

    private DevHumanRequestStore.Source permittedField(DevelopmentSubjectPolicy.Subject subject, Actor actor,
                                                        boolean save, boolean display, Instant now, boolean opinion) {
        if (subject == null || actor == null || !subjects.current(subject, now)
                || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN
                || !actor.principal().equals(subject.stableSubjectId())
                || !actor.organization().equals(subject.organizationRef())
                || actor.authorizationVersion() != subject.credentialVersion()) return null;
        try {
            byte[] raw = Files.readAllBytes(manifestPath);
            if (!MANIFEST_SHA.equals(sha(raw))) return null;
            JsonNode policy = mapper.readTree(raw);
            if (!"DEV-CON-HUMAN-GAP-01".equals(policy.path("manifestId").asText())
                    || !"PROJECT_AUTHORED_SYNTHETIC".equals(policy.path("origin").asText())
                    || !"isolated-development-only".equals(policy.path("environment").asText())
                    || !"SYNTHETIC_DEV_ONLY".equals(policy.path("status").asText())
                    || policy.path("withdrawalVersion").asLong(-1) != 0
                    || !actor.organization().equals(policy.path("organizationRef").asText())
                    || !contains(policy.path("allowedPrincipalRefs"), actor.principal())
                    || !"input.json".equals(policy.path("payloadFile").asText())
                    || !PAYLOAD_SHA.equals(policy.path("payloadSha256").asText())) return null;
            Instant from = OffsetDateTime.parse(policy.path("validFrom").asText()).toInstant();
            Instant until = OffsetDateTime.parse(policy.path("validUntil").asText()).toInstant();
            JsonNode uses = policy.path("fields").path(opinion ? "opinion.text" : "request.text");
            if (now.isBefore(from) || !now.isBefore(until)
                    || (save && !contains(uses, "SAVE")) || (display && !contains(uses, "DISPLAY"))) return null;
            byte[] payloadBytes = Files.readAllBytes(manifestPath.resolveSibling("input.json"));
            if (!PAYLOAD_SHA.equals(sha(payloadBytes))) return null;
            JsonNode payload = mapper.readTree(payloadBytes);
            JsonNode request = payload.path("entries").path(opinion ? 1 : 0);
            String expected = opinion ? OPINION : TEXT;
            if (!"DEV-CON-HUMAN-GAP-01".equals(payload.path("datasetId").asText())
                    || !(opinion ? "OPINION_GAP" : "REQUEST_GAP").equals(request.path("id").asText())
                    || !(opinion ? "INTERNAL_TEST_OPINION" : "REQUEST").equals(request.path("kind").asText())
                    || !expected.equals(request.path("text").asText())) return null;
            return new DevHumanRequestStore.Source("DEV-CON-HUMAN-GAP-01", MANIFEST_SHA,
                    sha(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, expected);
        } catch (Exception failure) { return null; }
    }

    private boolean signed() {
        try { return MANIFEST_SHA.equals(sha(Files.readAllBytes(manifestPath)))
                && PAYLOAD_SHA.equals(sha(Files.readAllBytes(manifestPath.resolveSibling("input.json")))); }
        catch (Exception failure) { return false; }
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
