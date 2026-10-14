package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real pinned models against unchanged real-data filter qrels; no professional gold labels. */
class ProductPatentHybridFilterBenchmarkTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PatentBm25fQuery lexical = new PatentBm25fQuery();
    private final PatentQueryExpansion expansion = new PatentQueryExpansion();
    private final PatentRetrievalConfidence confidence = new PatentRetrievalConfidence();

    @Test
    @EnabledIfSystemProperty(named = "patent.reranker.model", matches = ".+")
    void realHybridFiltersConfidenceAndHundredReplaysRespectRegisteredThresholds() throws Exception {
        Path root = workspace();
        JsonNode benchmark = mapper.readTree(root.resolve(
                "data/patents/search-benchmark-v2/manifest.json").toFile());
        Path capture = root.resolve("data/patents/public-pilot-v1");
        JsonNode manifest = mapper.readTree(capture.resolve("manifest.json").toFile());
        assertThat(sha(capture.resolve("records.json")))
                .isEqualTo(benchmark.path("sourceRecordsSha256").asText());
        var source = new ProductSourceAvailability.Active("SURECHEMBL_PUBLIC_SEARCH", "contract-v1",
                manifest.path("datasetId").asText() + ':' + manifest.path("generatedAt").asText(),
                "decision-v1", 1, 1, "public-pilot-v1/manifest.json",
                sha(capture.resolve("manifest.json")), manifest.path("recordsSha256").asText());
        var loader = new ProductCollectedPatentTitles(
                new ProductSourceEvidenceFiles(root.resolve("data/patents"), 10_000_000), mapper);
        try (var embeddings = embeddings(); var reranker = reranker()) {
            List<PatentLexicalQuery.Document> all = loader.load(source);
            List<float[]> vectors = embeddings.embed(all.stream().map(d -> d.fields().get("TITLE")).toList());
            Map<String, float[]> index = new HashMap<>();
            for (int i = 0; i < all.size(); i++) index.put(key(all.get(i)), vectors.get(i));
            double recall = 0, precision = 0, ndcg = 0;
            int judged = 0, lexicalNegative = 0, lexicalNegativeEmpty = 0;
            var metrics = new PatentSearchMetrics();
            for (JsonNode query : benchmark.path("queries")) {
                var filters = ProductPatentSearchFilters.parse(mapper,
                        mapper.writeValueAsString(query.path("filters")));
                var documents = loader.load(source, filters);
                Outcome result = run(documents, index, query.path("query").asText(), embeddings, reranker);
                assertThat(run(documents, index, query.path("query").asText(), embeddings, reranker))
                        .isEqualTo(result);
                Map<String, Integer> qrels = mapper.convertValue(query.path("qrels"),
                        new TypeReference<Map<String, Integer>>() { });
                if (qrels.isEmpty()) {
                    lexicalNegative++;
                    if (result.ids().isEmpty()) lexicalNegativeEmpty++;
                    // Keep the original negative and labels; v2 query abstention must now satisfy it.
                    assertThat(result.assessment().status())
                            .isEqualTo(PatentRetrievalConfidence.Status.NO_RESULT);
                } else {
                    recall += metrics.evaluate(result.ids(), qrels, 50).recall();
                    precision += metrics.evaluate(result.ids(), qrels, 10).precision();
                    ndcg += metrics.evaluate(result.ids(), qrels, 10).ndcg();
                    judged++;
                }
            }
            assertThat(judged).isEqualTo(4);
            assertThat(lexicalNegative).isEqualTo(1);
            assertThat((double) lexicalNegativeEmpty / lexicalNegative).isEqualTo(
                    benchmark.path("thresholds").path("emptyQueryAccuracy").asDouble());
            assertThat(recall / judged).isGreaterThanOrEqualTo(
                    benchmark.path("thresholds").path("recallAt50").asDouble());
            assertThat(ndcg / judged).isGreaterThanOrEqualTo(
                    benchmark.path("thresholds").path("ndcgAt10").asDouble());
            Outcome low = run(all, index, "aqueous adhesive", embeddings, reranker);
            assertThat(low.ids()).isNotEmpty();
            assertThat(low.assessment().status()).isEqualTo(PatentRetrievalConfidence.Status.LOW_CONFIDENCE);
            assertThat(low.assessment().reason()).isEqualTo("NO_LEXICAL_SEMANTIC_OVERLAP_IN_TOP_50");
            var hardEmptyFilters = ProductPatentSearchFilters.parse(mapper,
                    "{\"publicationFrom\":\"2030-01-01\",\"publicationTo\":\"2030-12-31\","
                    + "\"ipcPrefixes\":[\"H01M\"],\"cpcPrefixes\":[\"H01M\"]}");
            Outcome hardEmpty = run(loader.load(source, hardEmptyFilters), index,
                    "lithium battery", embeddings, reranker);
            assertThat(hardEmpty.ids()).isEmpty();
            assertThat(hardEmpty.assessment().status()).isEqualTo(PatentRetrievalConfidence.Status.NO_RESULT);
            String query = "lithium battery";
            Outcome expected = run(all, index, query, embeddings, reranker);
            long[] times = new long[100];
            for (int i = 0; i < times.length; i++) {
                long started = System.nanoTime();
                assertThat(run(all, index, query, embeddings, reranker)).isEqualTo(expected);
                times[i] = System.nanoTime() - started;
            }
            Arrays.sort(times);
            double p95 = times[94] / 1_000_000.0, p99 = times[98] / 1_000_000.0;
            assertThat(p95).isLessThanOrEqualTo(1000);
            assertThat(p99).isLessThanOrEqualTo(2000);
            System.out.printf("PATENT_HYBRID_FILTER_BENCHMARK originalQueries=5 judged=4 "
                    + "lexicalNegative=1 recall@50=%.6f precision@10=%.6f ndcg@10=%.6f "
                    + "lexicalNegativeEmptyAccuracy=%.6f hardFilterEmptyCases=1 hardFilterEmptyCorrect=1 "
                    + "lowConfidenceCases=1 runs=100 p95Ms=%.3f p99Ms=%.3f deterministic=1.000000%n",
                    recall / judged, precision / judged, ndcg / judged,
                    (double) lexicalNegativeEmpty / lexicalNegative, p95, p99);
        }
    }

    private Outcome run(List<PatentLexicalQuery.Document> docs, Map<String, float[]> index, String query,
            OnnxPatentEmbeddingProvider embeddings, OnnxPatentReranker reranker) {
        String planned = expansion.expand(query).retrievalQuery();
        var sparse = lexical.search(docs, planned, Set.of("TITLE"), 1000);
        if (docs.isEmpty()) return new Outcome(List.of(), confidence.assess(List.of()));
        float[] vector = embeddings.embed(List.of(planned)).getFirst();
        List<PatentHybridQuery.SemanticMatch> dense = new ArrayList<>();
        Map<String, String> titles = new HashMap<>();
        for (var doc : docs) {
            float[] stored = index.get(key(doc));
            double score = 0;
            for (int i = 0; i < vector.length; i++) score += vector[i] * stored[i];
            dense.add(new PatentHybridQuery.SemanticMatch(doc.sourceId(), doc.documentId(),
                    doc.documentVersion(), doc.sourceVersion(), doc.rawSha(), doc.locatorRef(), score));
            titles.put(key(doc), doc.fields().get("TITLE"));
        }
        dense.sort(Comparator.comparingDouble(PatentHybridQuery.SemanticMatch::semanticScore).reversed()
                .thenComparing(PatentHybridQuery.SemanticMatch::documentId));
        var fused = new PatentHybridQuery().fuse(sparse, dense, 200);
        var ranked = new PatentRerankedQuery().rerank(planned, fused, titles, reranker, embeddings.model(), 100);
        return new Outcome(ranked.matches().stream().map(m -> m.candidate().documentId()).toList(),
                confidence.assess(ranked.matches().isEmpty() ? List.of() : fused.matches()));
    }

    private record Outcome(List<String> ids, PatentRetrievalConfidence.Assessment assessment) { }
    private static String key(PatentLexicalQuery.Document d) {
        return d.sourceId() + '\0' + d.documentId() + '\0' + d.documentVersion();
    }
    private static OnnxPatentEmbeddingProvider embeddings() {
        return new OnnxPatentEmbeddingProvider(Path.of(System.getProperty("patent.embedding.model")),
                Path.of(System.getProperty("patent.embedding.tokenizer")),
                new PatentEmbeddingProvider.Model("ONNX_RUNTIME",
                        "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
                        "e8f8c211226b894fcb81acc59f3b34ba3efd5f42",
                        "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f",
                        "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8",
                        384, 128, "MEAN_POOL_L2/v1", "Apache-2.0"));
    }
    private static OnnxPatentReranker reranker() {
        return new OnnxPatentReranker(Path.of(System.getProperty("patent.reranker.model")),
                Path.of(System.getProperty("patent.reranker.tokenizer")),
                new PatentReranker.Model("ONNX_RUNTIME", "cross-encoder/ms-marco-MiniLM-L-6-v2",
                        "233902d25c440f23af6f7d6e94d2946bac0bee0a",
                        "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9",
                        "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
                        512, "Apache-2.0"));
    }
    private static Path workspace() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/08-总开发实现计划.md")))
            root = root.getParent();
        if (root == null) throw new IllegalStateException("Workspace unavailable");
        return root;
    }
    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
