package cn.xinji.agentplatform.enforcement.procedure;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class FileProcedureSourceTest {
    @TempDir Path temporary;

    @Test void missingReferencedOriginalCannotBeReportedAsPresent() throws Exception {
        var manifest = temporary.resolve("manifest.json");
        Files.copy(Path.of("data/enforcement/procedure-v2-acceptance-v1/manifest.json"), manifest);
        var empty = (MaterialRepository) Proxy.newProxyInstance(MaterialRepository.class.getClassLoader(),
                new Class<?>[]{MaterialRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("material")) return Optional.empty();
                    throw new AssertionError("Unexpected repository call: " + method.getName());
                });
        var source = new FileProcedureSource(manifest, new ObjectMapper(),
                new VerifiedProcedureMaterial(empty, new MaterialFiles() {
                    @Override public Staged stage(byte[] bytes) { throw new AssertionError("No file stage"); }
                    @Override public byte[] read(String key) { throw new AssertionError("No file read"); }
                    @Override public List<String> stagedKeys() { return List.of(); }
                    @Override public void discard(String key) { throw new AssertionError("No discard"); }
                },
                        null, "named-reader", Clock.systemUTC()));
        assertThatThrownBy(() -> source.caseSnapshot("PROJECT-CASE-PROCEDURE-01", "1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("material");
    }
    @Test void exactProjectSnapshotsStayVersionedAndNotOfficial() {
        var source = new FileProcedureSource(Path.of(
                "data/enforcement/procedure-v2-acceptance-v1/manifest.json"), new ObjectMapper(),
                (ref, organization) -> { });
        var complete = source.caseSnapshot("PROJECT-CASE-PROCEDURE-01", "1");
        assertThat(complete.events()).hasSize(2);
        assertThat(complete.sourceRef()).isEqualTo("ENF-PROCEDURE-PRODUCT-ACCEPTANCE-01");
        assertThat(source.caseSnapshot("PROJECT-CASE-PROCEDURE-MISSING", "1").events()).isEmpty();
        assertThat(source.caseSnapshot("PROJECT-CASE-OTHER-PROCEDURE", "1").procedure())
                .isEqualTo("OTHER_PROCEDURE");
        assertThat(source.calendar("PROJECT-CALENDAR-01", "1").sourceRef())
                .isEqualTo("PROJECT_TEST_ONLY_NOT_OFFICIAL");
        assertThatThrownBy(() -> source.caseSnapshot("PROJECT-CASE-PROCEDURE-01", "2"))
                .isInstanceOf(SecurityException.class);
    }
}
