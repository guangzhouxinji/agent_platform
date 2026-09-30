package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.infrastructure.WorkspaceTestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevTaskInputPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");
    private static final Path MANIFEST = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation", "dev-synthetic-v1",
            "task-input-manifest.json"));

    @Test void acceptsOnlySignedInSyntheticSaveInputFromCurrentHuman() {
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var policy = new DevTaskInputPolicy(new ObjectMapper(), MANIFEST, subjects);
        var human = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), NOW);
        String goal = "查找并比较用于温度调节的传感器、控制器和阀门方案";
        assertThat(policy.allows(human, "GOAL", goal, NOW)).isTrue();
        assertThat(policy.allows(human, "GOAL", goal + "!", NOW)).isFalse();
        assertThat(policy.allows(human, "SUPPLEMENT", goal, NOW)).isFalse();
        assertThat(policy.allows(subjects.authenticate(DevelopmentBoundaryPolicyTest.token("agent"), NOW),
                "GOAL", goal, NOW)).isFalse();
        subjects.revoke("human-a");
        assertThat(policy.allows(human, "GOAL", goal, NOW)).isFalse();
    }

    @Test void refusesChangedContentWithoutUpdatedDigest(@TempDir Path directory) throws Exception {
        Path changed = directory.resolve("input.json");
        Files.writeString(changed, Files.readString(MANIFEST).replace("温度调节", "压力调节"));
        assertThatThrownBy(() -> new DevTaskInputPolicy(new ObjectMapper(), changed,
                DevelopmentBoundaryPolicyTest.subjects())).isInstanceOf(IllegalArgumentException.class);
    }
}
