package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Native collected-content integrity only; this adapter cannot enable a source or grant field uses. */
final class ProductHupdSourceInventory {
    static final String FORMAT = "HUPD_REAL_CAPTURE/v1";
    record Result(String sourceId, String sourceVersion, String provenance, String recordsSha,
                  String inventorySha, long records, long originals, Set<String> references) { }
    private static final Pattern PUBLICATION = Pattern.compile("(US\\d{11}[A-Z]\\d)-(\\d{8})");
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;

    ProductHupdSourceInventory(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    Result inspect(String manifestRef, byte[] manifestBytes) {
        try {
            JsonNode manifest = mapper.readTree(manifestBytes);
            if (!FORMAT.equals(text(manifest, "schemaVersion"))
                    || !"REAL_CANDIDATE".equals(text(manifest, "status"))
                    || !manifest.path("productEnabled").isBoolean() || manifest.path("productEnabled").booleanValue()
                    || !manifest.path("fullArchiveSha256Verified").isBoolean()
                    || manifest.path("fullArchiveSha256Verified").booleanValue()
                    || !manifest.path("sampling").path("queryResponse").isBoolean()
                    || manifest.path("sampling").path("queryResponse").booleanValue()
                    || !manifest.path("sampling").path("professionalQrels").isBoolean()
                    || manifest.path("sampling").path("professionalQrels").booleanValue())
                throw new IllegalStateException("Native capture boundaries differ");
            String source = text(manifest, "sourceId"), providerVersion = text(manifest, "sourceVersion");
            String collected = text(manifest, "collectedAt");
            Instant.parse(collected);
            String provenance = text(manifest, "originalUrl");
            if (!provenance.equals("https://huggingface.co/datasets/HUPD/hupd/resolve/" + providerVersion + "/" + text(manifest, "archivePath"))
                    || !providerVersion.matches("[0-9a-f]{40}") || !text(manifest, "publishedArchiveSha256").matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Native archive origin differs");
            JsonNode responses = manifest.path("responses"), records = manifest.path("records");
            if (!responses.isArray() || responses.isEmpty() || !records.isArray() || records.isEmpty() || records.size() > 100)
                throw new IllegalStateException("Native capture inventory incomplete");
            String parent = Path.of(manifestRef).getParent() == null ? ""
                    : Path.of(manifestRef).getParent().toString().replace('\\', '/') + "/";
            Set<String> names = new HashSet<>(), references = new LinkedHashSet<>();
            references.add(manifestRef);
            MessageDigest inventory = MessageDigest.getInstance("SHA-256");
            byte[] prefix = null;
            for (JsonNode item : responses) {
                byte[] bytes = original(parent, item, names, references, inventory);
                Instant.parse(text(item, "collectedAt"));
                if ("raw/archive-prefix.tar.gz".equals(text(item, "file"))) {
                    long size = number(manifest, "archiveSize"), captured = number(manifest.path("sampling"), "prefixBytes");
                    if (captured != bytes.length || number(item, "status") != 206
                            || !text(item, "url").equals(provenance)
                            || !text(item, "contentRange").equals("bytes 0-" + (captured - 1) + "/" + size)
                            || captured >= size || captured > 32L * 1024 * 1024)
                        throw new IllegalStateException("Native HTTP prefix range differs");
                    prefix = bytes;
                }
            }
            Set<String> documents = new HashSet<>();
            List<ProductCapturedArchive.Member> archive = new ArrayList<>();
            for (JsonNode item : records) {
                byte[] bytes = original(parent, item, names, references, inventory);
                String sha = text(item, "sha256");
                if (!text(item, "documentVersion").equals("RAW_SHA256:" + sha))
                    throw new IllegalStateException("Native document version differs");
                JsonNode record = mapper.readTree(bytes);
                ProductCapturedJsonFields.verify(bytes, record, item, mapper);
                var publication = PUBLICATION.matcher(text(record, "publication_number"));
                if (!publication.matches() || !publication.group(2).equals(text(record, "date_published")))
                    throw new IllegalStateException("Native publication identity differs");
                LocalDate.parse(publication.group(2), DateTimeFormatter.BASIC_ISO_DATE);
                if (!text(record, "application_number").matches("[0-9]+")
                        || !documents.add(publication.group(1) + '\0' + text(item, "documentVersion")))
                    throw new IllegalStateException("Native document inventory duplicates or differs");
                archive.add(new ProductCapturedArchive.Member(text(item, "archiveMember"),
                        number(item, "tarHeaderOffset"), number(item, "tarContentOffset"), sha));
            }
            if (prefix == null) throw new IllegalStateException("Native archive prefix missing");
            ProductHupdPublisherEvidence.verify(manifest, parent, files, mapper);
            new ProductCapturedArchive().verify(prefix, archive);
            String manifestSha = ProductCapturedArchive.digest(manifestBytes);
            return new Result(source, providerVersion + ":" + collected, provenance, manifestSha,
                    HexFormat.of().formatHex(inventory.digest()), records.size(), names.size(), Set.copyOf(references));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | java.time.DateTimeException invalid) {
            throw new IllegalStateException("Native captured inventory cannot be verified", invalid);
        }
    }

    private byte[] original(String parent, JsonNode item, Set<String> names,
            Set<String> references, MessageDigest inventory) {
        String name = text(item, "file"), sha = text(item, "sha256");
        if (!names.add(name)) throw new IllegalStateException("Duplicate native original");
        byte[] bytes = files.verified(parent + name, sha);
        if (bytes.length != number(item, "bytes")) throw new IllegalStateException("Native original length differs");
        references.add(parent + name);
        inventory.update((name.length() + ":" + name + sha.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
        return bytes;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException("Native capture field missing: " + field);
        return value.asText();
    }
    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalStateException("Exact native capture number required: " + field);
        return value.longValue();
    }
}
