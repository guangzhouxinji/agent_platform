package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses actual collected originals. Deliberate mutations are rejection tests, not alternative source data. */
class ProductHupdSourceInventoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path capture = Path.of("data/patents/hupd-real-candidate-20261002-v2").toAbsolutePath();
    @TempDir Path temporary;
    private ProductHupdSourceInventory reader(Path root) {
        return new ProductHupdSourceInventory(new ProductSourceEvidenceFiles(root, 10_000_000), mapper);
    }
    private byte[] manifest() throws Exception { return Files.readAllBytes(capture.resolve("manifest.json")); }
    private byte[] changed(Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode manifest = (ObjectNode) mapper.readTree(manifest());
        mutation.accept(manifest);
        return mapper.writeValueAsBytes(manifest);
    }

    @Test void allActualMembersAndLocationsVerifyWithoutGrantingAProductUse() throws Exception {
        var actual = reader(capture).inspect("manifest.json", manifest());
        var metadata = mapper.readTree(manifest());
        assertThat(actual.sourceId()).isEqualTo("HUPD_PUBLIC_ARCHIVE");
        assertThat(actual.records()).isEqualTo(20);
        assertThat(actual.originals()).isEqualTo(27);
        assertThat(actual.references()).hasSize(28).contains("manifest.json", "raw/archive-prefix.tar.gz", "raw/member-0020.json");
        assertThat(actual.recordsSha()).isEqualTo(ProductCapturedArchive.digest(manifest()));
        assertThat(actual.sourceVersion()).isEqualTo(metadata.path("sourceVersion").asText() + ":" + metadata.path("collectedAt").asText());
        assertThat(metadata.path("productEnabled").booleanValue()).isFalse();
    }

    @Test void digestLengthMemberOffsetsAndIdentityTamperingReject() throws Exception {
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                m -> ((ObjectNode) m.path("records").get(0)).put("sha256", "0".repeat(64)),
                m -> ((ObjectNode) m.path("records").get(0)).put("bytes", 1),
                m -> ((ObjectNode) m.path("records").get(0)).put("tarContentOffset", 0),
                m -> ((ObjectNode) m.path("records").get(0)).put("archiveMember", "../other.json"),
                m -> ((ObjectNode) m.path("records").get(0).path("identifiers")).put("publication_number", "different"))) {
            byte[] bytes = changed(mutation);
            assertThatThrownBy(() -> reader(capture).inspect("manifest.json", bytes)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void fieldTokenPointersAndUtf8RangesAreNotTrustedFromManifest() throws Exception {
        for (Consumer<ObjectNode> mutation : java.util.List.<Consumer<ObjectNode>>of(
                m -> ((ObjectNode) m.path("records").get(0).path("locators").get(0)).put("valueUtf8Start", 0),
                m -> ((ObjectNode) m.path("records").get(0).path("locators").get(0)).put("jsonPointer", "/another"),
                m -> ((ObjectNode) m.path("records").get(0).path("locators").get(0)).put("keyUtf8EndExclusive", "42"))) {
            byte[] bytes = changed(mutation);
            assertThatThrownBy(() -> reader(capture).inspect("manifest.json", bytes)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void productEnabledAndFullArchiveValidationCannotBeSmuggledByCaptureMetadata() throws Exception {
        for (String field : java.util.List.of("productEnabled", "fullArchiveSha256Verified")) {
            byte[] bytes = changed(m -> m.put(field, true));
            assertThatThrownBy(() -> reader(capture).inspect("manifest.json", bytes))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("boundaries");
        }
        byte[] duplicate = changed(m -> ((com.fasterxml.jackson.databind.node.ArrayNode) m.path("records")).add(m.path("records").get(0)));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", duplicate)).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingOriginalAndWrongStableArchiveOriginReject() throws Exception {
        assertThatThrownBy(() -> reader(temporary).inspect("manifest.json", manifest())).isInstanceOf(IllegalStateException.class);
        byte[] origin = changed(m -> m.put("originalUrl", "https://another-origin.example/archive"));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", origin)).isInstanceOf(IllegalStateException.class);
        byte[] range = changed(m -> ((ObjectNode) m.path("responses").get(6)).put("contentRange", "bytes 0-3/4"));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", range)).isInstanceOf(IllegalStateException.class);
    }

    @Test void metadataLicenseAndPublisherVersionCannotBeRewrittenByTheManifest() throws Exception {
        byte[] license = changed(m -> ((ObjectNode) m.path("licenseEvidence")).put("hostedDataStatement", "public-domain"));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", license))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("publisher");
        byte[] missingPublisher = changed(m -> ((com.fasterxml.jackson.databind.node.ArrayNode) m.path("responses")).remove(0));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", missingPublisher))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("publisher");
        byte[] fakeQueries = changed(m -> ((ObjectNode) m.path("sampling")).put("queryResponse", true));
        assertThatThrownBy(() -> reader(capture).inspect("manifest.json", fakeQueries))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("boundaries");
    }

    @Test void equalTextAtANestedLocationDoesNotProveTheTopLevelFieldPointer() throws Exception {
        // Constructed JSON only for locator mechanics; never a product/source/benchmark record.
        String json = "{\"title\":\"a\",\"nested\":{\"title\":\"a\"}}";
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectNode item = mapper.createObjectNode();
        String sha = ProductCapturedArchive.digest(bytes);
        item.put("sha256", sha);
        item.putArray("fields").add("title").add("nested");
        var locators = item.putArray("locators");
        int key = json.lastIndexOf("\"title\"");
        locators.addObject().put("field", "title").put("jsonPointer", "/title").put("rawSha256", sha)
                .put("encoding", "UTF-8").put("keyUtf8Start", key).put("keyUtf8EndExclusive", key + 7)
                .put("valueUtf8Start", key + 8).put("valueUtf8EndExclusive", key + 11);
        locators.addObject().put("field", "nested");
        assertThatThrownBy(() -> ProductCapturedJsonFields.verify(bytes, mapper.readTree(bytes), item, mapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("top-level");
    }
}
