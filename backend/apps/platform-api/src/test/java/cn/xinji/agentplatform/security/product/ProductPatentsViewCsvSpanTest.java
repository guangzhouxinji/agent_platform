package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Grammar differential C and actual original-row C, never Source admission or professional labels. */
class ProductPatentsViewCsvSpanTest {
    private static final List<String> TWO = List.of("first", "second");
    private static byte[] encoded(List<String> values, String end) {
        return (values.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining("\t")) + end).getBytes(StandardCharsets.UTF_8);
    }
    @Test void longQuotedUnicodeTabsNewlinesAndEscapedQuotesKeepExactValues() {
        String large = "第一行\t\"quoted\"\r\nsecond😀".repeat(4096);
        for (String end : List.of("\n", "\r", "\r\n")) {
            byte[] bytes = encoded(List.of(large, ""), end);
            var parsed = ProductPatentsViewCsv.row(bytes, TWO);
            assertThat(parsed).containsEntry("first", large).containsEntry("second", "");
            assertThat(parsed).isEqualTo(characterReference(bytes, TWO));
        }
    }
    @Test void deterministicGrammarCasesDifferentiallyMatchPreviousCharacterAlgorithm() {
        var random = new Random(578);
        var atoms = List.of("a", "\"", " ", "\t", "\r", "\n", "汉字", "λ", "😀");
        for (int trial = 0; trial < 1000; trial++) {
            var values = new ArrayList<String>();
            for (int field = 0; field < TWO.size(); field++) {
                var value = new StringBuilder();
                int length = random.nextInt(120);
                for (int i = 0; i < length; i++) value.append(atoms.get(random.nextInt(atoms.size())));
                values.add(value.toString());
            }
            byte[] bytes = encoded(values, trial % 2 == 0 ? "\n" : "\r\n");
            assertThat(ProductPatentsViewCsv.row(bytes, TWO)).isEqualTo(characterReference(bytes, TWO))
                    .containsEntry("first", values.get(0)).containsEntry("second", values.get(1));
        }
    }
    @Test void incompleteQuotesPostQuoteGarbageExtraRowsBadColumnsAndInvalidUtf8RemainRejected() {
        for (String invalid : List.of("x\ty", "\"x\ty\n", "\"x\"tail\ty\n", "x\"bad\ty\n",
                "x\ty\nextra\n", "x\ty\tz\n", "\"x\"\ty\r\nextra", "\"x\"\"\ty\n")) {
            byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ProductPatentsViewCsv.row(bytes, TWO)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> characterReference(bytes, TWO)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> ProductPatentsViewCsv.row(new byte[]{(byte) 255}, TWO))
                .hasMessage("Original TSV encoding invalid");
        assertThatThrownBy(() -> ProductPatentsViewCsv.row(encoded(List.of("x", "y"), "\n"), List.of("same", "same")))
                .hasMessage("Incomplete TSV row/columns");
    }
    @Test void allFortyCollectedPatentsKeepExactOriginalMetadataAndClaimsFieldsAndHashes() throws Exception {
        Path root = Path.of("data/patents/patentsview-real-candidate-20261002-v3").toAbsolutePath();
        byte[] manifest = Files.readAllBytes(root.resolve("manifest.json"));
        assertThat(ProductCapturedArchive.digest(manifest)).isEqualTo("54a18995b6272b4dd8d8a473f1ee6f059125bc29a580840459ff8d8e5971a639");
        var mapper = new ObjectMapper();
        JsonNode records = mapper.readTree(manifest).path("records");
        assertThat(records.size()).isEqualTo(40);
        int claims = 0;
        for (JsonNode item : records) {
            byte[] envelope = Files.readAllBytes(root.resolve(item.path("file").asText()));
            assertThat(ProductCapturedArchive.digest(envelope)).isEqualTo(item.path("sha256").asText());
            var record = mapper.readTree(envelope);
            original(root, record.path("metadata"), ProductPatentsViewCsv.METADATA);
            for (JsonNode claim : record.path("claims")) { original(root, claim, ProductPatentsViewCsv.CLAIMS); claims++; }
        }
        assertThat(claims).isPositive();
    }
    private static void original(Path root, JsonNode entry, List<String> columns) throws Exception {
        JsonNode locator = entry.path("sourceLocator");
        byte[] raw = Files.readAllBytes(root.resolve(locator.path("file").asText()));
        assertThat(ProductCapturedArchive.digest(raw)).isEqualTo(locator.path("rowSha256").asText());
        var parsed = ProductPatentsViewCsv.row(raw, columns);
        assertThat(parsed).isEqualTo(characterReference(raw, columns));
        assertThat(entry.path("originalFields").size()).isEqualTo(parsed.size());
        parsed.forEach((name, value) -> assertThat(entry.path("originalFields").path(name).asText()).isEqualTo(value));
    }
    /** Previous algorithm retained only as a grammar oracle, not as a second product path. */
    private static Map<String,String> characterReference(byte[] bytes, List<String> columns) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            List<String> values = new ArrayList<>(); StringBuilder value = new StringBuilder();
            boolean quoted = false, closed = false, ended = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                        else { quoted = false; closed = true; }
                    } else value.append(c);
                } else if (c == '\t') { values.add(value.toString()); value.setLength(0); closed = false; }
                else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    if (i != text.length() - 1) throw new IllegalStateException("More than one CSV row");
                    values.add(value.toString()); ended = true;
                } else if (c == '"' && value.isEmpty() && !closed) quoted = true;
                else { if (closed || c == '"') throw new IllegalStateException("Invalid TSV quote"); value.append(c); }
            }
            if (quoted || !ended || values.size() != columns.size() || new HashSet<>(columns).size() != columns.size())
                throw new IllegalStateException("Incomplete TSV row/columns");
            Map<String,String> result = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) result.put(columns.get(i), values.get(i));
            return Map.copyOf(result);
        } catch (CharacterCodingException invalid) { throw new IllegalStateException("Original TSV encoding invalid", invalid); }
    }
}
