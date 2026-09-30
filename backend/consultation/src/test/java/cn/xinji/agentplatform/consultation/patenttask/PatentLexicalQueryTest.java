package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class PatentLexicalQueryTest {
    private final PatentLexicalQuery query = new PatentLexicalQuery();

    @Test void capturedDocumentsDriveLiteralAndStableVersionedResults() throws Exception {
        List<PatentLexicalQuery.Document> documents = captured("data/patents/public-pilot-v1/records.json");
        Set<String> fields = Set.of("TITLE", "ABSTRACT", "CLAIMS");
        var first = query.search(documents, "binder coating", fields, 5);
        var again = query.search(documents, "BINDER   COATING", fields, 5);
        assertThat(first).isEqualTo(again);
        assertThat(first.matches()).hasSize(5);
        assertThat(first.truncated()).isTrue();
        assertThat(first.matches()).allSatisfy(match -> {
            assertThat(match.documentId()).isNotBlank();
            assertThat(match.documentVersion()).isNotBlank();
            assertThat(match.matchFields()).isSubsetOf(fields);
            assertThat(match.rawSha()).matches("[0-9a-f]{64}");
            assertThat(match.locatorRef()).isNotBlank();
        });
        assertThat(query.search(documents, "binder coating", fields, 100).matches()).hasSize(20);
        assertThat(query.search(documents, "binder coating", Set.of("TITLE"), 100).matches().size())
                .isLessThan(20);
        assertThat(query.search(documents, "%_", fields, 100).matches()).isEmpty();
        assertThatThrownBy(() -> query.search(documents, "binder", Set.of(), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void capturedRowsWithoutManifestBoundOriginalsCannotBecomeCandidates() throws Exception {
        List<PatentLexicalQuery.Document> documents = captured(
                "data/patents/cn-topic-public-pilot-v1/records.json");
        assertThat(documents).anySatisfy(document -> assertThat(document.rawSha()).isNull());
        assertThatThrownBy(() -> query.search(documents, "人工智能",
                Set.of("TITLE", "ABSTRACT", "CLAIMS"), 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<PatentLexicalQuery.Document> captured(String path) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !root.resolve(path).toFile().isFile()) root = root.getParent();
        if (root == null) throw new IllegalStateException("Captured source records are unavailable");
        ObjectMapper mapper = new ObjectMapper();
        Path recordsPath = root.resolve(path);
        JsonNode rows = mapper.readTree(recordsPath.toFile()).path("records");
        JsonNode manifest = mapper.readTree(recordsPath.resolveSibling("manifest.json").toFile());
        String sourceVersion = manifest.path("datasetId").asText() + ":"
                + manifest.path("generatedAt").asText();
        Map<String, String> rawHashes = new HashMap<>();
        for (JsonNode raw : manifest.path("rawResponses"))
            rawHashes.put(raw.path("file").asText(), raw.path("sha256").asText());
        List<PatentLexicalQuery.Document> documents = new ArrayList<>();
        for (JsonNode row : rows) {
            String locator = row.path("detailRawFile").asText();
            documents.add(new PatentLexicalQuery.Document("SURECHEMBL_PUBLIC_SEARCH",
                    row.path("sourceRecordId").asText(), row.path("sourceVersion").asText(),
                    sourceVersion, rawHashes.get(locator), locator,
                    Map.of("TITLE", row.path("title").asText(),
                            "ABSTRACT", row.path("abstractExcerpt").asText(),
                            "CLAIMS", row.path("claimsExcerpt").asText())));
        }
        return documents;
    }
}
