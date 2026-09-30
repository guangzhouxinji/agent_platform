package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductLexicalAndTest {
    @Test void chineseCapturedRecordRetainsUtf8Locations() throws Exception {
        Path root = Path.of("data/patents/cn-topic-public-pilot-v1");
        byte[] bytes = Files.readAllBytes(root.resolve("records.json"));
        ObjectMapper json = new ObjectMapper();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(json.readTree(Files.readAllBytes(root.resolve("manifest.json")))
                        .path("recordsSha256").asText());
        var records = json.readTree(bytes).path("records");
        assertThat(records.size()).isEqualTo(400);
        String title = records.get(0).path("title").asText();
        var result = ProductLexicalAnd.match(ProductLexicalAnd.parse("人工智能 机器学习"),
                Map.of("TITLE", title), Set.of("TITLE")).orElseThrow();
        assertThat(result.matchedFieldCount()).isEqualTo(1);
        assertThat(result.locations()).hasSize(2);
        for (var location : result.locations())
            assertThat(new String(title.getBytes(StandardCharsets.UTF_8), location.byteStart(),
                    location.byteEnd() - location.byteStart(), StandardCharsets.UTF_8))
                    .isEqualTo(location.term());
    }

    @Test void realCapturedRecordsMatchOnlyPermittedFieldsWithOriginalByteOffsets() throws Exception {
        Path root = Path.of("data/patents/public-pilot-v1");
        byte[] bytes = Files.readAllBytes(root.resolve("records.json"));
        ObjectMapper json = new ObjectMapper();
        var manifest = json.readTree(Files.readAllBytes(root.resolve("manifest.json")));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(manifest.path("recordsSha256").asText());
        var records = json.readTree(bytes).path("records");
        assertThat(records.size()).isEqualTo(44);
        var query = ProductLexicalAnd.parse("coating drying");
        int hits = 0;
        for (var record : records) {
            String claims = record.path("claimsExcerpt").asText("");
            var match = ProductLexicalAnd.match(query,
                    Map.of("TITLE", record.path("title").asText(""),
                            "ABSTRACT", record.path("abstractExcerpt").asText(""),
                            "CLAIMS", claims), Set.of("TITLE", "ABSTRACT", "CLAIMS"));
            if (match.isEmpty()) continue;
            hits++;
            for (var location : match.orElseThrow().locations()) {
                String value = switch (location.field()) {
                    case "TITLE" -> record.path("title").asText();
                    case "ABSTRACT" -> record.path("abstractExcerpt").asText();
                    case "CLAIMS" -> claims;
                    default -> throw new AssertionError("Unknown field");
                };
                String original = new String(value.getBytes(StandardCharsets.UTF_8),
                        location.byteStart(), location.byteEnd() - location.byteStart(), StandardCharsets.UTF_8);
                assertThat(original).isEqualToIgnoringCase(location.term());
            }
        }
        assertThat(hits).isPositive().isLessThan(records.size());
        assertThat(ProductLexicalAnd.match(query, Map.of("TITLE", "coating", "CLAIMS", "drying"),
                Set.of("TITLE"))).isEmpty();
        assertThatThrownBy(() -> ProductLexicalAnd.parse("!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
