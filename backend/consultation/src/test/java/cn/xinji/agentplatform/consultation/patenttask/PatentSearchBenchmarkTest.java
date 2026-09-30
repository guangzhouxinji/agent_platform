package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentSearchMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PatentSearchBenchmarkTest {
    @Test void registeredRealTitleBenchmarkMeetsVersionedEngineeringThresholds() throws Exception {
        Path root = workspace();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode benchmark = mapper.readTree(root.resolve(
                "data/patents/search-benchmark-v1/manifest.json").toFile());
        Path recordsPath = root.resolve("data/patents/public-pilot-v1/records.json");
        assertThat(sha(recordsPath)).isEqualTo(benchmark.path("sourceRecordsSha256").asText());
        List<PatentLexicalQuery.Document> documents = documents(mapper, recordsPath);
        var engine = new PatentBm25fQuery();
        var metrics = new PatentSearchMetrics();
        double recall = 0;
        double precision = 0;
        double ndcg = 0;
        double reciprocalRank = 0;
        int judged = 0;
        int emptyCorrect = 0;
        int emptyTotal = 0;
        for (JsonNode query : benchmark.path("queries")) {
            var first = engine.search(documents, query.path("query").asText(), Set.of("TITLE"), 50);
            var second = engine.search(documents, query.path("query").asText(), Set.of("TITLE"), 50);
            assertThat(first).isEqualTo(second);
            Map<String, Integer> qrels = mapper.convertValue(query.path("qrels"),
                    new TypeReference<Map<String, Integer>>() { });
            if (qrels.isEmpty()) {
                emptyTotal++;
                if (first.matches().isEmpty()) emptyCorrect++;
            } else {
                var ranked = first.matches().stream()
                        .map(PatentBm25fQuery.Match::documentId).toList();
                var scores = metrics.evaluate(ranked, qrels, 50);
                var topTen = metrics.evaluate(ranked, qrels, 10);
                recall += scores.recall();
                precision += topTen.precision();
                ndcg += topTen.ndcg();
                reciprocalRank += topTen.reciprocalRank();
                judged++;
            }
        }
        assertThat(recall / judged).isGreaterThanOrEqualTo(
                benchmark.path("thresholds").path("recallAt50").asDouble());
        assertThat(ndcg / judged).isGreaterThanOrEqualTo(
                benchmark.path("thresholds").path("ndcgAt10").asDouble());
        assertThat((double) emptyCorrect / emptyTotal).isEqualTo(
                benchmark.path("thresholds").path("emptyQueryAccuracy").asDouble());
        System.out.printf("PATENT_SEARCH_BENCHMARK recall@50=%.6f precision@10=%.6f "
                        + "ndcg@10=%.6f mrr=%.6f emptyAccuracy=%.6f queries=%d%n",
                recall / judged, precision / judged, ndcg / judged, reciprocalRank / judged,
                (double) emptyCorrect / emptyTotal, judged + emptyTotal);
    }

    @Test void currentRealDatasetIsStableAndWithinRegisteredLatency() throws Exception {
        Path root = workspace();
        ObjectMapper mapper = new ObjectMapper();
        var documents = documents(mapper, root.resolve("data/patents/public-pilot-v1/records.json"));
        var engine = new PatentBm25fQuery();
        var expected = engine.search(documents, "lithium battery", Set.of("TITLE"), 100);
        long[] elapsed = new long[100];
        for (int index = 0; index < elapsed.length; index++) {
            long start = System.nanoTime();
            assertThat(engine.search(documents, "lithium battery", Set.of("TITLE"), 100))
                    .isEqualTo(expected);
            elapsed[index] = System.nanoTime() - start;
        }
        long[] sorted = LongStream.of(elapsed).sorted().toArray();
        double p95Millis = sorted[94] / 1_000_000.0;
        double p99Millis = sorted[98] / 1_000_000.0;
        assertThat(p95Millis).isLessThanOrEqualTo(1_000.0);
        assertThat(p99Millis).isLessThanOrEqualTo(2_000.0);
        System.out.printf("PATENT_SEARCH_PERFORMANCE documents=%d runs=100 p95Ms=%.3f p99Ms=%.3f "
                + "deterministic=1.000000%n", documents.size(), p95Millis, p99Millis);
    }

    @Test void versionedExpansionDoesNotLoseRealBenchmarkRecallOrOriginalCandidates() throws Exception {
        Path root = workspace();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode benchmark = mapper.readTree(root.resolve(
                "data/patents/search-benchmark-v1/manifest.json").toFile());
        List<PatentLexicalQuery.Document> documents = documents(mapper,
                root.resolve("data/patents/public-pilot-v1/records.json"));
        var engine = new PatentBm25fQuery();
        var expansion = new PatentQueryExpansion();
        var metrics = new PatentSearchMetrics();
        double baselineRecall = 0;
        double expandedRecall = 0;
        double baselineNdcg = 0;
        double expandedNdcg = 0;
        int expandedQueries = 0;
        for (JsonNode query : benchmark.path("queries")) {
            Map<String, Integer> qrels = mapper.convertValue(query.path("qrels"),
                    new TypeReference<Map<String, Integer>>() { });
            var plan = expansion.expand(query.path("query").asText());
            if (plan.expansions().isEmpty() || qrels.isEmpty()) continue;
            var baseline = engine.search(documents, plan.originalQuery(), Set.of("TITLE"), 50);
            var expanded = engine.search(documents, plan.retrievalQuery(), Set.of("TITLE"), 50);
            var baselineIds = baseline.matches().stream().map(PatentBm25fQuery.Match::documentId).toList();
            var expandedIds = expanded.matches().stream().map(PatentBm25fQuery.Match::documentId).toList();
            assertThat(expandedIds).containsAll(baselineIds);
            baselineRecall += metrics.evaluate(baselineIds, qrels, 50).recall();
            expandedRecall += metrics.evaluate(expandedIds, qrels, 50).recall();
            baselineNdcg += metrics.evaluate(baselineIds, qrels, 10).ndcg();
            expandedNdcg += metrics.evaluate(expandedIds, qrels, 10).ndcg();
            expandedQueries++;
        }
        assertThat(expandedQueries).isGreaterThanOrEqualTo(4);
        assertThat(expandedRecall / expandedQueries)
                .isGreaterThanOrEqualTo(baselineRecall / expandedQueries);
        System.out.printf("PATENT_QUERY_EXPANSION_BENCHMARK queries=%d baselineRecall@50=%.6f "
                        + "expandedRecall@50=%.6f baselineNdcg@10=%.6f expandedNdcg@10=%.6f%n",
                expandedQueries, baselineRecall / expandedQueries, expandedRecall / expandedQueries,
                baselineNdcg / expandedQueries, expandedNdcg / expandedQueries);
    }

    private static List<PatentLexicalQuery.Document> documents(ObjectMapper mapper, Path recordsPath)
            throws Exception {
        JsonNode records = mapper.readTree(recordsPath.toFile()).path("records");
        JsonNode manifest = mapper.readTree(recordsPath.resolveSibling("manifest.json").toFile());
        Map<String, String> hashes = new HashMap<>();
        for (JsonNode raw : manifest.path("rawResponses"))
            hashes.put(raw.path("file").asText(), raw.path("sha256").asText());
        String sourceVersion = manifest.path("datasetId").asText() + ':'
                + manifest.path("generatedAt").asText();
        List<PatentLexicalQuery.Document> result = new ArrayList<>();
        for (JsonNode row : records) {
            String raw = row.path("detailRawFile").asText();
            result.add(new PatentLexicalQuery.Document("SURECHEMBL_PUBLIC_SEARCH",
                    row.path("sourceRecordId").asText(), row.path("sourceVersion").asText(),
                    sourceVersion, hashes.get(raw), raw + "#title",
                    Map.of("TITLE", row.path("title").asText())));
        }
        return result;
    }

    private static Path workspace() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/08-总开发实现计划.md")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace is unavailable");
        return root;
    }

    private static String sha(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
