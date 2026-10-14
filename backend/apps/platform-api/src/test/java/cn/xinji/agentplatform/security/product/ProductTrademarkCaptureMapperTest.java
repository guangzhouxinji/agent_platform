package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.sha;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ProductTrademarkCaptureMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERATED = "2026-10-13T00:00:00Z";
    private static final String VERSION = "dataset:" + GENERATED;
    private static final String DETAIL = """
            {"sourceRecordId":"r1","registrationNumber":"00012A","name":"\u5546\u6807",
             "applicants":["Owner A","Owner B"],"applicantAddress":"Address",
             "classes":[{"code":"09","name":"Electronics","goodsServices":["first","second"],"similarGroups":["0901","0902"]},
                        {"code":"42","name":null,"goodsServices":["third"],"similarGroups":["4209"]}],
             "registrationDate":"2024-02-29","validUntil":"2034-02","sourceStatus":"unfamiliar","sourceUpdatedAt":"original time"}
            """;
    private final ProductTrademarkCaptureMapper mapper = new ProductTrademarkCaptureMapper(JSON);
    record Raw(String id, byte[] bytes) { }

    @Test void allFieldsPrecisionRealBytesAndVersionsSurvive() {
        Input input = fixture(List.of(raw("r1", DETAIL)));
        var result = mapper.map(input); var record = result.records().getFirst(); var view = record.observation();
        assertEquals("00012A", view.registrationNumber());
        assertEquals(List.of("Owner A", "Owner B"), view.applicants());
        assertEquals(2, view.classes().size()); assertEquals(List.of("first", "second"), view.classes().getFirst().items());
        assertEquals(List.of("0901", "0902"), view.classes().getFirst().similarGroups());
        assertEquals(LocalDate.of(2024, 2, 29), view.registered().day());
        assertEquals("MONTH", view.validUntil().precision()); assertNull(view.validUntil().day());
        assertEquals("UNMAPPED", view.displayStatus()); assertEquals("original time", view.sourceUpdatedAt());
        assertEquals(VERSION, record.registeredSourceVersion());
        assertEquals("RAW_SHA256:" + sha(input.originals().getFirst().bytes()), view.ref().version());
        assertEquals(Admission.NOT_CHECKED, result.admission()); assertEquals("UNKNOWN", result.nature().code());
        assertEquals(Admission.NOT_CHECKED, result.nature().admission());
        assertTrue(result.bindingSetSha256().matches("[0-9a-f]{64}"));
        FieldEvidence name = field(record, "name");
        byte[] token = Arrays.copyOfRange(input.originals().getFirst().bytes(), name.tokenStart(), name.tokenEndExclusive());
        assertEquals("\"\u5546\u6807\"", new String(token, StandardCharsets.UTF_8)); assertEquals(sha(token), name.tokenSha256());
    }
    @Test void missingNullAndYearRemainDifferent() {
        var result = mapper.map(fixture(List.of(raw("r1", "{\"sourceRecordId\":\"r1\",\"name\":null,\"registrationDate\":\"2024\"}"))));
        var record = result.records().getFirst();
        assertEquals("YEAR", record.observation().registered().precision());
        assertNull(record.observation().registered().day()); assertNull(record.observation().registrationNumber());
        assertTrue(record.observation().classes().isEmpty());
        assertEquals(ValueState.NULL, field(record, "name").state());
        assertNotNull(field(record, "name").tokenSha256());
        assertEquals(ValueState.MISSING, field(record, "registrationNumber").state());
        assertNull(field(record, "registrationNumber").tokenStart());
        assertNull(field(record, "registrationNumber").tokenSha256());
    }
    @Test void numericRegistrationNumberIsNotCoerced() {
        reject(DETAIL.replace("\"00012A\"", "12"));
        assertEquals(" 00012A ", mapper.map(fixture(List.of(raw("r1", DETAIL.replace("00012A", " 00012A ")))))
                .records().getFirst().observation().registrationNumber());
    }
    @Test void datesDoNotNormalizeOrInventDays() {
        for (String date : List.of("2023-02-29", "2024-13", "0000", "24", "2024-2", "2024-01-32", " 2024"))
            reject(DETAIL.replace("2024-02-29", date));
    }
    @Test void duplicateStableIdAcrossDifferentRawVersionsFailsWholeCapture() {
        Input input = fixture(List.of(raw("r1", DETAIL), raw("r1", DETAIL.replace("00012A", "00012B"))));
        assertThrows(InvalidCapture.class, () -> mapper.map(input));
    }
    @Test void wrongOriginalIdSourceAndRawVersionFail() {
        assertThrows(InvalidCapture.class, () -> mapper.map(fixture(List.of(raw("r2", DETAIL)))));
        Input original = fixture(List.of(raw("r1", DETAIL)));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeRecords(original, node ->
                ((ObjectNode) node.path("records").get(0)).put("sourceId", "another"))));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeRecords(original, node ->
                ((ObjectNode) node.path("records").get(0)).put("sourceVersion", "source-s3"))));
    }
    @Test void nestedAndTopLevelDuplicateKeysAndTrailingContentFail() {
        reject(DETAIL.replace("\"code\":\"09\"", "\"code\":\"09\",\"code\":\"42\""));
        reject(DETAIL.replace("\"sourceRecordId\":\"r1\"", "\"sourceRecordId\":\"r1\",\"sourceRecordId\":\"r1\""));
        reject(DETAIL + " {}");
    }
    @Test void malformedUtf8AndEscapedLoneSurrogateFail() {
        byte[] bytes = DETAIL.getBytes(StandardCharsets.UTF_8); bytes[bytes.length / 2] = (byte) 0x80;
        assertThrows(InvalidCapture.class, () -> mapper.map(fixture(List.of(new Raw("r1", bytes)))));
        reject(DETAIL.replace("Owner A", "\\uD800"));
    }
    @Test void callerPermissiveMapperDoesNotWeakenOriginalParsing() {
        var permissive = JSON.copy().enable(JsonParser.Feature.ALLOW_COMMENTS, JsonParser.Feature.ALLOW_SINGLE_QUOTES);
        var privateMapper = new ProductTrademarkCaptureMapper(permissive);
        assertThrows(InvalidCapture.class, () -> privateMapper.map(fixture(List.of(raw("r1", "/*comment*/" + DETAIL)))));
        assertThrows(InvalidCapture.class, () -> privateMapper.map(fixture(List.of(raw("r1", DETAIL.replace('"', '\''))))));
        assertTrue(permissive.isEnabled(JsonParser.Feature.ALLOW_COMMENTS));
    }
    @Test void missingExtraAndChangedOriginalsFailInventory() {
        Input original = fixture(List.of(raw("r1", DETAIL)));
        assertThrows(InvalidCapture.class, () -> mapper.map(withOriginals(original, List.of())));
        List<Artifact> extra = new ArrayList<>(original.originals()); extra.add(artifact("capture/extra.json", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(InvalidCapture.class, () -> mapper.map(withOriginals(original, extra)));
        Artifact first = original.originals().getFirst(); byte[] changed = first.bytes(); changed[0] = ' ';
        assertThrows(InvalidCapture.class, () -> mapper.map(withOriginals(original,
                List.of(new Artifact(first.ref(), first.sha256(), first.byteLength(), changed)))));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeManifest(original, node ->
                ((ObjectNode) node.path("rawResponses").get(0)).put("bytes", first.byteLength() + 1))));
    }
    @Test void unsafeRefsAndMixedRegisteredVersionFail() {
        Input original = fixture(List.of(raw("r1", DETAIL)));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeRecords(original, node ->
                ((ObjectNode) node.path("records").get(0)).put("detailRawFile", "../raw0.json"))));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeRecords(original, node ->
                ((ObjectNode) node.path("records").get(0)).put("sourceRef", "https://private.invalid/?token=secret"))));
        assertThrows(InvalidCapture.class, () -> mapper.map(new Input(original.sourceId(), original.contractVersion(), "source-s3",
                original.manifest(), original.records(), original.originals(), original.mapping(), null)));
        assertThrows(InvalidCapture.class, () -> mapper.map(changeRecords(original, node ->
                ((ObjectNode) node.path("records").get(0)).put("capturedAt", "2026-10-14T00:00:00Z"))));
        assertThrows(InvalidCapture.class, () -> mapper.map(new Input(original.sourceId(), original.contractVersion(), VERSION,
                original.manifest(), original.records(), original.originals(), new Mapping("another", "1", "0".repeat(64)), null)));
    }
    @Test void arrayMembersTypesAndFiniteLimitsAreChecked() {
        reject(DETAIL.replace("\"Owner B\"", "12"));
        reject(DETAIL.replace("\"goodsServices\":[\"third\"]", "\"goodsServices\":{}"));
        ObjectNode detail = read(DETAIL.getBytes(StandardCharsets.UTF_8));
        var owners = detail.putArray("applicants"); for (int i = 0; i < 10001; i++) owners.add("owner");
        assertThrows(InvalidCapture.class, () -> mapper.map(fixture(List.of(new Raw("r1", encode(detail))))));
        reject(DETAIL.replace("Owner A", "x".repeat(65537)));
    }
    @Test void oneMalformedSecondMemberNeverReturnsPartialProjection() {
        assertThrows(InvalidCapture.class, () -> mapper.map(fixture(List.of(raw("r1", DETAIL),
                raw("r2", DETAIL.replace("r1", "r2").replace("\"00012A\"", "12"))))));
    }
    @Test void contentChangesAffectProjectionAndRelationDigests() {
        var first = mapper.map(fixture(List.of(raw("r1", DETAIL)))).records().getFirst();
        var second = mapper.map(fixture(List.of(raw("r1", DETAIL.replace("second", "changed"))))).records().getFirst();
        assertNotEquals(first.normalizedSha256(), second.normalizedSha256());
        assertNotEquals(first.relationSha256(), second.relationSha256());
    }
    @Test void canonicalDigestsIgnoreCallerPrettyPrintingAndMemberOrdering() {
        Input input = fixture(List.of(raw("r2", DETAIL.replace("r1", "r2")), raw("r1", DETAIL)));
        var normal = mapper.map(input);
        var pretty = new ProductTrademarkCaptureMapper(JSON.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)).map(input);
        assertEquals(normal.bindingSetSha256(), pretty.bindingSetSha256());
        assertEquals(List.of("r1", "r2"), normal.records().stream().map(item -> item.observation().ref().recordId()).toList());
        Input reordered = changeRecords(input, node -> {
            var rows = node.withArray("records"); var first = rows.remove(0); rows.add(first);
        });
        assertEquals(normal.bindingSetSha256(), mapper.map(reordered).bindingSetSha256());
    }
    @Test void customCallerSerializerCannotChangeFixedProjectionSchemas() throws Exception {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.RecordRef.class,
                new com.fasterxml.jackson.databind.JsonSerializer<cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.RecordRef>() {
                    @Override public void serialize(cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.RecordRef value,
                            com.fasterxml.jackson.core.JsonGenerator generator, com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                        generator.writeString("polluted");
                    }
                });
        var caller = JSON.copy().registerModule(module);
        Input input = fixture(List.of(raw("r1", DETAIL)));
        var normal = mapper.map(input); var isolated = new ProductTrademarkCaptureMapper(caller).map(input);
        assertEquals("\"polluted\"", caller.writeValueAsString(normal.records().getFirst().observation().ref()));
        assertEquals(normal, isolated);
    }
    @Test void byteCopiesAndResultCollectionsCannotBeMutated() {
        byte[] bytes = DETAIL.getBytes(StandardCharsets.UTF_8); Artifact raw = artifact("capture/raw0.json", bytes);
        bytes[0] = ' '; assertEquals('{', raw.bytes()[0]); byte[] returned = raw.bytes(); returned[0] = ' ';
        assertEquals('{', raw.bytes()[0]);
        var capture = mapper.map(fixture(List.of(raw("r1", DETAIL))));
        assertThrows(UnsupportedOperationException.class, () -> capture.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> capture.records().getFirst().fields().clear());
    }
    @Test void natureEvidenceRemainsUnadmittedAndMustBindExactSource() {
        Input original = fixture(List.of(raw("r1", DETAIL)));
        ObjectNode nature = JSON.createObjectNode().put("schema", "TM-SOURCE-NATURE-V1").put("sourceId", "source")
                .put("contractVersion", "contract-v1").put("sourceVersion", VERSION).put("sourceNature", "RESTRICTED");
        Input withNature = withNature(original, artifact("capture/nature.json", encode(nature)));
        var claim = mapper.map(withNature).nature(); assertEquals("RESTRICTED", claim.code());
        assertEquals(Admission.NOT_CHECKED, claim.admission());
        nature.put("sourceId", "another");
        assertThrows(InvalidCapture.class, () -> mapper.map(withNature(original, artifact("capture/nature.json", encode(nature)))));
        nature.put("sourceId", "source").put("sourceNature", "APPROVED_SUBSTITUTE");
        assertThrows(InvalidCapture.class, () -> mapper.map(withNature(original, artifact("capture/nature.json", encode(nature)))));
    }

    private void reject(String detail) { assertThrows(InvalidCapture.class, () -> mapper.map(fixture(List.of(raw("r1", detail))))); }
    private static Raw raw(String id, String body) { return new Raw(id, body.getBytes(StandardCharsets.UTF_8)); }
    private static FieldEvidence field(ProjectedTrademarkRecord record, String name) {
        return record.fields().stream().filter(item -> item.field().equals(name)).findFirst().orElseThrow();
    }
    private static Input fixture(List<Raw> raws) {
        List<Artifact> originals = new ArrayList<>();
        ObjectNode records = JSON.createObjectNode().put("datasetId", "dataset").put("generatedAt", GENERATED);
        var rows = records.putArray("records");
        ObjectNode manifest = JSON.createObjectNode().put("datasetId", "dataset").put("generatedAt", GENERATED)
                .put("recordsFile", "records.json").put("recordCount", raws.size());
        manifest.putObject("source").put("apiBaseUrl", "https://source.invalid"); var inventory = manifest.putArray("rawResponses");
        for (int i = 0; i < raws.size(); i++) {
            Raw raw = raws.get(i); String file = "raw" + i + ".json"; Artifact artifact = artifact("capture/" + file, raw.bytes()); originals.add(artifact);
            rows.addObject().put("sourceId", "source").put("sourceRecordId", raw.id()).put("sourceVersion", "RAW_SHA256:" + artifact.sha256())
                    .put("capturedAt", GENERATED).put("detailRawFile", file).put("sourceRef", "source-ref-" + i);
            inventory.addObject().put("file", file).put("sha256", artifact.sha256()).put("bytes", artifact.byteLength());
        }
        Artifact record = artifact("capture/records.json", encode(records)); manifest.put("recordsSha256", record.sha256());
        return new Input("source", "contract-v1", VERSION, artifact("capture/manifest.json", encode(manifest)), record,
                originals, ProductTrademarkCaptureMapper.MAPPING, null);
    }
    private static Input changeManifest(Input input, Consumer<ObjectNode> change) {
        ObjectNode manifest = read(input.manifest().bytes()); change.accept(manifest);
        return new Input(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(),
                artifact(input.manifest().ref(), encode(manifest)), input.records(), input.originals(), input.mapping(), input.natureEvidence());
    }
    private static Input changeRecords(Input input, Consumer<ObjectNode> change) {
        ObjectNode records = read(input.records().bytes()); change.accept(records); Artifact artifact = artifact(input.records().ref(), encode(records));
        Input replaced = new Input(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(), input.manifest(), artifact,
                input.originals(), input.mapping(), input.natureEvidence());
        return changeManifest(replaced, node -> node.put("recordsSha256", artifact.sha256()));
    }
    private static Input withOriginals(Input input, List<Artifact> originals) {
        return new Input(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(), input.manifest(), input.records(), originals, input.mapping(), input.natureEvidence());
    }
    private static Input withNature(Input input, Artifact nature) {
        return new Input(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(), input.manifest(), input.records(), input.originals(), input.mapping(), nature);
    }
    private static Artifact artifact(String ref, byte[] bytes) { return new Artifact(ref, sha(bytes), bytes.length, bytes); }
    private static byte[] encode(ObjectNode node) { try { return JSON.writeValueAsBytes(node); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static ObjectNode read(byte[] bytes) { try { return (ObjectNode) JSON.readTree(bytes); } catch (Exception impossible) { throw new AssertionError(impossible); } }
}
