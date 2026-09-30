package cn.xinji.agentplatform.security.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DevTaskInputPolicy {
    private final DevelopmentSubjectPolicy subjects;
    private final String organization;
    private final Set<String> principals;
    private final Instant from;
    private final Instant until;
    private final Map<String, String> entries;

    public DevTaskInputPolicy(ObjectMapper mapper, Path file, DevelopmentSubjectPolicy subjects) {
        this.subjects = subjects;
        try {
            JsonNode root = mapper.readTree(file.toFile());
            if (!"DEV-CON-INPUT-01".equals(required(root, "manifestId"))
                    || root.path("manifestVersion").asInt() != 1
                    || !"SYNTHETIC_DEV_ONLY".equals(required(root, "status"))
                    || !root.has("withdrawalRef") || !root.path("withdrawalRef").isNull()) {
                throw new IllegalArgumentException("Invalid task input manifest");
            }
            required(root, "approvalRef");
            organization = required(root, "organizationRef");
            from = OffsetDateTime.parse(required(root, "validFrom")).toInstant();
            until = OffsetDateTime.parse(required(root, "validUntil")).toInstant();
            if (!from.isBefore(until)) throw new IllegalArgumentException("Invalid input interval");
            JsonNode people = root.path("allowedPrincipalRefs");
            if (!people.isArray()) throw new IllegalArgumentException("Missing input principals");
            Set<String> allowed = new HashSet<>();
            people.forEach(item -> allowed.add(item.asText()));
            if (allowed.size() < 2 || allowed.contains("")) throw new IllegalArgumentException("Invalid input principals");
            principals = Set.copyOf(allowed);
            JsonNode values = root.path("entries");
            if (!values.isArray() || values.size() != 2) throw new IllegalArgumentException("Invalid input entries");
            Map<String, String> parsed = new HashMap<>();
            for (JsonNode item : values) {
                String kind = required(item, "kind");
                String content = required(item, "text");
                String hash = required(item, "sha256");
                if (!Set.of("GOAL", "SUPPLEMENT").contains(kind)
                        || !sha256(content).equals(hash)
                        || item.path("uses").size() != 1
                        || !"SAVE".equals(item.path("uses").get(0).asText())
                        || parsed.putIfAbsent(kind, content) != null) {
                    throw new IllegalArgumentException("Invalid task input digest or use");
                }
            }
            if (parsed.size() != 2) throw new IllegalArgumentException("Missing task input kind");
            entries = Map.copyOf(parsed);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot load approved synthetic task input", failure);
        }
    }

    public boolean allows(DevelopmentSubjectPolicy.Subject subject, String kind, String content, Instant now) {
        return subject != null && subjects.current(subject, now)
                && subject.kind() == DevelopmentBoundaryProperties.Kind.HUMAN
                && organization.equals(subject.organizationRef())
                && principals.contains(subject.stableSubjectId())
                && !now.isBefore(from) && now.isBefore(until)
                && entries.getOrDefault(kind, "").equals(content);
    }

    public Instant validUntil() { return until; }

    private static String required(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing input field");
        return value.asText();
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
