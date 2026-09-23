package cn.xinji.agentplatform.security.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ApiCredentialRegistry {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private final Map<String, Credential> credentials;

    ApiCredentialRegistry(ApiAccessProperties properties) {
        if (properties.getCredentials().isEmpty()) {
            throw new IllegalArgumentException("api-access requires at least one credential when enabled");
        }
        Map<String, Credential> configured = new LinkedHashMap<>();
        for (ApiAccessProperties.Credential item : properties.getCredentials()) {
            Credential credential = validate(item);
            if (configured.putIfAbsent(credential.keyId(), credential) != null) {
                throw new IllegalArgumentException("Duplicate api-access key-id: " + credential.keyId());
            }
        }
        credentials = Map.copyOf(configured);
    }

    AuthenticatedClient authenticate(String authorization, Instant now) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiAccessFailure("API_KEY_REQUIRED", 401);
        }
        String token = authorization.substring(7).trim();
        int separator = token.indexOf('.');
        if (!token.startsWith("xj_") || separator < 4 || separator == token.length() - 1) {
            throw new ApiAccessFailure("INVALID_API_KEY", 401);
        }
        String keyId = token.substring(3, separator);
        String secret = token.substring(separator + 1);
        if (secret.length() < 32) throw new ApiAccessFailure("INVALID_API_KEY", 401);
        Credential credential = credentials.get(keyId);
        if (credential == null || !matches(secret, credential.secretHash())) {
            throw new ApiAccessFailure("INVALID_API_KEY", 401);
        }
        if (credential.status() != ApiAccessProperties.Status.ACTIVE) {
            throw new ApiAccessFailure("API_KEY_INACTIVE", 401, credential.keyId(), credential.clientId());
        }
        if (credential.expiresAt() != null && !now.isBefore(credential.expiresAt())) {
            throw new ApiAccessFailure("API_KEY_EXPIRED", 401, credential.keyId(), credential.clientId());
        }
        return new AuthenticatedClient(credential);
    }

    private static Credential validate(ApiAccessProperties.Credential item) {
        String keyId = required(item.getKeyId(), "key-id");
        if (!KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("Invalid api-access key-id: " + keyId);
        String hash = required(item.getSecretSha256(), "secret-sha256").toLowerCase();
        if (!SHA_256.matcher(hash).matches()) throw new IllegalArgumentException("Invalid secret-sha256 for key-id: " + keyId);
        String clientId = required(item.getClientId(), "client-id");
        String principal = required(item.getPrincipalRef(), "principal-ref");
        String organization = optional(item.getOrganizationRef());
        if (item.getStatus() == null) throw new IllegalArgumentException("Missing status for key-id: " + keyId);
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : item.getScopes()) if (scope != null && !scope.isBlank()) scopes.add(scope.trim());
        if (scopes.isEmpty()) throw new IllegalArgumentException("Missing scopes for key-id: " + keyId);
        if (item.getRequestsPerMinute() < 1 || item.getRequestsPerDay() < 1) {
            throw new IllegalArgumentException("Request limits must be positive for key-id: " + keyId);
        }
        return new Credential(keyId, hash, clientId, principal, organization, item.getStatus(), item.getExpiresAt(),
                Set.copyOf(scopes), item.getRequestsPerMinute(), item.getRequestsPerDay());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing api-access " + field);
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean matches(String secret, String expectedHash) {
        byte[] actual = sha256(secret);
        byte[] expected;
        try { expected = HexFormat.of().parseHex(expectedHash); }
        catch (IllegalArgumentException invalid) { return false; }
        return MessageDigest.isEqual(actual, expected);
    }

    static String hashSecret(String secret) { return HexFormat.of().formatHex(sha256(secret)); }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Credential(String keyId, String secretHash, String clientId, String principalRef, String organizationRef,
                      ApiAccessProperties.Status status, Instant expiresAt, Set<String> scopes,
                      int requestsPerMinute, int requestsPerDay) { }

    record AuthenticatedClient(Credential credential) {
        boolean allows(String scope) { return credential.scopes().contains("*") || credential.scopes().contains(scope); }
    }

}
