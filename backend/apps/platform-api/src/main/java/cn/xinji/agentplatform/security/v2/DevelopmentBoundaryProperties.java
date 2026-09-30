package cn.xinji.agentplatform.security.v2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("consultation-v2.development")
public class DevelopmentBoundaryProperties {
    private boolean enabled;
    private boolean taskCoreEnabled;
    private boolean textResultEnabled;
    private boolean evidenceReadEnabled;
    private boolean featureConfirmEnabled;
    private boolean comparisonReconfirmEnabled;
    private boolean inputPreviewEnabled;
    private boolean purposeReadEnabled;
    private boolean purposeSelectEnabled;
    private boolean workStateEnabled;
    private boolean humanRequestEnabled;
    private boolean humanAcceptEnabled;
    private boolean humanOpinionEnabled;
    private boolean humanReturnEnabled;
    private boolean humanContextEnabled;
    private boolean correctionEnabled;
    private String manifestPath;
    private String taskInputManifestPath;
    private String comparisonInputManifestPath;
    private String featureCandidateManifestPath;
    private String humanRequestManifestPath;
    private String correctionInputManifestPath;
    private List<Credential> credentials = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isTaskCoreEnabled() { return taskCoreEnabled; }
    public void setTaskCoreEnabled(boolean taskCoreEnabled) { this.taskCoreEnabled = taskCoreEnabled; }
    public boolean isTextResultEnabled() { return textResultEnabled; }
    public void setTextResultEnabled(boolean textResultEnabled) { this.textResultEnabled = textResultEnabled; }
    public boolean isEvidenceReadEnabled() { return evidenceReadEnabled; }
    public void setEvidenceReadEnabled(boolean evidenceReadEnabled) { this.evidenceReadEnabled = evidenceReadEnabled; }
    public boolean isFeatureConfirmEnabled() { return featureConfirmEnabled; }
    public void setFeatureConfirmEnabled(boolean featureConfirmEnabled) { this.featureConfirmEnabled = featureConfirmEnabled; }
    public boolean isComparisonReconfirmEnabled() { return comparisonReconfirmEnabled; }
    public void setComparisonReconfirmEnabled(boolean comparisonReconfirmEnabled) {
        this.comparisonReconfirmEnabled = comparisonReconfirmEnabled;
    }
    public boolean isInputPreviewEnabled() { return inputPreviewEnabled; }
    public void setInputPreviewEnabled(boolean inputPreviewEnabled) { this.inputPreviewEnabled = inputPreviewEnabled; }
    public boolean isPurposeReadEnabled() { return purposeReadEnabled; }
    public void setPurposeReadEnabled(boolean purposeReadEnabled) { this.purposeReadEnabled = purposeReadEnabled; }
    public boolean isPurposeSelectEnabled() { return purposeSelectEnabled; }
    public void setPurposeSelectEnabled(boolean purposeSelectEnabled) { this.purposeSelectEnabled = purposeSelectEnabled; }
    public boolean isWorkStateEnabled() { return workStateEnabled; }
    public void setWorkStateEnabled(boolean workStateEnabled) { this.workStateEnabled = workStateEnabled; }
    public boolean isHumanRequestEnabled() { return humanRequestEnabled; }
    public void setHumanRequestEnabled(boolean humanRequestEnabled) { this.humanRequestEnabled = humanRequestEnabled; }
    public boolean isHumanAcceptEnabled() { return humanAcceptEnabled; }
    public void setHumanAcceptEnabled(boolean humanAcceptEnabled) { this.humanAcceptEnabled = humanAcceptEnabled; }
    public boolean isHumanOpinionEnabled() { return humanOpinionEnabled; }
    public void setHumanOpinionEnabled(boolean humanOpinionEnabled) { this.humanOpinionEnabled = humanOpinionEnabled; }
    public boolean isHumanReturnEnabled() { return humanReturnEnabled; }
    public void setHumanReturnEnabled(boolean humanReturnEnabled) { this.humanReturnEnabled = humanReturnEnabled; }
    public boolean isHumanContextEnabled() { return humanContextEnabled; }
    public void setHumanContextEnabled(boolean humanContextEnabled) { this.humanContextEnabled = humanContextEnabled; }
    public boolean isCorrectionEnabled() { return correctionEnabled; }
    public void setCorrectionEnabled(boolean correctionEnabled) { this.correctionEnabled = correctionEnabled; }
    public String getManifestPath() { return manifestPath; }
    public void setManifestPath(String manifestPath) { this.manifestPath = manifestPath; }
    public String getTaskInputManifestPath() { return taskInputManifestPath; }
    public void setTaskInputManifestPath(String taskInputManifestPath) { this.taskInputManifestPath = taskInputManifestPath; }
    public String getComparisonInputManifestPath() { return comparisonInputManifestPath; }
    public void setComparisonInputManifestPath(String comparisonInputManifestPath) {
        this.comparisonInputManifestPath = comparisonInputManifestPath;
    }
    public String getFeatureCandidateManifestPath() { return featureCandidateManifestPath; }
    public void setFeatureCandidateManifestPath(String featureCandidateManifestPath) {
        this.featureCandidateManifestPath = featureCandidateManifestPath;
    }
    public String getHumanRequestManifestPath() { return humanRequestManifestPath; }
    public void setHumanRequestManifestPath(String humanRequestManifestPath) {
        this.humanRequestManifestPath = humanRequestManifestPath;
    }
    public String getCorrectionInputManifestPath() { return correctionInputManifestPath; }
    public void setCorrectionInputManifestPath(String correctionInputManifestPath) {
        this.correctionInputManifestPath = correctionInputManifestPath;
    }
    public List<Credential> getCredentials() { return credentials; }
    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : credentials;
    }

    public static class Credential {
        private String keyId;
        private String secretSha256;
        private String principalRef;
        private String organizationRef;
        private Kind kind;
        private Status status = Status.ACTIVE;
        private Instant expiresAt;

        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getSecretSha256() { return secretSha256; }
        public void setSecretSha256(String secretSha256) { this.secretSha256 = secretSha256; }
        public String getPrincipalRef() { return principalRef; }
        public void setPrincipalRef(String principalRef) { this.principalRef = principalRef; }
        public String getOrganizationRef() { return organizationRef; }
        public void setOrganizationRef(String organizationRef) { this.organizationRef = organizationRef; }
        public Kind getKind() { return kind; }
        public void setKind(Kind kind) { this.kind = kind; }
        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    }

    public enum Kind { HUMAN, AGENT, JOB }
    public enum Status { ACTIVE, SUSPENDED, REVOKED }
}
