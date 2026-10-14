package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentEmbeddingProvider;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentHybridQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentQueryExpansion;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentRerankedQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentSearchMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class PatentRerankerBenchmarkTest {
    private static final String EMBEDDING_REVISION = "e8f8c211226b894fcb81acc59f3b34ba3efd5f42";
    private static final String EMBEDDING_SHA = "98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f";
    private static final String EMBEDDING_TOKENIZER_SHA =
            "2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8";
    private static final String RERANK_REVISION = "233902d25c440f23af6f7d6e94d2946bac0bee0a";
    private static final String RERANK_SHA = "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9";
    private static final String RERANK_TOKENIZER_SHA =
            "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66";

    @Test
    @EnabledIfSystemProperty(named = "patent.reranker.model", matches = ".+")
    void pinnedModelsReportRrfAndRerankQualityAgainstUnchangedRealQrels() throws Exception {
        Path root = workspace();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode benchmark = mapper.readTree(root.resolve(
                "data/patents/search-benchmark-v1/manifest.json").toFile());
        List<PatentLexicalQuery.Document> documents = documents(mapper, root);
        var embeddingModel = new PatentEmbeddingProvider.Model("ONNX_RUNTIME",
                "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2", EMBEDDING_REVISION,
                EMBEDDING_SHA, EMBEDDING_TOKENIZER_SHA, 384, 128, "MEAN_POOL_L2/v1", "Apache-2.0");
        var rerankModel = new PatentReranker.Model("ONNX_RUNTIME",
                "cross-encoder/ms-marco-MiniLM-L-6-v2", RERANK_REVISION,
                RERANK_SHA, RERANK_TOKENIZER_SHA, 512, "Apache-2.0");
        try (var embeddings = new OnnxPatentEmbeddingProvider(
                    Path.of(System.getProperty("patent.embedding.model")),
                    Path.of(System.getProperty("patent.embedding.tokenizer")), embeddingModel);
             var reranker = new OnnxPatentReranker(
                    Path.of(System.getProperty("patent.reranker.model")),
                    Path.of(System.getProperty("patent.reranker.tokenizer")), rerankModel)) {
            List<float[]> documentVectors = embeddings.embed(documents.stream()
                    .map(document -> document.fields().get("TITLE")).toList());
            compare(benchmark, documents, documentVectors, embeddings, reranker, mapper);
        }
    }

    private static void compare(JsonNode benchmark, List<PatentLexicalQuery.Document> documents,
            List<float[]> documentVectors, OnnxPatentEmbeddingProvider embeddings,
            OnnxPatentReranker reranker, ObjectMapper mapper) {
        var lexicalEngine = new PatentBm25fQuery();
        var hybridEngine = new PatentHybridQuery();
        var expansion = new PatentQueryExpansion();
        var metrics = new PatentSearchMetrics();
        double hybridRecall = 0, rerankRecall = 0, hybridNdcg = 0, rerankNdcg = 0;
        double recall10 = 0, recall100 = 0, precision10 = 0, precision20 = 0, ndcg20 = 0, mrr = 0;
        int judged = 0, negative = 0, negativeEmpty = 0;
        for (JsonNode query : benchmark.path("queries")) {
            Map<String, Integer> qrels = mapper.convertValue(query.path("qrels"),
                    new TypeReference<Map<String, Integer>>() { });
            String retrievalQuery = expansion.expand(query.path("query").asText()).retrievalQuery();
            var lexical = lexicalEngine.search(documents, retrievalQuery, Set.of("TITLE"), 1000);
            List<PatentHybridQuery.SemanticMatch> semantic = semantic(documents, documentVectors,
                    embeddings.embed(List.of(retrievalQuery)).getFirst());
            var hybrid = hybridEngine.fuse(lexical, semantic, 200);
            Map<String, String> texts = new HashMap<>();
            for (int index = 0; index < documents.size(); index++)
                texts.put(PatentRerankedQuery.key(hybrid.matches().get(index)),
                        title(hybrid.matches().get(index), documents));
            var reranked = new PatentRerankedQuery().rerank(retrievalQuery, hybrid, texts,
                    reranker, embeddings.model(), 100);
            List<String> hybridIds = hybrid.matches().stream().map(PatentHybridQuery.Match::documentId).toList();
            List<String> rerankedIds = reranked.matches().stream()
                    .map(match -> match.candidate().documentId()).toList();
            if (qrels.isEmpty()) {
                negative++;
                if (rerankedIds.isEmpty()) negativeEmpty++;
                assertThat(rerankedIds).as("Original negative %s", query.path("id").asText()).isEmpty();
                System.out.printf("PATENT_RERANK_QUERY id=%s judgment=NEGATIVE candidates=%d members=%d%n",
                        query.path("id").asText(), reranked.candidateCount(), rerankedIds.size());
                continue;
            }
            assertThat(rerankedIds).containsExactlyInAnyOrderElementsOf(hybridIds);
            hybridRecall += metrics.evaluate(hybridIds, qrels, 50).recall();
            rerankRecall += metrics.evaluate(rerankedIds, qrels, 50).recall();
            hybridNdcg += metrics.evaluate(hybridIds, qrels, 10).ndcg();
            rerankNdcg += metrics.evaluate(rerankedIds, qrels, 10).ndcg();
            var top10 = metrics.evaluate(rerankedIds, qrels, 10);
            var top20 = metrics.evaluate(rerankedIds, qrels, 20);
            recall10 += top10.recall();
            recall100 += metrics.evaluate(rerankedIds, qrels, 100).recall();
            precision10 += top10.precision();
            precision20 += top20.precision();
            ndcg20 += top20.ndcg();
            mrr += top10.reciprocalRank();
            System.out.printf("PATENT_RERANK_QUERY id=%s judgment=POSITIVE recall@10=%.6f "
                    + "recall@50=%.6f recall@100=%.6f precision@10=%.6f precision@20=%.6f "
                    + "ndcg@10=%.6f ndcg@20=%.6f mrr@10=%.6f%n", query.path("id").asText(),
                    top10.recall(), metrics.evaluate(rerankedIds, qrels, 50).recall(),
                    metrics.evaluate(rerankedIds, qrels, 100).recall(), top10.precision(), top20.precision(),
                    top10.ndcg(), top20.ndcg(), top10.reciprocalRank());
            judged++;
        }
        double threshold = benchmark.path("thresholds").path("ndcgAt10").asDouble();
        assertThat(judged).isEqualTo(9);
        assertThat(negative).isEqualTo(1);
        assertThat((double) negativeEmpty / negative)
                .isEqualTo(benchmark.path("thresholds").path("emptyQueryAccuracy").asDouble());
        assertThat(rerankRecall / judged)
                .isGreaterThanOrEqualTo(benchmark.path("thresholds").path("recallAt50").asDouble());
        assertThat(rerankNdcg / judged).isGreaterThanOrEqualTo(threshold);
        System.out.printf("PATENT_RERANK_BENCHMARK originalQueries=%d judged=%d negative=%d "
                        + "emptyAccuracy=%.6f hybridRecall@50=%.6f rerankRecall@50=%.6f "
                        + "hybridNdcg@10=%.6f rerankNdcg@10=%.6f%n",
                judged + negative, judged, negative, (double) negativeEmpty / negative,
                hybridRecall / judged, rerankRecall / judged, hybridNdcg / judged, rerankNdcg / judged);
        System.out.printf("PATENT_RERANK_EXTENDED_METRICS positiveQueries=%d negativeQueries=%d "
                + "recall@10=%.6f recall@100=%.6f precision@10=%.6f precision@20=%.6f "
                + "ndcg@20=%.6f mrr@10=%.6f judgment=ENGINEERING_RELEVANCE%n", judged, negative,
                recall10 / judged, recall100 / judged, precision10 / judged, precision20 / judged,
                ndcg20 / judged, mrr / judged);
    }

    private static List<PatentHybridQuery.SemanticMatch> semantic(
            List<PatentLexicalQuery.Document> documents, List<float[]> vectors, float[] query) {
        List<PatentHybridQuery.SemanticMatch> result = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            var document = documents.get(index);
            result.add(new PatentHybridQuery.SemanticMatch(document.sourceId(), document.documentId(),
                    document.documentVersion(), document.sourceVersion(), document.rawSha(),
                    document.locatorRef(), cosine(query, vectors.get(index))));
        }
        result.sort(Comparator.comparingDouble(PatentHybridQuery.SemanticMatch::semanticScore).reversed()
                .thenComparing(PatentHybridQuery.SemanticMatch::documentId));
        return List.copyOf(result);
    }

    private static String title(PatentHybridQuery.Match match,
            List<PatentLexicalQuery.Document> documents) {
        return documents.stream().filter(document -> document.documentId().equals(match.documentId())
                && document.documentVersion().equals(match.documentVersion())).findFirst()
                .orElseThrow().fields().get("TITLE");
    }

    private static double cosine(float[] left, float[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) result += left[index] * right[index];
        return result;
    }

    private static List<PatentLexicalQuery.Document> documents(ObjectMapper mapper, Path root)
            throws Exception {
        Path recordsPath = root.resolve("data/patents/public-pilot-v1/records.json");
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
}
