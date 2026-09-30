package cn.xinji.agentplatform.security.product;

import java.time.Duration;

/** Required deployment decision for task metadata, never a code default. */
public record ProductRetentionPolicy(String reference, long version, String evidenceSha256, Duration maxAge) {
    public ProductRetentionPolicy {
        if (reference == null || reference.isBlank() || version < 1
                || evidenceSha256 == null || !evidenceSha256.matches("[0-9a-fA-F]{64}")
                || maxAge == null || maxAge.isNegative() || maxAge.isZero())
            throw new IllegalArgumentException("Accountable task retention policy is required");
        evidenceSha256 = evidenceSha256.toLowerCase(java.util.Locale.ROOT);
    }
}
