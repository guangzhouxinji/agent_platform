package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentBoundaryPolicyTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");
    private static final String RELATIVE_MANIFEST = "data/consultation/dev-synthetic-v1/manifest.json";

    @Test void authenticatesDistinctKindsAndRevocationInvalidatesAnExistingSubject() throws Exception {
        var subjects = subjects();
        var human = subjects.authenticate(token("human-a"), NOW);
        var agent = subjects.authenticate(token("agent"), NOW);
        assertThat(human.stableSubjectId()).isEqualTo("dev-con-a");
        assertThat(human.organizationRef()).isEqualTo("dev-con-isolated");
        assertThat(subjects.permits(human, DevelopmentBoundaryProperties.Kind.HUMAN, "dev-con-a", NOW)).isTrue();
        assertThat(subjects.decide(human, DevelopmentBoundaryProperties.Kind.HUMAN, "dev-con-a", NOW)
                .authorizationVersion()).isEqualTo(human.credentialVersion());
        assertThat(subjects.currentDelegation("dev-con-a", "dev-con-isolated", human.credentialVersion(),
                NOW.plusSeconds(60), NOW)).isTrue();
        assertThat(subjects.currentDelegation("dev-con-a", "other-org", human.credentialVersion(),
                NOW.plusSeconds(60), NOW)).isFalse();
        assertThat(subjects.permits(human, DevelopmentBoundaryProperties.Kind.HUMAN, "dev-con-b", NOW)).isFalse();
        assertThat(subjects.decide(human, DevelopmentBoundaryProperties.Kind.HUMAN, null, NOW).status())
                .isEqualTo(DevelopmentSubjectPolicy.DecisionStatus.DENY);
        assertThat(subjects.permits(agent, DevelopmentBoundaryProperties.Kind.HUMAN, "dev-con-agent", NOW)).isFalse();
        assertThat(subjects.permits(human, DevelopmentBoundaryProperties.Kind.AGENT, "dev-con-a", NOW)).isFalse();
        assertThat(subjects.authenticate(token("human-a"), Instant.parse("2099-01-01T00:00:00Z"))).isNull();
        subjects.revoke("human-a");
        assertThat(subjects.current(human, NOW)).isFalse();
        assertThat(subjects.currentDelegation("dev-con-a", "dev-con-isolated", human.credentialVersion(),
                NOW.plusSeconds(60), NOW)).isFalse();
        assertThat(subjects.authenticate(token("human-a"), NOW)).isNull();
    }

    @Test void enforcesEveryUseAndImmediateWithdrawal() {
        var subjects = subjects();
        var use = new SyntheticUsePolicy(new ObjectMapper(), manifest(), subjects);
        var human = subjects.authenticate(token("human-a"), NOW);
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "claimText",
                SyntheticUsePolicy.Use.QUERY, NOW)).isTrue();
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "claimText",
                SyntheticUsePolicy.Use.DOWNLOAD, NOW)).isTrue();
        for (var denied : List.of(SyntheticUsePolicy.Use.MODEL_USE, SyntheticUsePolicy.Use.CROSS_DOMAIN)) {
            assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "claimText", denied, NOW)).isFalse();
        }
        assertThat(use.allows(human, "DEV-CON-QUERY-01", "Q1", "V1", "recordId",
                SyntheticUsePolicy.Use.QUERY, NOW)).isTrue();
        for (var denied : List.of(SyntheticUsePolicy.Use.SAVE, SyntheticUsePolicy.Use.INDEX,
                SyntheticUsePolicy.Use.DISPLAY, SyntheticUsePolicy.Use.DOWNLOAD)) {
            assertThat(use.allows(human, "DEV-CON-QUERY-01", "Q1", "V1", "recordId", denied, NOW)).isFalse();
        }
        assertThat(use.allows(human, "CON-04-PUBLIC-PILOT-V1", "P1", "V1", "claimText",
                SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V2", "claimText",
                SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "unknown",
                SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
        var wrongOrganization = subjects("other-org").authenticate(token("human-a"), NOW);
        assertThat(use.allows(wrongOrganization,
                "DEV-CON-SYN-01", "P1", "V1", "claimText", SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "claimText",
                SyntheticUsePolicy.Use.QUERY, Instant.parse("2027-03-23T16:00:00Z"))).isFalse();
        assertThat(use.allows(subjects.authenticate(token("agent"), NOW), "DEV-CON-SYN-01",
                "P1", "V1", "claimText", SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
        use.withdraw("DEV-CON-SYN-01");
        assertThat(use.allows(human, "DEV-CON-SYN-01", "P1", "V1", "claimText",
                SyntheticUsePolicy.Use.QUERY, NOW)).isFalse();
    }

    @Test void refusesTamperedContentsAndForbiddenManifestActions(@TempDir Path directory) throws Exception {
        String original = Files.readString(manifest());
        Path changed = directory.resolve("manifest.json");
        Files.writeString(changed, original.replace("传感器、控制器、阀门", "传感器、控制器、伪造阀门"));
        assertThatThrownBy(() -> new SyntheticUsePolicy(new ObjectMapper(), changed, subjects()))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(changed, original.replace("\"DOWNLOAD\"", "\"MODEL_USE\""));
        assertThatThrownBy(() -> new SyntheticUsePolicy(new ObjectMapper(), changed, subjects()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void refusesReusedCredentialSecret() {
        var properties = new DevelopmentBoundaryProperties();
        var first = credential("human-a", "dev-con-a", DevelopmentBoundaryProperties.Kind.HUMAN);
        var second = credential("human-b", "dev-con-b", DevelopmentBoundaryProperties.Kind.HUMAN);
        second.setSecretSha256(first.getSecretSha256());
        properties.setCredentials(List.of(first, second,
                credential("agent", "dev-con-agent", DevelopmentBoundaryProperties.Kind.AGENT),
                credential("job", "dev-con-job", DevelopmentBoundaryProperties.Kind.JOB)));
        assertThatThrownBy(() -> new DevelopmentSubjectPolicy(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static DevelopmentSubjectPolicy subjects() {
        return subjects("dev-con-isolated");
    }

    private static DevelopmentSubjectPolicy subjects(String organization) {
        var properties = new DevelopmentBoundaryProperties();
        properties.setCredentials(List.of(credential("human-a", "dev-con-a", DevelopmentBoundaryProperties.Kind.HUMAN, organization),
                credential("human-b", "dev-con-b", DevelopmentBoundaryProperties.Kind.HUMAN, organization),
                credential("agent", "dev-con-agent", DevelopmentBoundaryProperties.Kind.AGENT, organization),
                credential("job", "dev-con-job", DevelopmentBoundaryProperties.Kind.JOB, organization)));
        return new DevelopmentSubjectPolicy(properties);
    }

    static String token(String keyId) { return "Bearer xj_" + keyId + "." + keyId + "-" + SECRET; }

    static Path manifest() {
        Path fromRoot = Path.of(RELATIVE_MANIFEST).toAbsolutePath();
        Path fromBackend = Path.of("..", RELATIVE_MANIFEST).toAbsolutePath();
        if (Files.isRegularFile(fromRoot)) return fromRoot;
        if (Files.isRegularFile(fromBackend)) return fromBackend;
        throw new IllegalStateException("Synthetic manifest missing from workspace");
    }

    private static DevelopmentBoundaryProperties.Credential credential(String keyId, String principal,
                                                                        DevelopmentBoundaryProperties.Kind kind) {
        return credential(keyId, principal, kind, "dev-con-isolated");
    }

    private static DevelopmentBoundaryProperties.Credential credential(String keyId, String principal,
                                                                        DevelopmentBoundaryProperties.Kind kind,
                                                                        String organization) {
        var item = new DevelopmentBoundaryProperties.Credential();
        item.setKeyId(keyId);
        try { item.setSecretSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((keyId + "-" + SECRET).getBytes()))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        item.setPrincipalRef(principal);
        item.setOrganizationRef(organization);
        item.setKind(kind);
        item.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
        return item;
    }
}
