package cn.xinji.agentplatform.security.apikey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("api-access")
public class ApiAccessProperties {
    private boolean enabled;
    private List<Credential> credentials = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<Credential> getCredentials() { return credentials; }
    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : credentials;
    }

    public static class Credential {
        private String keyId;
        private String secretSha256;
        private String clientId;
        private String principalRef;
        private String organizationRef;
        private Status status = Status.ACTIVE;
        private Instant expiresAt;
        private List<String> scopes = new ArrayList<>();
        private int requestsPerMinute = 60;
        private int requestsPerDay = 5000;

        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getSecretSha256() { return secretSha256; }
        public void setSecretSha256(String secretSha256) { this.secretSha256 = secretSha256; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getPrincipalRef() { return principalRef; }
        public void setPrincipalRef(String principalRef) { this.principalRef = principalRef; }
        public String getOrganizationRef() { return organizationRef; }
        public void setOrganizationRef(String organizationRef) { this.organizationRef = organizationRef; }
        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes == null ? new ArrayList<>() : scopes; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
        public int getRequestsPerDay() { return requestsPerDay; }
        public void setRequestsPerDay(int requestsPerDay) { this.requestsPerDay = requestsPerDay; }
    }

    public enum Status { ACTIVE, SUSPENDED, REVOKED }
}
