package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static cn.xinji.agentplatform.security.product.ProductCollectedPatentContent.*;

/** Registered-source format projection. Admission and current field use remain caller-owned. */
final class ProductPatentsViewSourceDocuments {
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    ProductPatentsViewSourceDocuments(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files; this.mapper = mapper;
    }
    Capture read(ProductCollectedPatentTitles.CaptureSnapshot source, byte[] manifestBytes) {
        return read(source,manifestBytes,Set.of("TITLE"));
    }
    Capture read(ProductCollectedPatentTitles.CaptureSnapshot source, byte[] manifestBytes, Set<String> selected) {
        return read(source,manifestBytes,selected,null);
    }
    /** Verify the entire inventory before projecting only the requested page. */
    Capture read(ProductCollectedPatentTitles.CaptureSnapshot source, byte[] manifestBytes, Set<String> selected,
                 Set<String> requestedDocuments) {
        ProductCapturedPatentFields.validate(selected);
        var verified = ProductPatentSearchTransportObservation.measure(
                ProductPatentSearchTransportObservation.Detail.SOURCE_INVENTORY,
                () -> new ProductPatentsViewSourceInventory(files,mapper).selectedContent(source.manifestRef(),manifestBytes));
        if (!source.sourceId().equals(verified.sourceId()) || !source.sourceVersion().equals(verified.sourceVersion())
                || !source.manifestSha().equals(verified.recordsSha()) || !source.recordsSha().equals(verified.recordsSha()))
            throw new IllegalStateException("Native content differs from registered verification snapshot");
        var requested = requestedDocuments == null ? verified.documents().keySet() : Set.copyOf(requestedDocuments);
        if (!verified.documents().keySet().containsAll(requested))
            throw new IllegalStateException("Requested native document unavailable");
        Map<String,Entry> entries = ProductPatentSearchTransportObservation.measure(
                ProductPatentSearchTransportObservation.Detail.SOURCE_PROJECTION, () -> {
        Map<String,Entry> projected = new LinkedHashMap<>();
        for (var original : verified.documents().values()) {
            if (!requested.contains(original.id())) continue;
            var content = ProductCapturedPatentFields.readVerifiedEnvelope(original,selected,
                    Map.of("TITLE","title","CLAIMS","claimsText"),mapper);
            LocalDate published = LocalDate.parse(original.record().path("metadata").path("originalFields").path("patent_date").asText());
            projected.put(original.id(),new Entry(original.id(),original.version(),original.ref(),original.sha(),content,
                    published,List.of(),List.of()));
        }
        return projected;
        });
        try {
            return new Capture(source,verified.provenance(),Instant.parse(mapper.readTree(manifestBytes).path("collectedAt").asText()),
                    Collections.unmodifiableMap(entries));
        } catch (java.io.IOException failure) { throw new IllegalStateException("Native source metadata unavailable",failure); }
    }
}
