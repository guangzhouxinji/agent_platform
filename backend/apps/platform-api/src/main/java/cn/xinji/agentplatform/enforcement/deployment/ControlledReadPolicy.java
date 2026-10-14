package cn.xinji.agentplatform.enforcement.deployment;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Immutable deployment restriction; never creates domain state or permissions. */
public record ControlledReadPolicy(String digest, String issuer, String organization, String clientId,
        String callbackUri, String documentId, long documentRevision, String jobPackId,
        Instant expiresAt, boolean allowConsent, Set<String> assets) {
    private static final Set<String> KEYS = Set.of("schemaVersion", "approvalRef", "issuer", "organization",
            "clientId", "callbackUri", "documentId", "documentRevision", "jobPackId", "expiresAt",
            "allowConsent", "assets");

    public ControlledReadPolicy {
        assets = Set.copyOf(assets);
        if (digest == null || !digest.matches("[0-9a-f]{64}") || !identifier(organization)
                || !identifier(clientId) || !identifier(documentId) || !identifier(jobPackId)
                || documentRevision < 1 || expiresAt == null || assets.size() > 128)
            throw new IllegalArgumentException("Invalid controlled-read policy");
        URI origin = https(issuer), callback = https(callbackUri);
        if (!origin.getPath().isEmpty() || !origin.getHost().equals(callback.getHost())
                || !"/app/enforcement/document-drafts".equals(callback.getPath()))
            throw new IllegalArgumentException("Invalid controlled-read issuer or callback");
        for (String asset : assets)
            if (!asset.matches("/app/assets/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(js|css|svg|png|woff2)")
                    || asset.contains("..")) throw new IllegalArgumentException("Exact static asset required");
    }

    public static ControlledReadPolicy load(Path path, String expectedSha, ObjectMapper mapper, Clock clock)
            throws Exception {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > 65536)
            throw new IllegalArgumentException("Bounded regular policy file required");
        byte[] bytes = Files.readAllBytes(path);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (expectedSha == null || !sha.equals(expectedSha))
            throw new IllegalArgumentException("Controlled-read policy SHA mismatch");
        var parser = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        JsonNode tree = parser.readTree(bytes);
        if (tree == null) throw new IllegalArgumentException("Empty policy");
        Set<String> names = new HashSet<>(); tree.fieldNames().forEachRemaining(names::add);
        if (!tree.isObject() || !names.equals(KEYS) || !tree.path("schemaVersion").isInt()
                || tree.path("schemaVersion").asInt() != 1 || !tree.path("allowConsent").isBoolean()
                || !tree.path("documentRevision").isIntegralNumber()
                || !tree.path("documentRevision").canConvertToLong() || !tree.path("assets").isArray())
            throw new IllegalArgumentException("Controlled-read policy schema mismatch");
        text(tree, "approvalRef");
        Set<String> assets = new HashSet<>();
        for (JsonNode item : tree.path("assets"))
            if (!item.isTextual() || !assets.add(item.textValue()))
                throw new IllegalArgumentException("Duplicate or invalid asset");
        var policy = new ControlledReadPolicy(sha, text(tree,"issuer"), text(tree,"organization"),
                text(tree,"clientId"), text(tree,"callbackUri"), text(tree,"documentId"),
                tree.path("documentRevision").longValue(), text(tree,"jobPackId"),
                Instant.parse(text(tree,"expiresAt")), tree.path("allowConsent").booleanValue(), assets);
        if (!policy.current(clock)) throw new IllegalArgumentException("Controlled-read policy expired");
        return policy;
    }

    public boolean current(Clock clock) { return clock.instant().isBefore(expiresAt); }
    public String documentPath() { return "/v2/enforcement/document-drafts/" + documentId; }
    public String jobPackPath() { return "/v2/enforcement/job-packs/" + jobPackId; }
    public String callbackPath() { return URI.create(callbackUri).getPath(); }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,159}") && !value.contains("..");
    }
    private static URI https(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || value.contains("%"))
            throw new IllegalArgumentException("Exact HTTPS endpoint required");
        return uri;
    }
    private static String text(JsonNode tree, String field) {
        JsonNode node = tree.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > 1024)
            throw new IllegalArgumentException("Required policy text missing");
        return node.textValue();
    }
}
