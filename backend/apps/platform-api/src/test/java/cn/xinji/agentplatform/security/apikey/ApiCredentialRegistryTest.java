package cn.xinji.agentplatform.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApiCredentialRegistryTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test void authenticatesActiveCredentialFromDigest() {
        var registry = registry(ApiAccessProperties.Status.ACTIVE, Instant.parse("2099-01-01T00:00:00Z"));
        var authenticated = registry.authenticate("Bearer xj_partner-key." + SECRET, Instant.parse("2026-09-18T00:00:00Z"));

        assertThat(authenticated.credential().clientId()).isEqualTo("partner-client");
        assertThat(authenticated.credential().principalRef()).isEqualTo("v0-authorized-user");
        assertThat(authenticated.credential().organizationRef()).isEqualTo("org-v0");
        assertThat(authenticated.allows("consultation:submit")).isTrue();
        assertThat(authenticated.allows("source:read")).isFalse();
    }

    @Test void rejectsMalformedUnknownAndWrongSecrets() {
        var registry = registry(ApiAccessProperties.Status.ACTIVE, null);

        assertFailure(registry, null, "API_KEY_REQUIRED");
        assertFailure(registry, "Bearer malformed", "INVALID_API_KEY");
        assertFailure(registry, "Bearer xj_unknown." + SECRET, "INVALID_API_KEY");
        assertFailure(registry, "Bearer xj_partner-key.wrong-secret", "INVALID_API_KEY");
    }

    @Test void rejectsSuspendedRevokedAndExpiredCredentials() {
        assertFailure(registry(ApiAccessProperties.Status.SUSPENDED, null), token(), "API_KEY_INACTIVE");
        assertFailure(registry(ApiAccessProperties.Status.REVOKED, null), token(), "API_KEY_INACTIVE");
        assertFailure(registry(ApiAccessProperties.Status.ACTIVE, Instant.parse("2026-09-18T00:00:00Z")),
                token(), "API_KEY_EXPIRED");
    }

    @Test void refusesInvalidOrDuplicateStartupConfiguration() {
        ApiAccessProperties empty = new ApiAccessProperties();
        assertThatThrownBy(() -> new ApiCredentialRegistry(empty)).hasMessageContaining("at least one credential");

        ApiAccessProperties duplicate = new ApiAccessProperties();
        duplicate.setCredentials(List.of(credential(ApiAccessProperties.Status.ACTIVE, null),
                credential(ApiAccessProperties.Status.ACTIVE, null)));
        assertThatThrownBy(() -> new ApiCredentialRegistry(duplicate)).hasMessageContaining("Duplicate");
    }

    private static ApiCredentialRegistry registry(ApiAccessProperties.Status status, Instant expiry) {
        ApiAccessProperties properties = new ApiAccessProperties();
        properties.setCredentials(List.of(credential(status, expiry)));
        return new ApiCredentialRegistry(properties);
    }

    private static ApiAccessProperties.Credential credential(ApiAccessProperties.Status status, Instant expiry) {
        ApiAccessProperties.Credential credential = new ApiAccessProperties.Credential();
        credential.setKeyId("partner-key");
        credential.setSecretSha256(ApiCredentialRegistry.hashSecret(SECRET));
        credential.setClientId("partner-client");
        credential.setPrincipalRef("v0-authorized-user");
        credential.setOrganizationRef("org-v0");
        credential.setStatus(status);
        credential.setExpiresAt(expiry);
        credential.setScopes(List.of("consultation:submit"));
        credential.setRequestsPerMinute(10);
        credential.setRequestsPerDay(100);
        return credential;
    }

    private static void assertFailure(ApiCredentialRegistry registry, String header, String code) {
        assertThatThrownBy(() -> registry.authenticate(header, Instant.parse("2026-09-18T00:00:00Z")))
                .isInstanceOf(ApiAccessFailure.class)
                .hasMessage(code);
    }

    private static String token() { return "Bearer xj_partner-key." + SECRET; }
}
