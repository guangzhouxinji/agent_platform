package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure all-member byte verification. No persistence, transport or current authorization. */
final class ProductTrademarkCaptureMapper {
    static final Mapping MAPPING = new Mapping("TM-JSON-DIRECT-V1", "1", sha((
            "TM-JSON-DIRECT-V1:1|sourceRecordId:string|required|registrationNumber:string-or-null|name:string-or-null"
            + "|applicants:ordered-strings|applicantAddress:string-or-null|classes:ordered-code-name-goodsServices-similarGroups"
            + "|registrationDate:YYYY-YYYY-MM-YYYY-MM-DD|validUntil:YYYY-YYYY-MM-YYYY-MM-DD"
            + "|sourceStatus:UNMAPPED|sourceUpdatedAt:string-or-null|TM-NORMALIZED-C14N-V1|TM-RELATIONS-C14N-V1")
            .getBytes(StandardCharsets.UTF_8)));
    private final ProductTrademarkCaptureJson json;
    private final ProductTrademarkCapturedFields fields;
    ProductTrademarkCaptureMapper(ObjectMapper mapper) {
        json = new ProductTrademarkCaptureJson(mapper); fields = new ProductTrademarkCapturedFields(json);
    }

    VerifiedTrademarkCapture map(Input input) {
        try { return verify(input); }
        catch (InvalidCapture invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new InvalidCapture(); }
    }
    private VerifiedTrademarkCapture verify(Input input) {
        require(input != null && MAPPING.equals(input.mapping()));
        identity(input.sourceId()); identity(input.contractVersion()); identity(input.registeredSourceVersion());
        require(input.sourceId().length() <= 80 && input.registeredSourceVersion().length() <= 100
                && input.originals().size() <= MAX_ORIGINALS);
        byte[] manifestBytes = json.verified(input.manifest(), MIB);
        byte[] recordBytes = json.verified(input.records(), 8 * MIB);
        JsonNode manifest = json.parse(manifestBytes), records = json.parse(recordBytes);
        String dataset = text(manifest.get("datasetId")), generated = text(manifest.get("generatedAt"));
        Instant generatedAt = time(generated);
        require(input.registeredSourceVersion().equals(dataset + ":" + generated));
        require(dataset.equals(text(records.get("datasetId"))) && generated.equals(text(records.get("generatedAt"))));
        require(manifest.path("source").isObject()); text(manifest.path("source").get("apiBaseUrl"));
        String parent = parent(input.manifest().ref());
        String recordFile = text(manifest.get("recordsFile")); path(recordFile);
        require(input.records().ref().equals(parent + recordFile));
        require(digestText(text(manifest.get("recordsSha256"))).equals(sha(recordBytes)));
        long count = integer(manifest.get("recordCount"));
        JsonNode rows = records.get("records"), inventory = manifest.get("rawResponses");
        require(count > 0 && count <= MAX_RECORDS && rows != null && rows.isArray() && rows.size() == count);
        require(inventory != null && inventory.isArray() && !inventory.isEmpty() && inventory.size() <= MAX_ORIGINALS);
        Map<String, Artifact> originals = originals(input, manifestBytes.length + (long) recordBytes.length);
        long completeBytes = manifestBytes.length + (long) recordBytes.length;
        for (Artifact raw : originals.values()) completeBytes = Math.addExact(completeBytes, raw.byteLength());
        if (input.natureEvidence() != null) completeBytes = Math.addExact(completeBytes, input.natureEvidence().byteLength());
        require(completeBytes <= MAX_TOTAL_BYTES);
        String inventorySha = inventory(inventory, parent, originals);
        NatureClaim nature = nature(input);
        List<ProjectedTrademarkRecord> result = new ArrayList<>();
        Set<String> stableIds = new HashSet<>();
        int relationCount = 0;
        for (JsonNode row : rows) {
            require(row.isObject() && input.sourceId().equals(text(row.get("sourceId"))));
            String stable = text(row.get("sourceRecordId")); identity(stable);
            require(stable.length() <= 240 && stableIds.add(stable));
            String file = text(row.get("detailRawFile")); path(file);
            Artifact raw = originals.get(parent + file); require(raw != null && raw.byteLength() <= 2L * MIB);
            String rawSha = digestText(raw.sha256()), version = text(row.get("sourceVersion"));
            require(version.equals("RAW_SHA256:" + rawSha));
            Instant captured = time(text(row.get("capturedAt"))); require(!captured.isAfter(generatedAt));
            String ref = text(row.get("sourceRef"));
            require(ref.matches("[\\p{L}\\p{N}._-]+") && ref.length() <= 1000);
            var mapped = fields.map(raw.bytes(), raw.ref(), rawSha, input.sourceId(), stable, version, ref, nature);
            relationCount = Math.addExact(relationCount, mapped.arrayItems()); require(relationCount <= MAX_RELATIONS);
            result.add(new ProjectedTrademarkRecord(mapped.observation(), input.registeredSourceVersion(), raw.ref(), rawSha,
                    raw.byteLength(), captured, mapped.fields(), mapped.normalizedSha(), mapped.relationSha()));
        }
        result.sort((left, right) -> compareUtf8(left.observation().ref().recordId(), right.observation().ref().recordId()));
        return new VerifiedTrademarkCapture(input.sourceId(), input.contractVersion(), input.registeredSourceVersion(),
                input.manifest().ref(), sha(manifestBytes), sha(recordBytes), inventorySha, bindings(result), MAPPING,
                nature, result, Admission.NOT_CHECKED);
    }

    private Map<String, Artifact> originals(Input input, long totalBytes) {
        Map<String, Artifact> result = new HashMap<>(); Set<String> folded = new HashSet<>();
        folded.add(input.manifest().ref().toLowerCase(Locale.ROOT));
        require(folded.add(input.records().ref().toLowerCase(Locale.ROOT)));
        for (Artifact artifact : input.originals()) {
            byte[] bytes = json.verified(artifact, 2 * MIB);
            json.parse(bytes);
            totalBytes = Math.addExact(totalBytes, bytes.length); require(totalBytes <= MAX_TOTAL_BYTES);
            require(folded.add(artifact.ref().toLowerCase(Locale.ROOT)) && result.put(artifact.ref(), artifact) == null);
        }
        require(!result.isEmpty()); return result;
    }
    private String inventory(JsonNode inventory, String parent, Map<String, Artifact> originals) {
        Set<String> seen = new HashSet<>();
        // Preserve the generic Source verifier's declared inventory order and digest contract.
        StringBuilder binding = new StringBuilder();
        for (JsonNode item : inventory) {
            require(item.isObject()); String file = text(item.get("file")); path(file);
            String hash = digestText(text(item.get("sha256")));
            Artifact raw = originals.get(parent + file);
            require(seen.add(parent + file) && raw != null && hash.equals(digestText(raw.sha256()))
                    && integer(item.get("bytes")) == raw.byteLength());
            binding.append(file.length()).append(':').append(file).append(hash);
        }
        require(seen.equals(originals.keySet())); return sha(binding.toString().getBytes(StandardCharsets.UTF_8));
    }
    private NatureClaim nature(Input input) {
        if (input.natureEvidence() == null) return new NatureClaim("UNKNOWN", null, null, Admission.NOT_CHECKED);
        String ref = input.natureEvidence().ref();
        require(ref != null && !ref.equalsIgnoreCase(input.manifest().ref()) && !ref.equalsIgnoreCase(input.records().ref())
                && input.originals().stream().noneMatch(raw -> ref.equalsIgnoreCase(raw.ref())));
        byte[] bytes = json.verified(input.natureEvidence(), MIB); JsonNode node = json.parse(bytes);
        require("TM-SOURCE-NATURE-V1".equals(text(node.get("schema")))
                && input.sourceId().equals(text(node.get("sourceId")))
                && input.contractVersion().equals(text(node.get("contractVersion")))
                && input.registeredSourceVersion().equals(text(node.get("sourceVersion"))));
        String code = text(node.get("sourceNature")); require(Set.of("PUBLIC", "RESTRICTED", "APPROVED_SUBSTITUTE").contains(code));
        if (code.equals("APPROVED_SUBSTITUTE")) {
            for (String field : List.of("responsibleParty", "scope", "purpose", "licenseRef")) text(node.get(field));
            digestText(text(node.get("licenseSha256")));
        }
        return new NatureClaim(code, input.natureEvidence().ref(), sha(bytes), Admission.NOT_CHECKED);
    }
    private String bindings(List<ProjectedTrademarkRecord> records) {
        ArrayNode members = json.array();
        for (ProjectedTrademarkRecord record : records) {
            var node = json.object().put("stableId", record.observation().ref().recordId())
                    .put("recordVersion", record.observation().ref().version()).put("rawRef", record.rawRef())
                    .put("rawSHA", record.rawSha256()).put("rawBytes", record.rawBytes())
                    .put("normalizedSHA", record.normalizedSha256()).put("relationSHA", record.relationSha256());
            node.set("capturedAt", json.instant(record.capturedAt())); members.add(node);
        }
        return json.canonicalSha(json.object().put("schema", "TM-BINDING-SET-C14N-V1").set("members", members));
    }
    private String parent(String ref) { int slash = ref.lastIndexOf('/'); return slash < 0 ? "" : ref.substring(0, slash + 1); }
}
