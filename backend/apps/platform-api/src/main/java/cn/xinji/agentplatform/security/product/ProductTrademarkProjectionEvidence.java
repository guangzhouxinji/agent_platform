package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductTrademarkCapture.*;
import static cn.xinji.agentplatform.security.product.ProductTrademarkCaptureJson.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;

/** Loads every controlled capture member before reusing the proven mapper. */
final class ProductTrademarkProjectionEvidence {
    record Loaded(Input input, VerifiedTrademarkCapture capture, java.util.Set<String> extraSaveFields) { }
    private final ProductSourceEvidenceFiles files;
    private final ProductTrademarkCaptureJson json;
    private final ProductTrademarkCaptureMapper mapper;
    ProductTrademarkProjectionEvidence(ProductSourceEvidenceFiles files, ObjectMapper json) {
        this.files = files; this.json = new ProductTrademarkCaptureJson(json);
        mapper = new ProductTrademarkCaptureMapper(json);
    }
    Loaded load(ProductTrademarkProjectionApproval approved, String manifestRef, String manifestSHA,
                String recordsSHA, String inventorySHA, long recordCount, long rawCount) {
        Artifact manifest = read(manifestRef, manifestSHA);
        var declaration = json.parse(manifest.bytes());
        String directory = manifestRef.substring(0, manifestRef.lastIndexOf('/') + 1);
        Artifact records = read(directory + text(declaration.get("recordsFile")), recordsSHA);
        var inventory = declaration.get("rawResponses");
        require(inventory != null && inventory.isArray() && inventory.size() > 0 && inventory.size() <= MAX_ORIGINALS);
        var originals = new ArrayList<Artifact>(); var extra = new java.util.TreeSet<String>();
        long total = manifest.byteLength() + records.byteLength();
        for (var member : inventory) {
            String ref = directory + text(member.get("file"));
            Artifact raw = read(ref, text(member.get("sha256")));
            require(raw.byteLength() == integer(member.get("bytes")));
            total += raw.byteLength(); require(total <= MAX_TOTAL_BYTES); originals.add(raw);
            var body = json.parse(raw.bytes());
            if (body.has("images")) extra.add("IMAGE");
            if (body.has("history")) extra.add("HISTORY");
        }
        Artifact map = read(approved.value("mapEvidenceRef"), approved.value("mapSHA"));
        require(map.sha256().equals(ProductTrademarkCaptureMapper.MAPPING.sha256()));
        Artifact nature = read(approved.value("natureEvidenceRef"), approved.value("natureEvidenceSHA"));
        Input input = new Input(approved.value("sourceId"), approved.value("contractVersion"),
                approved.value("registeredSourceVersion"), manifest, records, originals,
                new Mapping(approved.value("mapId"), approved.value("mapVersion"), approved.value("mapSHA")), nature);
        VerifiedTrademarkCapture capture = mapper.map(input);
        require(capture.manifestSha256().equals(approved.value("manifestSHA"))
                && capture.recordsSha256().equals(approved.value("recordsSHA"))
                && capture.inventorySha256().equals(inventorySHA) && inventorySHA.equals(approved.value("inventorySHA"))
                && capture.bindingSetSha256().equals(approved.value("bindingSetSHA"))
                && capture.nature().code().equals(approved.value("sourceNature"))
                && !"UNKNOWN".equals(capture.nature().code())
                && capture.records().size() == recordCount && originals.size() == rawCount);
        return new Loaded(input, capture, java.util.Set.copyOf(extra));
    }
    private Artifact read(String ref, String sha) {
        byte[] bytes = files.verified(ref, sha); return new Artifact(ref, digestText(sha), bytes.length, bytes);
    }
}
