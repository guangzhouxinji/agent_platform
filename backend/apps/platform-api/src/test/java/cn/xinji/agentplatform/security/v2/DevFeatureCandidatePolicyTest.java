package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.infrastructure.WorkspaceTestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevFeatureCandidatePolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Test void signedCandidatesAreBoundToInputAndCurrentHuman(@TempDir Path temporary) throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-feature-candidates-v1"));
        Path input = DevelopmentBoundaryPolicyTest.manifest().resolveSibling("task-input-manifest.json");
        Path manifest = temporary.resolve("manifest.json"), payload = temporary.resolve("candidates.json");
        Path source = temporary.resolve("task-input-manifest.json");
        Files.copy(signed.resolve("manifest.json"), manifest);
        Files.copy(signed.resolve("candidates.json"), payload);
        Files.copy(input, source);
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var human = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), NOW);
        var actor = new Actor(human.stableSubjectId(), human.organizationRef(), human.credentialVersion(),
                human.expiresAt());
        var policy = new DevFeatureCandidatePolicy(new ObjectMapper(), manifest, source, subjects);
        assertThat(policy.permitted(human, actor, true, NOW).candidates()).hasSize(3);
        assertThat(policy.permitted(human, actor, true, Instant.parse("2027-03-23T16:00:00Z"))).isNull();
        Files.writeString(payload, Files.readString(payload).replace("温度传感器", "伪造传感器"));
        assertThat(policy.permitted(human, actor, true, NOW)).isNull();
        Files.copy(signed.resolve("candidates.json"), payload, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(source, Files.readString(source).replace("\"withdrawalRef\": null",
                "\"withdrawalRef\": \"WITHDRAWN\""));
        assertThat(policy.permitted(human, actor, true, NOW)).isNull();
        Files.copy(input, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        subjects.revoke("human-a");
        assertThat(policy.permitted(human, actor, true, NOW)).isNull();
    }

    @Test void refusesUnsignedOrResignedDifferentFeature(@TempDir Path temporary) throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-feature-candidates-v1"));
        Path manifest = temporary.resolve("manifest.json"), payload = temporary.resolve("candidates.json");
        Files.copy(signed.resolve("manifest.json"), manifest);
        Files.writeString(payload, Files.readString(signed.resolve("candidates.json"))
                .replace("温度传感器", "伪造传感器"));
        assertThatThrownBy(() -> new DevFeatureCandidatePolicy(new ObjectMapper(), manifest,
                DevelopmentBoundaryPolicyTest.manifest().resolveSibling("task-input-manifest.json"),
                DevelopmentBoundaryPolicyTest.subjects())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void comparisonFeaturesRemainUnconfirmedAndFailClosedOnWithdrawal(@TempDir Path temporary)
            throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-comparison-input-v1"));
        Path manifest = temporary.resolve("manifest.json"), payload = temporary.resolve("input.json");
        Files.copy(signed.resolve("manifest.json"), manifest);
        Files.copy(signed.resolve("input.json"), payload);
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var human = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), NOW);
        var actor = new Actor(human.stableSubjectId(), human.organizationRef(), human.credentialVersion(),
                human.expiresAt());
        var policy = new DevComparisonInputPolicy(new ObjectMapper(), manifest, subjects);
        var source = policy.permittedFeatures(human, actor, true, NOW);
        assertThat(source.manifestId()).isEqualTo("DEV-CON-COMPARE-INPUT-01");
        assertThat(source.candidates()).extracting(candidate -> candidate.id())
                .containsExactly("F1", "F2", "F3");
        assertThat(policy.permittedComparisonFeatures(human, actor, NOW)).isNotNull();
        assertThat(policy.permittedComparisonClaim(human, actor, "P2", "V1",
                "58bbca6df4d997f3aecb097fe880882122457c1eee6fc4b1f82f44654577b316", NOW)
                .claimText()).isEqualTo("传感器、手动阀门");
        Files.writeString(manifest, Files.readString(manifest).replace("\"withdrawalVersion\": 0",
                "\"withdrawalVersion\": 1"));
        assertThat(policy.permittedFeatures(human, actor, true, NOW)).isNull();
        assertThat(policy.permittedComparisonFeatures(human, actor, NOW)).isNull();
        Files.copy(signed.resolve("manifest.json"), manifest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(payload, Files.readString(payload).replace("UNCONFIRMED", "CONFIRMED"));
        assertThat(policy.permittedFeatures(human, actor, true, NOW)).isNull();
        Files.copy(signed.resolve("input.json"), payload, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        subjects.revoke("human-a");
        assertThat(policy.permittedFeatures(human, actor, true, NOW)).isNull();
    }

    @Test void comparisonReconfirmationIsOffWithoutASeparateSwitch() {
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var human = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), NOW);
        var actor = new Actor(human.stableSubjectId(), human.organizationRef(), human.credentialVersion(),
                human.expiresAt());
        Path workspace = WorkspaceTestPaths.root();
        var old = new DevFeatureCandidatePolicy(new ObjectMapper(), workspace.resolve(Path.of("data",
                "consultation", "dev-feature-candidates-v1", "manifest.json")),
                DevelopmentBoundaryPolicyTest.manifest().resolveSibling("task-input-manifest.json"), subjects);
        var comparison = new DevComparisonInputPolicy(new ObjectMapper(), workspace.resolve(Path.of("data",
                "consultation", "dev-comparison-input-v1", "manifest.json")), subjects);
        var settings = new DevelopmentBoundaryProperties();
        var controller = new DevFeatureConfirmationController(null, null, old, comparison, settings);
        var request = new MockHttpServletRequest();
        request.setAttribute(V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE, human);
        var guard = controller.sources(request);
        assertThat(guard.permitted(actor, "DEV-CON-FEATURE-CANDIDATES-01", true, NOW)).isNotNull();
        assertThat(guard.permitted(actor, "DEV-CON-COMPARE-INPUT-01", true, NOW)).isNull();
        settings.setComparisonReconfirmEnabled(true);
        assertThat(guard.permitted(actor, "DEV-CON-COMPARE-INPUT-01", true, NOW)).isNotNull();
        assertThat(guard.permitted(actor, "UNKNOWN", true, NOW)).isNull();
    }
}
