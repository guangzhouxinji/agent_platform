package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;

/** Binds the deterministic envelope to its exact captured original CSV records. */
final class ProductPatentsViewRecord {
    private static final java.util.regex.Pattern GRANT = java.util.regex.Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final java.util.regex.Pattern INTEGER = java.util.regex.Pattern.compile("[0-9]+");
    static void verify(JsonNode record, String grant, Map<String,byte[]> originals,
            JsonNode archives, Map<String,List<ProductPatentsViewArchiveProof.Segment>> segments) {
        if (!"DETERMINISTIC_REAL_TSV_ENVELOPE/v1".equals(text(record,"representation"))
                || !ProductPatentsViewSourceInventory.SOURCE.equals(text(record,"sourceId"))
                || !grant.equals(text(record,"originalGrantId")) || !GRANT.matcher(grant).matches())
            throw new IllegalStateException("Native grant identity differs");
        Map<String,String> metadata = original(record.path("metadata"), "metadata", originals, archives, segments);
        if (!grant.equals(metadata.get("patent_id")) || !text(record,"title").equals(metadata.get("patent_title")))
            throw new IllegalStateException("Native title differs from original row");
        LocalDate.parse(metadata.get("patent_date"));
        JsonNode claims = record.path("claims");
        if (!claims.isArray() || claims.isEmpty() || claims.size() > 10000
                || !INTEGER.matcher(metadata.get("num_claims")).matches()
                || Integer.parseInt(metadata.get("num_claims")) != claims.size())
            throw new IllegalStateException("Complete native claims inventory required");
        List<String> text = new ArrayList<>();
        long previous = -1;
        Set<String> claimNumbers = new HashSet<>();
        for (JsonNode claim : claims) {
            Map<String,String> values = original(claim, "claims", originals, archives, segments);
            String sequence = values.get("claim_sequence"), number = values.get("claim_number");
            if (!grant.equals(values.get("patent_id")) || !INTEGER.matcher(sequence).matches()
                    || !INTEGER.matcher(number).matches() || !claimNumbers.add(number)
                    || Long.parseLong(sequence) <= previous || values.get("claim_text").isBlank())
                throw new IllegalStateException("Original claim identity/order differs");
            previous = Long.parseLong(sequence);
            text.add(values.get("claim_text"));
        }
        if (!String.join("\n",text).equals(text(record,"claimsText")))
            throw new IllegalStateException("Derived claims are not exact original text");
    }

    private static Map<String,String> original(JsonNode entry, String kind, Map<String,byte[]> originals,
            JsonNode archives, Map<String,List<ProductPatentsViewArchiveProof.Segment>> segments) {
        JsonNode locator = entry.path("sourceLocator"), declaration = archives.path(kind);
        String file = text(locator,"file");
        byte[] raw = originals.get(file);
        if (raw == null || !ProductCapturedArchive.digest(raw).equals(text(locator,"rowSha256"))
                || !text(locator,"archiveSha256").equals(text(declaration,"sha256"))
                || !text(locator,"archiveMember").equals(text(declaration,"archiveMember")))
            throw new IllegalStateException("Original row binding differs");
        var expected = kind.equals("claims") ? ProductPatentsViewCsv.CLAIMS : ProductPatentsViewCsv.METADATA;
        Map<String,String> values = ProductPatentsViewCsv.row(raw, expected);
        JsonNode fields = entry.path("originalFields");
        if (!fields.isObject() || fields.size() != values.size()) throw new IllegalStateException("Original row columns differ");
        for (var value : values.entrySet())
            if (!fields.path(value.getKey()).isTextual() || !value.getValue().equals(fields.path(value.getKey()).asText()))
                throw new IllegalStateException("Envelope value differs from original row");
        segments.get(kind).add(new ProductPatentsViewArchiveProof.Segment(
                number(locator,"startByte"),number(locator,"endByte"),raw));
        return values;
    }

    static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Native text missing: " + key);
        return value.asText();
    }
    static long number(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalStateException("Exact native number required: " + key);
        return value.longValue();
    }
}
