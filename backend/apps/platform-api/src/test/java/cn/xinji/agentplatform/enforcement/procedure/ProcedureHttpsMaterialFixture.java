package cn.xinji.agentplatform.enforcement.procedure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class ProcedureHttpsMaterialFixture {
    private ProcedureHttpsMaterialFixture() { }

    static String prepare(MaterialRepository repository, MaterialFiles files, ObjectMapper json,
                          Path acceptanceManifest, String organization, String actor) throws Exception {
        return prepare(repository, files, json, acceptanceManifest, organization, actor,
                new JdbcTemplate(new DriverManagerDataSource(
                        "jdbc:h2:mem:enf-procedure-authority;DB_CLOSE_DELAY=-1", "sa", "")),
                "PROCEDURE-HTTP-SOURCE");
    }

    static String prepare(MaterialRepository repository, MaterialFiles files, ObjectMapper json,
                          Path acceptanceManifest, String organization, String actor,
                          JdbcTemplate authority, String source) throws Exception {
        MaterialAccess setup = new MaterialAccess() {
            @Override public void require(String a, String org, String type, String id,
                                          long version, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) { }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var material = new MaterialProductService(repository, files, setup, Clock.systemUTC());
        var collection = new MaterialCollectionService(repository, files, setup, Clock.systemUTC());
        material.registerSource(actor, "procedure-source", new MaterialProductService.SourceInput(
                source, organization, "PROJECT-PROCEDURE", 1, Instant.now().plusSeconds(3600),
                List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"), "project-owned-original"));
        var positive = material.observe(actor, "procedure-positive", new MaterialProductService.ObservationInput(
                source, "POS", "1", "project record", null));
        var negative = material.observe(actor, "procedure-negative", new MaterialProductService.ObservationInput(
                source, "NEG", "1", "missing", null));
        material.draftRule(actor, "procedure-discovery-draft", new MaterialProductService.RuleInput(
                "PROCEDURE-HTTP-DISCOVERY", source, "project", "project-method"));
        material.ruleState(actor, "procedure-discovery-publish", "PROCEDURE-HTTP-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var observed = material.observe(actor, "procedure-observe", new MaterialProductService.ObservationInput(
                source, "RECORD", "1", "project record original", null));
        var clue = material.discover(actor, "procedure-clue", observed.resultId(), "PROCEDURE-HTTP-DISCOVERY");
        var saved = material.registerMaterial(actor, "procedure-material", new MaterialProductService.MaterialInput(
                clue.resultId(), "Project procedure original, version one.\n".getBytes(StandardCharsets.UTF_8),
                "PROJECT-PROCEDURE", Instant.now().plusSeconds(3600),
                List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-29"));
        collection.attest(actor, "procedure-collect", saved.resultId(), 1, "RECORD", "Original collected");
        collection.verify("procedure-independent-verifier", "procedure-verify", saved.resultId(), 2,
                "Original SHA checked");
        var verified = repository.material(saved.resultId()).orElseThrow();
        String ref = verified.id() + "@" + verified.version();
        authority.update("INSERT INTO enf3_source_watermark(source_id,source_epoch,state) VALUES(?,?,'READY')",
                source, 1);
        authority.update("INSERT INTO enf3_delete_watermark(source_id,source_epoch,delete_epoch,state,valid_until) "
                + "VALUES(?,?,0,'READY',?)", source, 1, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        var root = json.readTree(Path.of("data/enforcement/procedure-v2-acceptance-v1/manifest.json").toFile());
        for (var item : root.path("caseInputs")) for (var entry : item.path("materials"))
            if ("PROJECT-RECORD-A@1".equals(entry.asText()))
                ((com.fasterxml.jackson.databind.node.ArrayNode) item.path("materials"))
                        .set(0, new com.fasterxml.jackson.databind.node.TextNode(ref));
        json.writeValue(acceptanceManifest.toFile(), root);
        return ref;
    }

    static void assertMissingOriginalFailsClosed(MaterialRepository repository, Path root,
                                                  String ref, Callable<Integer> readStatus) throws Exception {
        String id = ref.substring(0, ref.indexOf('@'));
        String key = repository.material(id).orElseThrow().fileKey();
        Path original = root.resolve(key + ".blob");
        Path held = root.resolve(key + ".held");
        Files.move(original, held, StandardCopyOption.ATOMIC_MOVE);
        try { assertThat(readStatus.call()).isEqualTo(503); }
        finally { Files.move(held, original, StandardCopyOption.ATOMIC_MOVE); }
    }
}
