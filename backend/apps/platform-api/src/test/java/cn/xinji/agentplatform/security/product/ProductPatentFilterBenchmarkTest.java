package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentSearchMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductPatentFilterBenchmarkTest {
    @Test void immutableRealMetadataBenchmarkCoversFilterCombinationsAndEmptyResults() throws Exception {
        Path root = workspace();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode benchmark = mapper.readTree(root.resolve(
                "data/patents/search-benchmark-v2/manifest.json").toFile());
        Path capture = root.resolve("data/patents/public-pilot-v1");
        JsonNode sourceManifest = mapper.readTree(capture.resolve("manifest.json").toFile());
        assertThat(sha(capture.resolve("records.json")))
                .isEqualTo(benchmark.path("sourceRecordsSha256").asText());
        var active = new ProductSourceAvailability.Active("SURECHEMBL_PUBLIC_SEARCH", "contract-v1",
                sourceManifest.path("datasetId").asText() + ':' + sourceManifest.path("generatedAt").asText(),
                "decision-v1", 1, 1, "public-pilot-v1/manifest.json",
                sha(capture.resolve("manifest.json")), sourceManifest.path("recordsSha256").asText());
        var loader = new ProductCollectedPatentTitles(
                new ProductSourceEvidenceFiles(root.resolve("data/patents"), 10_000_000), mapper);
        var engine = new PatentBm25fQuery();
        var metrics = new PatentSearchMetrics();
        double recall = 0, ndcg = 0;
        int judged = 0, empty = 0, emptyCorrect = 0;
        for (JsonNode query : benchmark.path("queries")) {
            var filters = ProductPatentSearchFilters.parse(mapper,
                    mapper.writeValueAsString(query.path("filters")));
            var documents = loader.load(active, filters);
            var first = engine.search(documents, query.path("query").asText(), Set.of("TITLE"), 50);
            var replay = engine.search(documents, query.path("query").asText(), Set.of("TITLE"), 50);
            assertThat(replay).isEqualTo(first);
            Map<String, Integer> qrels = mapper.convertValue(query.path("qrels"),
                    new TypeReference<Map<String, Integer>>() { });
            if (qrels.isEmpty()) {
                empty++;
                if (first.matches().isEmpty()) emptyCorrect++;
            } else {
                var ranked = first.matches().stream().map(PatentBm25fQuery.Match::documentId).toList();
                recall += metrics.evaluate(ranked, qrels, 50).recall();
                ndcg += metrics.evaluate(ranked, qrels, 10).ndcg();
                judged++;
            }
        }
        assertThat(judged).isEqualTo(4);
        assertThat(recall / judged).isGreaterThanOrEqualTo(
                benchmark.path("thresholds").path("recallAt50").asDouble());
        assertThat(ndcg / judged).isGreaterThanOrEqualTo(
                benchmark.path("thresholds").path("ndcgAt10").asDouble());
        assertThat((double) emptyCorrect / empty).isEqualTo(1.0);
        System.out.printf("PATENT_FILTER_BENCHMARK queries=%d recall@50=%.6f ndcg@10=%.6f "
                + "emptyAccuracy=%.6f%n", judged + empty, recall / judged, ndcg / judged,
                (double) emptyCorrect / empty);
    }

    private static Path workspace() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/08-总开发实现计划.md")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace is unavailable");
        return root;
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
