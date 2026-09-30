package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.infrastructure.WorkspaceTestPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevComparisonInputPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";

    @Test void displayRequiresSignedCurrentPayloadAndHuman(@TempDir Path temporary) throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-comparison-input-v1"));
        Path manifest = temporary.resolve("manifest.json"), payload = temporary.resolve("input.json");
        Files.copy(signed.resolve("manifest.json"), manifest);
        Files.copy(signed.resolve("input.json"), payload);
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var human = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), NOW);
        var policy = new DevComparisonInputPolicy(new ObjectMapper(), manifest, subjects);
        assertThat(policy.allowsDisplay(human, "GOAL", GOAL, NOW)).isTrue();
        assertThat(policy.allowsDisplay(human, "GOAL", GOAL + "伪造", NOW)).isFalse();
        assertThat(policy.allowsDisplay(human, "UNKNOWN", GOAL, NOW)).isFalse();
        Files.writeString(payload, Files.readString(payload).replace(GOAL, GOAL + "篡改"));
        assertThat(policy.allowsDisplay(human, "GOAL", GOAL, NOW)).isFalse();
        Files.copy(signed.resolve("input.json"), payload, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(manifest, Files.readString(manifest).replace("\"withdrawalVersion\": 0",
                "\"withdrawalVersion\": 1"));
        assertThat(policy.allowsDisplay(human, "GOAL", GOAL, NOW)).isFalse();
        Files.copy(signed.resolve("manifest.json"), manifest, StandardCopyOption.REPLACE_EXISTING);
        subjects.revoke("human-a");
        assertThat(policy.allowsDisplay(human, "GOAL", GOAL, NOW)).isFalse();
    }

    @Test void startupRejectsMissingOrResignedManifest(@TempDir Path temporary) throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-comparison-input-v1"));
        Path manifest = temporary.resolve("manifest.json");
        var mapper = new ObjectMapper();
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        assertThatThrownBy(() -> new DevComparisonInputPolicy(mapper, manifest, subjects))
                .isInstanceOf(IllegalArgumentException.class);
        Files.copy(signed.resolve("manifest.json"), manifest);
        Files.copy(signed.resolve("input.json"), temporary.resolve("input.json"));
        Files.writeString(manifest, Files.readString(manifest).replace("\"DISPLAY\"", "\"MODEL_USE\""));
        assertThatThrownBy(() -> new DevComparisonInputPolicy(mapper, manifest, subjects))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
