package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentBm25fQuery;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatentBm25fQueryTest {
    @Test void realCapturedDocumentsProduceScoredStableExplanations() throws Exception {
        var engine = new PatentBm25fQuery();
        var documents = captured();
        var first = engine.search(documents, "lithium battery", Set.of("TITLE"), 20);
        var again = engine.search(documents, "  LITHIUM battery  ", Set.of("TITLE"), 20);
        assertThat(first.methodVersion()).isEqualTo("BM25F/v1");
        assertThat(first.matches()).isEqualTo(again.matches());
        assertThat(first.matches()).isNotEmpty().allSatisfy(match -> {
            assertThat(match.lexicalScore()).isPositive();
            assertThat(match.matchFields()).containsExactly("TITLE");
            assertThat(match.matchedTerms()).isSubsetOf("lithium", "battery");
            assertThat(match.fieldScores()).containsOnlyKeys("TITLE");
            assertThat(match.locatorRef()).isNotBlank();
        });
        assertThat(first.matches()).isSortedAccordingTo((left, right) -> {
            int score = Double.compare(right.lexicalScore(), left.lexicalScore());
            return score != 0 ? score : left.documentId().compareTo(right.documentId());
        });
        assertThat(engine.search(documents, "quantum teleportation", Set.of("TITLE"), 20).matches())
                .isEmpty();
        assertThatThrownBy(() -> engine.search(documents, "battery", Set.of("FULL_TEXT"), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<PatentLexicalQuery.Document> captured() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String relative = "data/patents/public-pilot-v1/records.json";
        while (root != null && !root.resolve(relative).toFile().isFile()) root = root.getParent();
        if (root == null) throw new IllegalStateException("Real capture is unavailable");
        ObjectMapper mapper = new ObjectMapper();
        Path recordsPath = root.resolve(relative);
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
                    Map.of("TITLE", row.path("title").asText(),
                            "ABSTRACT", row.path("abstractExcerpt").asText(),
                            "CLAIMS", row.path("claimsExcerpt").asText())));
        }
        return result;
    }
}
