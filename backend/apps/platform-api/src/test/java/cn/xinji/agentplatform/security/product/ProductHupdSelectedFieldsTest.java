package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual original content projection C; no admission, retrieval, embedding or professional labels. */
class ProductHupdSelectedFieldsTest {
    @Test void historicalProjectionClosureUsesRealOriginalsWithoutGrantingCandidateAccess() throws Exception {
        var selected=Set.of("ABSTRACT","CLAIMS");
        var originals=new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root,10_000_000),mapper)
                .loadFieldEvidence(snapshot(),ProductPatentSearchFilters.none(),selected);
        var doc=originals.getFirst().document();
        var member=new ProductPatentSearchExecution.Member("original-C",0,doc.sourceId(),doc.documentId(),doc.documentVersion(),
                doc.sourceVersion(),doc.rawSha(),doc.locatorRef(),Set.of("DOCUMENT_ID"),List.of(doc.documentId()),1.0,
                Map.of("DOCUMENT_ID",1.0),1,null,null,null,null,null);
        var bundle=new ProductPatentFieldResultEvidence.Bundle(ProductPatentFieldResultEvidence.VERSION,List.copyOf(selected),Map.of(),
                "EXACT_PUBLICATION/v1","EXACT_PUBLICATION_DOCUMENT_KEY/v1",null,null,
                Map.of(member.memberId(),new ProductPatentFieldResultEvidence.Fields(originals.getFirst().fieldLocators(),Map.of())));
        String key=doc.sourceId()+'\0'+doc.documentId()+'\0'+doc.documentVersion();
        ProductPatentFieldRecoveryEvidence.verifyOriginals(bundle,List.of(member),Map.of(key,originals.getFirst()));
        var changed=new cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion.DocumentEvidence(doc,
                Map.of("ABSTRACT",originals.getFirst().fieldLocators().get("ABSTRACT"),"CLAIMS",doc.locatorRef()+"-wrong"));
        assertThatThrownBy(() -> ProductPatentFieldRecoveryEvidence.verifyOriginals(bundle,List.of(member),Map.of(key,changed)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void explicitFieldCitationsBindEveryOriginalLocatorAndRejectUnselectedEvidence() throws Exception {
        for (int mask = 1; mask < 16; mask++) {
            Set<String> selected = new LinkedHashSet<>();
            for (int i = 0; i < fields.size(); i++) if ((mask & (1 << i)) != 0) selected.add(fields.get(i));
            var capture = adapter().read(snapshot(),manifest(),selected);
            for (var document : capture.documents(ProductPatentSearchFilters.none())) {
                var member = member(document,selected);
                var citation = capture.citation(member,true);
                assertThat(citation.fieldLocators()).isEqualTo(capture.entries().get(document.documentId()).content().locators());
                assertThat(citation.fieldLocators()).containsOnlyKeys(selected);
                assertThatThrownBy(() -> citation.fieldLocators().clear()).isInstanceOf(UnsupportedOperationException.class);
                assertThat(capture.citation(member).fieldLocators()).isEmpty();
            }
            var document = capture.documents(ProductPatentSearchFilters.none()).getFirst();
            assertThatThrownBy(() -> capture.citation(member(document,Set.of("PERSONAL_DATA")),true))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void explicitCitationReadRequiresSameCurrentSnapshotForEverySelectedField() throws Exception {
        var selected = Set.of("ABSTRACT","CLAIMS"); var snapshot = snapshot();
        var doc = adapter().read(snapshot,manifest(),selected).documents(ProductPatentSearchFilters.none()).getFirst();
        var member = member(doc,selected);
        var active = new ProductSourceAvailability.Active(snapshot.sourceId(),"mechanical-citation-C",
                snapshot.sourceVersion(),"mechanical-C-only",1,2,snapshot.manifestRef(),snapshot.manifestSha(),snapshot.recordsSha());
        var availability = org.mockito.Mockito.mock(ProductSourceAvailability.class);
        for (String field : selected) org.mockito.Mockito.when(availability.require(snapshot.sourceId(),field,"QUERY"))
                .thenReturn(active);
        var service = new ProductPatentSearchCitations(new ProductSourceEvidenceFiles(root,10_000_000),mapper);
        var citations = service.readFields(List.of(member),availability,selected);
        assertThat(service.readFields(List.of(member),Map.of(snapshot.sourceId(),active),selected)).isEqualTo(citations);
        assertThatThrownBy(() -> service.readFields(List.of(member),Map.<String,ProductSourceAvailability.Active>of(),selected))
                .isInstanceOf(IllegalStateException.class);
        assertThat(citations.getFirst().fieldLocators()).containsOnlyKeys(selected);
        for (String field : selected) org.mockito.Mockito.verify(availability).require(snapshot.sourceId(),field,"QUERY");
        org.mockito.Mockito.when(availability.require(snapshot.sourceId(),"CLAIMS","QUERY"))
                .thenReturn(new ProductSourceAvailability.Active(active.sourceId(),active.contractVersion(),active.sourceVersion(),
                        active.decisionId(),2,2,active.manifestRef(),active.manifestSha(),active.recordsSha()));
        assertThatThrownBy(() -> service.readFields(List.of(member),availability,selected)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.when(availability.require(snapshot.sourceId(),"CLAIMS","QUERY"))
                .thenThrow(new IllegalStateException("withdrawn"));
        assertThatThrownBy(() -> service.readFields(List.of(member),availability,selected)).hasMessage("withdrawn");
        assertThatThrownBy(() -> service.readFields(List.of(member),availability,Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyCitationJsonDoesNotAcquireFieldMapAndCanStillBeRead() throws Exception {
        var capture = adapter().read(snapshot(),manifest());
        var doc = capture.documents(ProductPatentSearchFilters.none()).getFirst();
        var legacy = capture.citation(member(doc,Set.of("TITLE")));
        String json = mapper.writeValueAsString(legacy);
        assertThat(json).doesNotContain("fieldLocators");
        assertThat(mapper.readValue(json,ProductPatentSearchCitations.Citation.class)).isEqualTo(legacy);
        var current = capture.citation(member(doc,Set.of("TITLE")),true);
        assertThat(mapper.readValue(mapper.writeValueAsString(current),ProductPatentSearchCitations.Citation.class))
                .isEqualTo(current);
    }

    private ProductPatentSearchExecution.Member member(cn.xinji.agentplatform.consultation.patenttask.source.PatentLexicalQuery.Document doc,
            Set<String> selected) {
        return new ProductPatentSearchExecution.Member("member",0,doc.sourceId(),doc.documentId(),doc.documentVersion(),
                doc.sourceVersion(),doc.rawSha(),doc.locatorRef(),selected,List.of(),1.0,Map.of(),1,null,null,null,null,null);
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Path root = Path.of("data/patents").toAbsolutePath();
    private final String ref = "hupd-real-candidate-20261002-v2/manifest.json";
    private final List<String> fields = List.of("TITLE", "ABSTRACT", "CLAIMS", "DESCRIPTION");
    private final Map<String,String> keys = Map.of("TITLE", "title", "ABSTRACT", "abstract",
            "CLAIMS", "claims", "DESCRIPTION", "full_description");
    private byte[] manifest() throws Exception { return Files.readAllBytes(root.resolve(ref)); }
    private ProductHupdSourceDocuments adapter() { return new ProductHupdSourceDocuments(
            new ProductSourceEvidenceFiles(root, 10_000_000), mapper); }
    private ProductCollectedPatentTitles.CaptureSnapshot snapshot() throws Exception {
        var bytes = manifest(); var tree = mapper.readTree(bytes); String sha = ProductCapturedArchive.digest(bytes);
        return new ProductCollectedPatentTitles.CaptureSnapshot(tree.path("sourceId").asText(),
                tree.path("sourceVersion").asText() + ":" + tree.path("collectedAt").asText(), ref, sha, sha);
    }

    @Test void everySelectedCombinationContainsOnlyExactOriginalValuesAndIndividualByteLocations() throws Exception {
        for (int bits = 1; bits < 16; bits++) {
            Set<String> selected = new HashSet<>();
            for (int i = 0; i < 4; i++) if ((bits & (1 << i)) != 0) selected.add(fields.get(i));
            var capture = adapter().read(snapshot(), manifest(), selected);
            assertThat(capture.entries()).hasSize(20);
            for (var entry : capture.entries().values()) {
                byte[] bytes = Files.readAllBytes(root.resolve(entry.rawRef()));
                var raw = mapper.readTree(bytes);
                assertThat(entry.content().values()).containsOnlyKeys(selected);
                assertThat(entry.content().locators()).containsOnlyKeys(selected);
                assertThat(entry.version()).isEqualTo("RAW_SHA256:" + ProductCapturedArchive.digest(bytes));
                for (String field : selected) {
                    assertThat(entry.content().values().get(field)).isEqualTo(raw.path(keys.get(field)).asText());
                    String locator = entry.content().locators().get(field);
                    assertThat(locator).startsWith(entry.rawRef() + "#json-pointer=/" + keys.get(field) + "&utf8=");
                    String[] range = locator.split("utf8=")[1].split(":");
                    int start = Integer.parseInt(range[0]), end = Integer.parseInt(range[1]);
                    assertThat(mapper.readTree(bytes, start, end - start).asText())
                            .isEqualTo(entry.content().values().get(field));
                }
                String first = fields.stream().filter(selected::contains).findFirst().orElseThrow();
                assertThat(entry.locator()).isEqualTo(entry.content().locators().get(first));
                assertThat(entry.content().values()).doesNotContainKeys("examiner_id", "inventor", "decision", "LEGAL_STATUS");
            }
            assertThat(capture.documents(ProductPatentSearchFilters.none())).hasSize(20)
                    .allSatisfy(document -> assertThat(document.fields()).containsOnlyKeys(selected));
        }
    }

    @Test void defaultTitleContractAndDeterministicAnchorAreUnchanged() throws Exception {
        var adapter = adapter();
        assertThat(adapter.read(snapshot(), manifest()).documents(ProductPatentSearchFilters.none()))
                .isEqualTo(adapter.read(snapshot(), manifest(), Set.of("TITLE")).documents(ProductPatentSearchFilters.none()));
        var one = adapter.read(snapshot(), manifest(), new LinkedHashSet<>(List.of("DESCRIPTION", "ABSTRACT")));
        var two = adapter.read(snapshot(), manifest(), new LinkedHashSet<>(List.of("ABSTRACT", "DESCRIPTION")));
        assertThat(one).isEqualTo(two);
        assertThat(one.entries().values()).allSatisfy(entry -> assertThat(entry.locator()).contains("/abstract&utf8="));
    }

    @Test void selectedProjectionCannotBeMutatedOrAcquireUnselectedFields() throws Exception {
        var entry = adapter().read(snapshot(), manifest(), Set.of("CLAIMS")).entries().values().iterator().next();
        assertThatThrownBy(() -> entry.content().values().put("TITLE", "added"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> entry.content().locators().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(entry.locator()).contains("/claims&utf8=");
    }

    @Test void invalidSelectedSetsRejectWithoutTitleFallback() throws Exception {
        var snapshot = snapshot(); var manifest = manifest();
        for (Set<String> invalid : List.of(Set.<String>of(), Set.of("LEGAL_STATUS"), Set.of("TITLE", "PERSONAL_DATA")))
            assertThatThrownBy(() -> adapter().read(snapshot, manifest, invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter().read(snapshot, manifest, null)).isInstanceOf(IllegalArgumentException.class);
        var containsNull = new HashSet<String>(); containsNull.add(null);
        assertThatThrownBy(() -> adapter().read(snapshot, manifest, containsNull)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void changedShaOrLocatorNeverBecomesSelectedOriginalContent() throws Exception {
        var item = (ObjectNode) mapper.readTree(manifest()).path("records").get(0).deepCopy();
        String rawRef = ref.substring(0, ref.lastIndexOf('/') + 1) + item.path("file").asText();
        byte[] bytes = Files.readAllBytes(root.resolve(rawRef));
        var wrongSha = item.deepCopy(); wrongSha.put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> ProductCapturedPatentFields.read(rawRef, bytes, wrongSha, Set.of("ABSTRACT"), mapper))
                .isInstanceOf(IllegalStateException.class);
        for (var locator : item.path("locators")) if (locator.path("field").asText().equals("abstract"))
            ((ObjectNode) locator).put("valueUtf8Start", locator.path("valueUtf8Start").longValue() + 1);
        assertThatThrownBy(() -> ProductCapturedPatentFields.read(rawRef, bytes, item, Set.of("ABSTRACT"), mapper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void missingEmptyAndNontextSelectedContentRejectRatherThanUseOtherFields() throws Exception {
        var item = mapper.readTree(manifest()).path("records").get(0);
        String rawRef = ref.substring(0, ref.lastIndexOf('/') + 1) + item.path("file").asText();
        var actual = (ObjectNode) mapper.readTree(Files.readAllBytes(root.resolve(rawRef)));
        for (int mutation = 0; mutation < 4; mutation++) {
            var changed = actual.deepCopy();
            if (mutation == 0) changed.remove("claims");
            else if (mutation == 1) changed.put("claims", " ");
            else if (mutation == 2) changed.putNull("claims");
            else changed.put("claims", 42);
            byte[] bytes = mapper.writeValueAsBytes(changed);
            var captured = mutatedInventory(bytes);
            assertThatThrownBy(() -> ProductCapturedPatentFields.read(rawRef, bytes, captured, Set.of("CLAIMS"), mapper))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** Corruption counterexamples rebuilt only to exercise missing-field checks, never a registered source. */
    private ObjectNode mutatedInventory(byte[] bytes) throws Exception {
        String sha = ProductCapturedArchive.digest(bytes); var item = mapper.createObjectNode().put("sha256", sha);
        var names = item.putArray("fields"); var locators = item.putArray("locators");
        try (var parser = mapper.getFactory().createParser(bytes)) {
            assertThat(parser.nextToken()).isEqualTo(com.fasterxml.jackson.core.JsonToken.START_OBJECT);
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                String name = parser.currentName();
                long key = parser.currentTokenLocation().getByteOffset();
                var token = parser.nextToken(); long start = parser.currentTokenLocation().getByteOffset();
                if (token.isStructStart()) parser.skipChildren();
                else if (token == com.fasterxml.jackson.core.JsonToken.VALUE_STRING) parser.getText();
                long end = parser.currentLocation().getByteOffset(); names.add(name);
                locators.addObject().put("field", name).put("jsonPointer", "/" + name.replace("~", "~0").replace("/", "~1"))
                        .put("rawSha256", sha).put("encoding", "UTF-8").put("keyUtf8Start", key)
                        .put("keyUtf8EndExclusive", key + mapper.writeValueAsBytes(name).length)
                        .put("valueUtf8Start", start).put("valueUtf8EndExclusive", end);
            }
        }
        return item;
    }
}
