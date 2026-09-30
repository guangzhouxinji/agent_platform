package cn.xinji.agentplatform.enforcement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnforcementIndependentIPrerequisitesTest {
    @TempDir Path root;

    @Test void onlyDistinctDm8EndpointsQualifyForDualDeployment() {
        assertThatCode(() -> EnforcementIndependentIPrerequisites.requireDualDm8(
                "jdbc:dm://127.0.0.1:5257", "jdbc:dm://127.0.0.1:5258")).doesNotThrowAnyException();
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireDualDm8(
                "jdbc:dm://LOCALHOST:5257", "jdbc:dm://127.0.0.1:5257?schema=OTHER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireDualDm8(
                "jdbc:h2:mem:fake", "jdbc:dm://localhost:5258"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireDualDm8(
                "jdbc:dm://localhost:5257", "jdbc:h2:mem:fake"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireDualDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void restoreCannotReuseEitherDatabaseOrOverlapOriginals() throws Exception {
        Path source = java.nio.file.Files.createDirectory(root.resolve("source"));
        Path restored = java.nio.file.Files.createDirectory(root.resolve("restored"));
        assertThatCode(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://localhost:5293",
                source, restored)).doesNotThrowAnyException();
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://LOCALHOST:5257",
                source, restored)).isInstanceOf(IllegalArgumentException.class);
        Path nested = java.nio.file.Files.createDirectory(source.resolve("nested"));
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://localhost:5293",
                source, nested)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://LOCALHOST:5258",
                source, restored)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://localhost:5293",
                nested, source)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireRestoredDm8(
                "jdbc:dm://localhost:5257", "jdbc:dm://localhost:5258", "jdbc:dm://localhost:5293",
                source, root.resolve("missing"))).isInstanceOf(java.io.IOException.class);
    }

    @Test void staleRecoveryHandshakeCannotBeReplayed() throws Exception {
        Path gate = root.resolve("checkpoint");
        assertThatCode(() -> EnforcementIndependentIPrerequisites.requireFreshRecoveryGate(gate))
                .doesNotThrowAnyException();
        for (String suffix : new String[]{"", ".resume", ".restore-ready", ".withdrawn", ".verified"}) {
            Path stale = Path.of(gate + suffix);
            Files.writeString(stale, "stale");
            assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireFreshRecoveryGate(gate))
                    .isInstanceOf(IllegalArgumentException.class);
            Files.delete(stale);
        }
        assertThatThrownBy(() -> EnforcementIndependentIPrerequisites.requireFreshRecoveryGate(
                root.resolve("missing").resolve("checkpoint"))).isInstanceOf(IllegalArgumentException.class);
    }
}
