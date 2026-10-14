package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual original content C only: no Source activation, permission bypass, search or benchmark. */
class ProductHupdSourceDocumentsTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Path root = Path.of("data/patents").toAbsolutePath();
    private final String reference = "hupd-real-candidate-20261002-v2/manifest.json";
    private ProductSourceEvidenceFiles files() { return new ProductSourceEvidenceFiles(root, 10_000_000); }
    private ProductCollectedPatentTitles.CaptureSnapshot snapshot() throws Exception {
        byte[] bytes = Files.readAllBytes(root.resolve(reference));
        var manifest = mapper.readTree(bytes);
        String sha = ProductCapturedArchive.digest(bytes);
        return new ProductCollectedPatentTitles.CaptureSnapshot(manifest.path("sourceId").asText(),
                manifest.path("sourceVersion").asText() + ":" + manifest.path("collectedAt").asText(), reference, sha, sha);
    }
    private ProductPatentSearchExecution.Member member(PatentLexicalQuery.Document doc, String version, String sha, String locator) {
        return new ProductPatentSearchExecution.Member("component-member", 1, doc.sourceId(), doc.documentId(),
                version, doc.sourceVersion(), sha, locator, Set.of("TITLE"), List.of(),
                null, Map.of(), null, null, null, null, null, null);
    }
    private ProductSourceAvailability.Active captureBinding(ProductCollectedPatentTitles.CaptureSnapshot snapshot) {
        // This is a component input DTO, not a persisted ACTIVE source or a real grant.
        return new ProductSourceAvailability.Active(snapshot.sourceId(), "component-contract", snapshot.sourceVersion(),
                "component-decision", 1, 1, snapshot.manifestRef(), snapshot.manifestSha(), snapshot.recordsSha());
    }

    @Test void finalLoaderProjectsEachExactFieldWithoutEnablingTheCandidate() throws Exception {
        var binding = captureBinding(snapshot()); var loader = new ProductCollectedPatentTitles(files(),mapper);
        for (String field : List.of("TITLE","ABSTRACT","CLAIMS","DESCRIPTION"))
            assertThat(loader.loadField(binding,field)).hasSize(20)
                    .allSatisfy(doc -> assertThat(doc.fields()).containsOnlyKeys(field));
        assertThatThrownBy(() -> loader.loadField(binding,"LEGAL_STATUS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loader.loadField(binding,null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void titleOnlySourceFormatCannotFillAnUnavailableFullTextField() throws Exception {
        String ref = "public-pilot-v1/manifest.json"; byte[] manifest = Files.readAllBytes(root.resolve(ref));
        var tree = mapper.readTree(manifest);
        var snapshot = new ProductSourceAvailability.Active("component-source","component-contract",
                tree.path("datasetId").asText() + ":" + tree.path("generatedAt").asText(),"component-decision",1,1,
                ref,ProductCapturedArchive.digest(manifest),tree.path("recordsSha256").asText());
        assertThatThrownBy(() -> new ProductCollectedPatentTitles(files(),mapper).loadField(snapshot,"ABSTRACT"))
                .isInstanceOf(ProductCollectedPatentTitles.Unavailable.class)
                .hasRootCauseMessage("Source format does not supply the selected field");
    }

    @Test void actualTitlesUseTheirNativeOriginalIdentityAndExactUtf8ValuesOnly() throws Exception {
        var snapshot = snapshot();
        var documents = new ProductCollectedPatentTitles(files(), mapper).load(snapshot, ProductPatentSearchFilters.none());
        assertThat(documents).hasSize(20).allSatisfy(doc -> {
            assertThat(doc.fields()).containsOnlyKeys("TITLE");
            assertThat(doc.documentId()).matches("US[0-9]{11}[A-Z][0-9]");
            assertThat(doc.documentVersion()).isEqualTo("RAW_SHA256:" + doc.rawSha());
            assertThat(doc.sourceVersion()).isEqualTo(snapshot.sourceVersion());
        });
        for (var doc : documents) {
            String rawRef = doc.locatorRef().substring(0, doc.locatorRef().indexOf('#'));
            byte[] original = files().verified(rawRef, doc.rawSha());
            String[] offsets = doc.locatorRef().split("utf8=")[1].split(":");
            int start = Integer.parseInt(offsets[0]), end = Integer.parseInt(offsets[1]);
            assertThat(mapper.readTree(original, start, end - start).asText()).isEqualTo(doc.fields().get("TITLE"));
            assertThat(mapper.readTree(original).path("publication_number").asText())
                    .startsWith(doc.documentId() + "-");
        }
    }

    @Test void nativeDatesAndClassificationsAreReadWithoutGuessingLegalStatus() throws Exception {
        var snapshot = snapshot();
        var titles = new ProductCollectedPatentTitles(files(), mapper);
        assertThat(titles.load(snapshot, ProductPatentSearchFilters.validated(LocalDate.of(2030, 1, 1), null, List.of(), List.of())))
                .isEmpty();
        var filtered = titles.load(snapshot, ProductPatentSearchFilters.validated(null, null, List.of("A61B"), List.of()));
        assertThat(filtered).isNotEmpty();
        for (var doc : filtered) {
            String rawRef = doc.locatorRef().substring(0, doc.locatorRef().indexOf('#'));
            var record = mapper.readTree(files().verified(rawRef, doc.rawSha()));
            assertThat(record.path("ipcr_labels")).anySatisfy(label -> assertThat(label.asText()).startsWith("A61B"));
            assertThat(doc.fields()).doesNotContainKeys("LEGAL_STATUS", "decision", "CLAIMS", "DESCRIPTION", "examiner_id");
        }
    }

    @Test void nativeCitationsUseTheActualArchiveRequestNotAnInventedPatentPage() throws Exception {
        var snapshot = snapshot();
        var doc = new ProductCollectedPatentTitles(files(), mapper).load(snapshot, ProductPatentSearchFilters.none()).getFirst();
        var binding = captureBinding(snapshot);
        var citations = new ProductPatentSearchCitations(files(), mapper);
        var citation = citations.read(List.of(member(doc, doc.documentVersion(), doc.rawSha(), doc.locatorRef())),
                Map.of(snapshot.sourceId(), binding)).getFirst();
        var manifest = mapper.readTree(Files.readAllBytes(root.resolve(reference)));
        assertThat(citation.sourceUrl()).isEqualTo(manifest.path("originalUrl").asText());
        assertThat(citation.originalRequestUrl()).isEqualTo(citation.sourceUrl());
        assertThat(citation.locatorRef()).isEqualTo(doc.locatorRef());
        assertThat(citation.collectedAt().toString()).isEqualTo(manifest.path("responses").get(6).path("collectedAt").asText());
        assertThat(mapper.writeValueAsString(citation)).doesNotContain("abstract", "claims", "full_description", "examiner_name");
        for (var wrong : List.of(member(doc, "older-version", doc.rawSha(), doc.locatorRef()),
                member(doc, doc.documentVersion(), "0".repeat(64), doc.locatorRef()),
                member(doc, doc.documentVersion(), doc.rawSha(), doc.locatorRef() + "1")))
            assertThatThrownBy(() -> citations.read(List.of(wrong), Map.of(snapshot.sourceId(), binding)))
                    .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> citations.read(List.of(member(doc, doc.documentVersion(), doc.rawSha(), doc.locatorRef())),
                Map.<String,ProductSourceAvailability.Active>of())).isInstanceOf(IllegalStateException.class);
    }

    @Test void wrongSourceCaptureVersionAndRecordSetDigestFailClosed() throws Exception {
        var actual = snapshot();
        for (var wrong : List.of(
                new ProductCollectedPatentTitles.CaptureSnapshot("another-source", actual.sourceVersion(), reference, actual.manifestSha(), actual.recordsSha()),
                new ProductCollectedPatentTitles.CaptureSnapshot(actual.sourceId(), "older-version", reference, actual.manifestSha(), actual.recordsSha()),
                new ProductCollectedPatentTitles.CaptureSnapshot(actual.sourceId(), actual.sourceVersion(), reference, actual.manifestSha(), "0".repeat(64))))
            assertThatThrownBy(() -> new ProductCollectedPatentTitles(files(), mapper).load(wrong, ProductPatentSearchFilters.none()))
                    .isInstanceOf(ProductCollectedPatentTitles.Unavailable.class);
    }
}
