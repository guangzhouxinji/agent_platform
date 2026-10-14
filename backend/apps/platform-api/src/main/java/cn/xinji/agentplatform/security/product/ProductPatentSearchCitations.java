package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Citation facts come from immutable capture bytes, never reconstructed publication URLs. */
final class ProductPatentSearchCitations {
    record Citation(String memberId, String sourceId, String documentId, String documentVersion,
                    String sourceVersion, String sourceUrl, String originalRequestUrl,
                    Instant collectedAt, String rawSha, String locatorRef,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                    Map<String,String> fieldLocators) {
        Citation {
            fieldLocators = fieldLocators == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(fieldLocators));
        }
        Citation(String memberId, String sourceId, String documentId, String documentVersion,
                String sourceVersion, String sourceUrl, String originalRequestUrl,
                Instant collectedAt, String rawSha, String locatorRef) {
            this(memberId,sourceId,documentId,documentVersion,sourceVersion,sourceUrl,originalRequestUrl,
                    collectedAt,rawSha,locatorRef,Map.of());
        }
    }
    private record Capture(ProductSourceAvailability.Active active, Instant collectedAt,
                           Map<String,JsonNode> records, Map<String,JsonNode> raw,
                           ProductCollectedPatentContent.Capture nativeContent) { }
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    ProductPatentSearchCitations(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files; this.mapper = mapper;
    }

    List<Citation> read(List<ProductPatentSearchExecution.Member> members,
            ProductSourceAvailability availability) {
        return read(members, source -> availability.require(source, "TITLE", "QUERY"));
    }

    List<Citation> readFields(List<ProductPatentSearchExecution.Member> members,
            ProductSourceAvailability availability, java.util.Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        var fields = java.util.Set.copyOf(selected);
        return read(members, source -> {
            ProductSourceAvailability.Active snapshot = null;
            for (String field : new java.util.TreeSet<>(fields)) {
                var active = availability.require(source,field,"QUERY");
                if (active == null || !source.equals(active.sourceId())
                        || (snapshot != null && !snapshot.equals(active))) throw unavailable();
                snapshot = active;
            }
            return snapshot;
        }, fields, true);
    }

    List<Citation> read(List<ProductPatentSearchExecution.Member> members,
            Map<String,ProductSourceAvailability.Active> currentSources) {
        return read(members, source -> {
            var active = currentSources.get(source);
            if (active == null || !source.equals(active.sourceId())) throw unavailable();
            return active;
        });
    }

    List<Citation> readFields(List<ProductPatentSearchExecution.Member> members,
            Map<String,ProductSourceAvailability.Active> currentSources, java.util.Set<String> selected) {
        ProductCapturedPatentFields.validate(selected);
        var fields=java.util.Set.copyOf(selected);
        return read(members,source -> {
            var active=currentSources.get(source);
            if(active==null || !source.equals(active.sourceId())) throw unavailable();
            return active;
        },fields,true);
    }

    private List<Citation> read(List<ProductPatentSearchExecution.Member> members,
            java.util.function.Function<String,ProductSourceAvailability.Active> sourceFacts) {
        return read(members,sourceFacts,java.util.Set.of("TITLE"),false);
    }

    private List<Citation> read(List<ProductPatentSearchExecution.Member> members,
            java.util.function.Function<String,ProductSourceAvailability.Active> sourceFacts,
            java.util.Set<String> selected, boolean includeFields) {
        Map<String,Capture> sources = new HashMap<>();
        Map<String,java.util.Set<String>> requested = new HashMap<>();
        for (var member : members) requested.computeIfAbsent(member.sourceId(), ignored -> new java.util.HashSet<>())
                .add(member.documentId());
        List<Citation> result = new ArrayList<>();
        List<ProductCitationFileVerification.Reference> originals = new ArrayList<>();
        for (var member : members) {
            Capture capture = sources.computeIfAbsent(member.sourceId(), source ->
                    capture(sourceFacts.apply(source),selected,requested.get(source)));
            if (includeFields && member.matchFields().stream().anyMatch(field ->
                    !selected.contains(field) && !"DOCUMENT_ID".equals(field))) throw unavailable();
            if (capture.nativeContent() != null) {
                var citation = capture.nativeContent().citation(member,includeFields);
                var original = capture.nativeContent().entries().get(member.documentId());
                originals.add(new ProductCitationFileVerification.Reference(original.rawRef(), member.rawSha()));
                result.add(citation);
                continue;
            }
            JsonNode record = capture.records().get(member.documentId());
            if (record == null || !capture.active().sourceVersion().equals(member.sourceVersion())
                    || !required(record, "sourceVersion").equals(member.documentVersion()))
                throw unavailable();
            String name = required(record, "detailRawFile");
            JsonNode response = capture.raw().get(name);
            String rawRef = sibling(capture.active().manifestRef(), name);
            if (response == null || !required(response, "sourceRecordId").equals(member.documentId())
                    || !required(response, "sha256").equalsIgnoreCase(member.rawSha())
                    || !(rawRef + "#title").equals(member.locatorRef())) throw unavailable();
            originals.add(new ProductCitationFileVerification.Reference(rawRef, member.rawSha()));
            result.add(new Citation(member.memberId(), member.sourceId(), member.documentId(),
                    member.documentVersion(), member.sourceVersion(), publicUrl(required(record,"detailUrl")),
                    publicUrl(required(response,"requestUrl")), capture.collectedAt(),
                    member.rawSha(), member.locatorRef(),includeFields
                            ? Map.of("TITLE",member.locatorRef()) : Map.of()));
        }
        ProductPatentSearchTransportObservation.measure(ProductPatentSearchTransportObservation.Detail.CITATION_ORIGINALS,
                () -> { ProductCitationFileVerification.verify(files, originals); return null; });
        return List.copyOf(result);
    }

    private Capture capture(ProductSourceAvailability.Active active, java.util.Set<String> selected,
                            java.util.Set<String> requestedDocuments) {
        try {
            byte[] manifestBytes = files.verified(active.manifestRef(), active.manifestSha());
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (ProductPatentsViewSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText())) {
                var snapshot = new ProductCollectedPatentTitles.CaptureSnapshot(active.sourceId(),active.sourceVersion(),
                        active.manifestRef(),active.manifestSha(),active.recordsSha());
                var nativeContent = new ProductPatentsViewSourceDocuments(files,mapper).read(snapshot,manifestBytes,selected,requestedDocuments);
                return new Capture(active,nativeContent.collectedAt(),Map.of(),Map.of(),nativeContent);
            }
            if (ProductHupdSourceInventory.FORMAT.equals(manifest.path("schemaVersion").asText())) {
                var snapshot = new ProductCollectedPatentTitles.CaptureSnapshot(active.sourceId(), active.sourceVersion(),
                        active.manifestRef(), active.manifestSha(), active.recordsSha());
                var nativeContent = new ProductHupdSourceDocuments(files, mapper).read(snapshot, manifestBytes,selected);
                return new Capture(active, nativeContent.collectedAt(), Map.of(), Map.of(), nativeContent);
            }
            if (!selected.equals(java.util.Set.of("TITLE"))) throw unavailable();
            String generatedAt = required(manifest, "generatedAt");
            if (!(required(manifest,"datasetId") + ":" + generatedAt).equals(active.sourceVersion()))
                throw unavailable();
            JsonNode contents = ProductPatentCitationRecords.read(mapper,
                    files.verified(sibling(active.manifestRef(),
                            required(manifest,"recordsFile")), active.recordsSha()));
            if (!required(contents,"datasetId").equals(required(manifest,"datasetId"))
                    || !required(contents,"generatedAt").equals(generatedAt)
                    || !contents.path("records").isArray()
                    || contents.path("records").size() != manifest.path("recordCount").asInt(-1))
                throw unavailable();
            Map<String,JsonNode> records = new HashMap<>(), raw = new HashMap<>();
            for (JsonNode record : contents.path("records"))
                if (records.put(required(record,"sourceRecordId"),record) != null) throw unavailable();
            for (JsonNode response : manifest.path("rawResponses"))
                if ("DETAIL".equals(response.path("type").asText())
                        && raw.put(required(response,"file"),response) != null) throw unavailable();
            return new Capture(active, Instant.parse(generatedAt), Map.copyOf(records), Map.copyOf(raw), null);
        } catch (Exception failure) {
            throw new IllegalStateException("Search citation evidence unavailable", failure);
        }
    }

    static String publicUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 4000 || uri.isOpaque() || uri.getHost() == null
                    || uri.getUserInfo() != null || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) throw unavailable();
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
    }
    private static String sibling(String manifest, String child) {
        if (child.contains("..") || child.startsWith("/") || child.contains(":")) throw unavailable();
        int separator = manifest.lastIndexOf('/');
        return (separator < 0 ? "" : manifest.substring(0,separator+1)) + child.replace('\\','/');
    }
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw unavailable();
        return value;
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Search citation evidence unavailable");
    }
}
