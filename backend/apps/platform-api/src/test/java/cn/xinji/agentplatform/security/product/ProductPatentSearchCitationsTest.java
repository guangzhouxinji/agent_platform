package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Unmodified real capture bytes; controlled authority is component evidence only. */
class ProductPatentSearchCitationsTest {
    @Test void citationsBindActualUrlsVersionDigestAndLocatorWithoutExposingOtherFields() throws Exception {
        var mapper = new ObjectMapper();
        Path root = Path.of("data/patents").toAbsolutePath();
        Path manifestPath = root.resolve("public-pilot-v1/manifest.json");
        var manifest = mapper.readTree(manifestPath.toFile());
        var active = new ProductSourceAvailability.Active("source","contract",
                manifest.path("datasetId").asText()+":"+manifest.path("generatedAt").asText(),
                "decision",1,2,"public-pilot-v1/manifest.json",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifestPath))),
                manifest.path("recordsSha256").asText());
        var files = new ProductSourceEvidenceFiles(root,10_000_000);
        var doc = new ProductCollectedPatentTitles(files,mapper).load(active).getFirst();
        var availability = mock(ProductSourceAvailability.class);
        when(availability.require("source","TITLE","QUERY")).thenReturn(active);
        var citations = new ProductPatentSearchCitations(files,mapper);
        var member = member(doc,doc.documentVersion(),doc.rawSha(),doc.locatorRef());
        var citation = citations.read(List.of(member),availability).getFirst();
        assertThat(citations.read(List.of(member),Map.of("source",active)))
                .containsExactly(citation);
        assertThatThrownBy(() -> citations.read(List.of(member),Map.<String,ProductSourceAvailability.Active>of()))
                .isInstanceOf(IllegalStateException.class);
        var wrongSource = new ProductSourceAvailability.Active("other",active.contractVersion(),
                active.sourceVersion(),active.decisionId(),active.epoch(),active.rowVersion(),
                active.manifestRef(),active.manifestSha(),active.recordsSha());
        assertThatThrownBy(() -> citations.read(List.of(member),Map.of("source",wrongSource)))
                .isInstanceOf(IllegalStateException.class);
        var documents = new ArrayList<>(new ProductCollectedPatentTitles(files,mapper).load(active));
        Collections.reverse(documents);
        var orderedMembers = documents.stream().limit(20).map(value -> member(value,
                value.documentVersion(),value.rawSha(),value.locatorRef())).toList();
        assertThat(citations.read(orderedMembers,Map.of("source",active)))
                .isEqualTo(citations.read(orderedMembers,availability));
        assertThat(citations.read(orderedMembers,availability).stream().map(value -> value.documentId()).toList())
                .containsExactlyElementsOf(orderedMembers.stream().map(value -> value.documentId()).toList());
        var record = mapper.readTree(root.resolve("public-pilot-v1/records.json").toFile())
                .path("records").get(0);
        assertThat(citation.sourceUrl()).isEqualTo(record.path("detailUrl").asText());
        var raw = java.util.stream.StreamSupport.stream(manifest.path("rawResponses").spliterator(),false)
                .filter(item -> item.path("file").asText().equals(record.path("detailRawFile").asText()))
                .findFirst().orElseThrow();
        assertThat(citation.originalRequestUrl()).isEqualTo(raw.path("requestUrl").asText());
        assertThat(citation.documentVersion()).isEqualTo(doc.documentVersion());
        assertThat(citation.rawSha()).isEqualTo(doc.rawSha());
        assertThat(citation.locatorRef()).isEqualTo(doc.locatorRef());
        assertThat(citation.collectedAt()).isEqualTo(java.time.Instant.parse(manifest.path("generatedAt").asText()));
        assertThat(mapper.findAndRegisterModules().writeValueAsString(citation))
                .doesNotContain("claimsExcerpt","abstractExcerpt","password","access_token");
        for (var invalid : List.of(member(doc,"changed",doc.rawSha(),doc.locatorRef()),
                member(doc,doc.documentVersion(),"b".repeat(64),doc.locatorRef()),
                member(doc,doc.documentVersion(),doc.rawSha(),"wrong#title"))) {
            assertThatThrownBy(() -> citations.read(List.of(invalid),availability))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> citations.read(List.of(invalid),Map.of("source",active)))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(availability.require("source","TITLE","QUERY"))
                .thenThrow(new IllegalStateException("Source withdrawn"));
        assertThatThrownBy(() -> citations.read(List.of(member),availability)).hasMessage("Source withdrawn");
    }

    @Test void citationUrlsRejectExecutableRelativeAndCredentialBearingLinks() {
        for (String invalid : List.of("javascript:alert(1)","file:///C:/secret","/patent/123",
                "https://user:password@example.org/patent","https://example.org/a b"))
            assertThatThrownBy(() -> ProductPatentSearchCitations.publicUrl(invalid))
                    .isInstanceOf(IllegalStateException.class);
        assertThat(ProductPatentSearchCitations.publicUrl("https://example.org/patent/CN104900909A"))
                .isEqualTo("https://example.org/patent/CN104900909A");
    }

    private static ProductPatentSearchExecution.Member member(PatentLexicalQuery.Document doc,
            String version, String sha, String locator) {
        return new ProductPatentSearchExecution.Member("member",0,doc.sourceId(),doc.documentId(),
                version,doc.sourceVersion(),sha,locator,Set.of("TITLE"),List.of("battery"),
                1.0,Map.of("TITLE",1.0),1,null,null,null,null,null);
    }
}
