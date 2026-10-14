package cn.xinji.agentplatform.consultation.patenttask;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatentQuerySyntaxTest {
    private final PatentQuerySyntax syntax = new PatentQuerySyntax();
    private PatentLexicalQuery.Document doc(String title) {
        return new PatentLexicalQuery.Document("source", "CN-104900909-A", "20150909", "v1",
                "a".repeat(64), "title", Map.of("TITLE", title));
    }
    @Test void booleanPrecedencePhraseFieldAndNaturalLanguageAreDistinct() {
        assertThat(syntax.parse("lithium battery", Set.of("TITLE")).explicit()).isFalse();
        assertThat(syntax.parse("!!! lithium battery ???", Set.of("TITLE")).explicit()).isFalse();
        var plan = syntax.parse("TITLE:(battery AND NOT solar) OR \"coating film\"", Set.of("TITLE"));
        assertThat(plan.matches(doc("battery binder"))).isTrue();
        assertThat(plan.matches(doc("solar battery"))).isFalse();
        assertThat(plan.matches(doc("solar coating film"))).isTrue();
        assertThat(plan.matches(doc("coating polymer film"))).isFalse();
        assertThat(syntax.parse("battery OR coating AND solar", Set.of("TITLE"))
                .matches(doc("coating battery"))).isTrue();
        assertThat(syntax.parse("TITLE:battery", Set.of("TITLE"))
                .matches(doc("batteryless equipment"))).isFalse();
        assertThat(syntax.parse("TITLE:电池", Set.of("TITLE"))
                .matches(doc("锂电池材料"))).isTrue();
        assertThat(syntax.parse("TITLE:café", Set.of("TITLE"))
                .matches(doc("cafe\u0301"))).isTrue();
    }
    @Test void incompleteUnsupportedUnboundedAndUnadmittedQueriesFailClosed() {
        for (String bad : List.of("battery AND", "OR battery", "(battery", "battery)", "\"battery",
                "\"\"", "TITLE:", "CLAIMS:battery", "IPC:H01M", "battery*", "battery~2",
                "battery^3", "NOT battery", "battery OR NOT coating", "DOCUMENT_ID:no-number",
                "DOCUMENT_ID:CN104900909A OR battery", "()"))
            assertThatThrownBy(() -> syntax.parse(bad, Set.of("TITLE"))).as(bad)
                    .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("TITLE:battery", Set.of("CLAIMS")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("x ".repeat(129), Set.of("TITLE")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("(".repeat(18)+"battery"+")".repeat(18), Set.of("TITLE")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("x".repeat(4001), Set.of("TITLE")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> syntax.parse("电".repeat(1334), Set.of("TITLE")))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
        assertThatThrownBy(() -> new PatentQueryExpansion().expand("İ".repeat(1800)))
                .isInstanceOf(PatentQuerySyntax.InvalidQuery.class);
    }
    @Test void expansionsExcludeNegativeTermsAndPublicationIdentityIsNotTitleSimilarity() {
        var expansion = new PatentQueryExpansion().expand("TITLE:binder AND NOT battery");
        assertThat(expansion.version()).endsWith("+PATENT_QUERY/v1");
        assertThat(expansion.retrievalQuery()).isEqualTo("binder");
        assertThat(expansion.expansions()).isEmpty();
        var exact = syntax.parse("DOCUMENT_ID:CN104900909A", Set.of("TITLE"));
        var result = new PatentExactPublicationQuery().search(List.of(doc("unrelated title")), exact, 10);
        assertThat(result.methodVersion()).isEqualTo("EXACT_PUBLICATION/v1");
        assertThat(result.matches()).singleElement().satisfies(match ->
                assertThat(match.matchFields()).containsExactly("DOCUMENT_ID"));
        assertThat(syntax.parse("CN-104900909-A", Set.of("TITLE")).matches(doc("unrelated"))).isTrue();
    }
    @Test void realCollectedTitlesObeyHardConstraintsAndPreserveVersionedIdentity() throws Exception {
        Path workspace = Path.of("").toAbsolutePath();
        String relative = "data/patents/public-pilot-v1/records.json";
        while (workspace != null && !workspace.resolve(relative).toFile().isFile()) workspace = workspace.getParent();
        if (workspace == null) throw new IllegalStateException("Real capture is unavailable");
        var mapper = new ObjectMapper();
        var root = mapper.readTree(workspace.resolve(relative).toFile());
        var manifest = mapper.readTree(workspace.resolve(relative).resolveSibling("manifest.json").toFile());
        Map<String,String> hashes = new HashMap<>();
        for (var raw : manifest.path("rawResponses")) hashes.put(raw.path("file").asText(), raw.path("sha256").asText());
        List<PatentLexicalQuery.Document> documents = new ArrayList<>();
        for (var record : root.path("records")) documents.add(new PatentLexicalQuery.Document(
                "SURECHEMBL_PUBLIC_SEARCH", record.path("publicationNumber").asText(),
                record.path("sourceVersion").asText(), root.path("datasetId").asText(),
                hashes.get(record.path("detailRawFile").asText()), record.path("detailRawFile").asText()+"#title",
                Map.of("TITLE", record.path("title").asText())));
        var query = syntax.parse("TITLE:\"lithium ion battery\" AND NOT coating", Set.of("TITLE"));
        var matches = documents.stream().filter(query::matches).toList();
        assertThat(matches).isNotEmpty().allSatisfy(d -> {
            assertThat(d.fields().get("TITLE").toLowerCase()).contains("lithium ion battery").doesNotContain("coating");
            assertThat(d.documentVersion()).isNotBlank();
        });
        assertThat(new PatentExactPublicationQuery().search(documents,
                syntax.parse("CN104900909A", Set.of("TITLE")), 10).matches()).hasSize(1);
        var prepared = new PatentQueryRetrieval().prepare("TITLE:\"lithium ion battery\" AND NOT coating", documents, 100);
        assertThat(prepared.documents()).containsExactlyElementsOf(matches);
        assertThat(prepared.lexical().matches()).hasSameSizeAs(matches);
        // Actual collected text is C input; this test does not establish source admission or physical I.
    }
}
