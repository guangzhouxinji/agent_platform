package cn.xinji.agentplatform.security.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class DevelopmentSubjectPolicy {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private final AtomicReference<Map<String, Entry>> entries;

    public DevelopmentSubjectPolicy(DevelopmentBoundaryProperties properties) {
        Map<String, Entry> configured = new HashMap<>();
        Set<String> principals = new HashSet<>();
        Set<String> secretHashes = new HashSet<>();
        int humans = 0, agents = 0, jobs = 0;
        for (var item : properties.getCredentials()) {
            String keyId = required(item.getKeyId());
            String principal = required(item.getPrincipalRef());
            String organization = required(item.getOrganizationRef());
            String hash = required(item.getSecretSha256());
            if (!KEY_ID.matcher(keyId).matches() || !HASH.matcher(hash).matches()
                    || item.getKind() == null || item.getStatus() == null || item.getExpiresAt() == null
                    || !principals.add(principal) || !secretHashes.add(hash.toLowerCase())) {
                throw new IllegalArgumentException("Invalid V2 development credential configuration");
            }
            if (configured.putIfAbsent(keyId, new Entry(principal, organization, item.getKind(),
                    item.getStatus(), item.getExpiresAt(), HexFormat.of().parseHex(hash), 1)) != null) {
                throw new IllegalArgumentException("Duplicate V2 development key ID");
            }
            switch (item.getKind()) {
                case HUMAN -> humans++;
                case AGENT -> agents++;
                case JOB -> jobs++;
            }
        }
        if (humans < 2 || agents < 1 || jobs < 1) {
            throw new IllegalArgumentException("V2 development requires two humans, an agent and a job identity");
        }
        entries = new AtomicReference<>(Map.copyOf(configured));
    }

    public Subject authenticate(String authorization, Instant now) {
        if (authorization == null || !authorization.startsWith("Bearer xj_")) return null;
        String token = authorization.substring("Bearer xj_".length());
        int dot = token.indexOf('.');
        if (dot < 3 || dot == token.length() - 1) return null;
        Entry entry = entries.get().get(token.substring(0, dot));
        String secret = token.substring(dot + 1);
        if (entry == null || secret.length() < 32 || entry.status != DevelopmentBoundaryProperties.Status.ACTIVE
                || !now.isBefore(entry.expiresAt) || !MessageDigest.isEqual(sha256(secret), entry.secretHash)) {
            return null;
        }
        return new Subject("LOCAL_DEV", entry.principal, entry.organization, entry.kind,
                entry.version, entry.expiresAt);
    }

    public boolean current(Subject subject, Instant now) {
        if (subject == null || !now.isBefore(subject.expiresAt())) return false;
        return "LOCAL_DEV".equals(subject.issuer()) && entries.get().values().stream()
                .anyMatch(entry -> entry.principal.equals(subject.stableSubjectId())
                && entry.organization.equals(subject.organizationRef()) && entry.kind == subject.kind()
                && entry.status == DevelopmentBoundaryProperties.Status.ACTIVE
                && entry.version == subject.credentialVersion() && now.isBefore(entry.expiresAt));
    }

    public boolean currentDelegation(String principal, String organization, long authorizationVersion,
                                     Instant delegatedUntil, Instant now) {
        if (principal == null || organization == null || delegatedUntil == null
                || !now.isBefore(delegatedUntil)) return false;
        return entries.get().values().stream().anyMatch(entry -> entry.principal.equals(principal)
                && entry.organization.equals(organization)
                && entry.kind == DevelopmentBoundaryProperties.Kind.HUMAN
                && entry.status == DevelopmentBoundaryProperties.Status.ACTIVE
                && entry.version == authorizationVersion && now.isBefore(entry.expiresAt));
    }

    public void revoke(String keyId) {
        entries.updateAndGet(current -> {
            Entry prior = current.get(keyId);
            if (prior == null) throw new IllegalArgumentException("Unknown V2 development key ID");
            Map<String, Entry> next = new HashMap<>(current);
            next.put(keyId, new Entry(prior.principal, prior.organization, prior.kind,
                    DevelopmentBoundaryProperties.Status.REVOKED, prior.expiresAt, prior.secretHash, prior.version + 1));
            return Map.copyOf(next);
        });
    }

    public boolean permits(Subject subject, DevelopmentBoundaryProperties.Kind actionKind,
                           String resourceOwner, Instant now) {
        return decide(subject, actionKind, resourceOwner, now).status() == DecisionStatus.ALLOW;
    }

    public Decision decide(Subject subject, DevelopmentBoundaryProperties.Kind actionKind,
                           String resourceOwner, Instant now) {
        if (!current(subject, now) || actionKind == null || resourceOwner == null
                || subject.kind() != actionKind || !resourceOwner.equals(subject.stableSubjectId())) {
            return new Decision(DecisionStatus.DENY, subject == null ? 0 : subject.credentialVersion(),
                    subject == null ? null : subject.expiresAt());
        }
        return new Decision(DecisionStatus.ALLOW, subject.credentialVersion(), subject.expiresAt());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing V2 development credential field");
        return value.trim();
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Subject {
        private final String issuer;
        private final String stableSubjectId;
        private final String organizationRef;
        private final DevelopmentBoundaryProperties.Kind kind;
        private final long credentialVersion;
        private final Instant expiresAt;

        private Subject(String issuer, String stableSubjectId, String organizationRef,
                        DevelopmentBoundaryProperties.Kind kind, long credentialVersion, Instant expiresAt) {
            this.issuer = issuer;
            this.stableSubjectId = stableSubjectId;
            this.organizationRef = organizationRef;
            this.kind = kind;
            this.credentialVersion = credentialVersion;
            this.expiresAt = expiresAt;
        }

        public String issuer() { return issuer; }
        public String stableSubjectId() { return stableSubjectId; }
        public String organizationRef() { return organizationRef; }
        public DevelopmentBoundaryProperties.Kind kind() { return kind; }
        public long credentialVersion() { return credentialVersion; }
        public Instant expiresAt() { return expiresAt; }
    }
    public enum DecisionStatus { ALLOW, RESTRICTED, DENY }
    public record Decision(DecisionStatus status, long authorizationVersion, Instant effectiveUntil) { }

    private record Entry(String principal, String organization, DevelopmentBoundaryProperties.Kind kind,
                         DevelopmentBoundaryProperties.Status status, Instant expiresAt, byte[] secretHash,
                         long version) { }
}
