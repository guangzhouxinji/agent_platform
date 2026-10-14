package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Immutable real-corpus syntax qrels; deliberately not professional relevance acceptance. */
class PatentQuerySyntaxBenchmarkTest {
    @Test void realVersionedCorpusMeetsPreRegisteredSyntaxMetricsWithoutChangingLabels() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String relative = "data/patents/search-query-syntax-benchmark-v1/manifest.json";
        while(root != null && !Files.isRegularFile(root.resolve(relative))) root = root.getParent();
        if(root == null) throw new IllegalStateException("Real syntax benchmark unavailable");
        var mapper = new ObjectMapper();
        var benchmark = mapper.readTree(root.resolve(relative).toFile());
        Path recordsPath = root.resolve(benchmark.path("sourceRecords").asText());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(recordsPath)))).isEqualTo(benchmark.path("sourceRecordsSha256").asText());
        var manifest = mapper.readTree(recordsPath.resolveSibling("manifest.json").toFile());
        Map<String,String> hashes = new HashMap<>();
        for(var raw : manifest.path("rawResponses")) hashes.put(raw.path("file").asText(),raw.path("sha256").asText());
        List<PatentLexicalQuery.Document> documents = new ArrayList<>();
        for(var row : mapper.readTree(recordsPath.toFile()).path("records")) {
            String file = row.path("detailRawFile").asText();
            documents.add(new PatentLexicalQuery.Document("SURECHEMBL_PUBLIC_SEARCH",
                    row.path("sourceRecordId").asText(),row.path("sourceVersion").asText(),
                    manifest.path("datasetId").asText()+":"+manifest.path("generatedAt").asText(),
                    hashes.get(file),file+"#title",Map.of("TITLE",row.path("title").asText())));
        }
        var retrieval = new PatentQueryRetrieval();
        var metrics = new PatentSearchMetrics();
        int positives=0, negatives=0, empty=0;
        double recall=0, precision=0, ndcg=0;
        for(var query : benchmark.path("queries")) {
            var actual = retrieval.prepare(query.path("query").asText(),documents,1000);
            Map<String,Integer> qrels = mapper.convertValue(query.path("qrels"),new TypeReference<>(){});
            List<String> ids = actual.lexical().matches().stream().map(PatentBm25fQuery.Match::documentId).toList();
            assertThat(ids).containsExactlyInAnyOrderElementsOf(qrels.keySet()).doesNotHaveDuplicates();
            for(int repeat=0;repeat<100;repeat++) assertThat(retrieval.prepare(
                    query.path("query").asText(),documents,1000)).isEqualTo(actual);
            if(qrels.isEmpty()) { negatives++; if(ids.isEmpty()) empty++; }
            else {
                var result = metrics.evaluate(ids,qrels,benchmark.path("cutoff").asInt());
                recall+=result.recall();precision+=result.precision();ndcg+=result.ndcg();positives++;
            }
        }
        assertThat(recall/positives).isGreaterThanOrEqualTo(benchmark.path("thresholds").path("recallAt10").asDouble());
        assertThat(ndcg/positives).isGreaterThanOrEqualTo(benchmark.path("thresholds").path("ndcgAt10").asDouble());
        assertThat((double)empty/negatives).isEqualTo(1.0);
        System.out.printf(Locale.ROOT,"PATENT_QUERY_SYNTAX_BENCHMARK queries=%d recall@10=%.6f precision@10=%.6f ndcg@10=%.6f emptyAccuracy=%.6f replays=100%n",
                positives+negatives,recall/positives,precision/positives,ndcg/positives,(double)empty/negatives);
    }
}
