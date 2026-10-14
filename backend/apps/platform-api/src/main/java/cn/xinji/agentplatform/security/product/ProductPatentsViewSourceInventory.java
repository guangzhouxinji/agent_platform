package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static cn.xinji.agentplatform.security.product.ProductPatentsViewRecord.*;

/** Actual native source integrity. No method in this adapter enables a source or issues a grant. */
final class ProductPatentsViewSourceInventory {
    private static final java.util.regex.Pattern ARTIFACT = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,399}");
    private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final java.util.regex.Pattern MD5 = java.util.regex.Pattern.compile("md5:[0-9a-f]{32}");
    static final String FORMAT = "PATENTSVIEW_TSV_CAPTURE/v2", SOURCE = "PATENTSVIEW_ZENODO_2024";
    static final String PROVENANCE = "https://zenodo.org/records/15062183";
    record Original(String id, String version, String ref, String sha, byte[] bytes, JsonNode record) { }
    record Result(String sourceId, String sourceVersion, String provenance, String recordsSha,
                  String inventorySha, long records, long originals, Set<String> references,
                  Map<String,Original> documents) { }
    private final ProductSourceEvidenceFiles files;
    private final ObjectMapper mapper;
    ProductPatentsViewSourceInventory(ProductSourceEvidenceFiles files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    Result inspect(String ref, byte[] bytes) { return inspect(ref, bytes, true); }
    /** Only from an already VERIFIED/currently authorized or historical recovery snapshot. */
    Result selectedContent(String ref, byte[] bytes) { return inspect(ref, bytes, false); }

    private Result inspect(String ref, byte[] bytes, boolean deep) {
        try {
            JsonNode manifest = mapper.readTree(bytes);
            if (!FORMAT.equals(text(manifest,"schemaVersion")) || !SOURCE.equals(text(manifest,"sourceId"))
                    || !"REAL_CANDIDATE".equals(text(manifest,"status"))
                    || !manifest.path("productEnabled").isBoolean() || manifest.path("productEnabled").booleanValue()
                    || !"REPOSITORY_CC_BY_4_0_ASSERTION".equals(text(manifest,"rightsEvidenceStatus")))
                throw new IllegalStateException("Native capture boundaries differ");
            String collected = text(manifest,"collectedAt");
            Instant.parse(collected);
            JsonNode sampling = manifest.path("sampling"), artifacts = manifest.path("artifacts"), records = manifest.path("records");
            if (!"FIRST_DISTINCT_GRANT_IDS_IN_FULL_CLAIMS_TSV".equals(text(sampling,"method"))
                    || !records.isArray() || records.isEmpty() || records.size() > 100
                    || number(sampling,"limit") != records.size() || !artifacts.isArray() || artifacts.isEmpty())
                throw new IllegalStateException("Native capture inventory incomplete");
            for (String flag : List.of("representative", "professionalQrels", "queryResponse"))
                if (!sampling.path(flag).isBoolean() || sampling.path(flag).booleanValue())
                    throw new IllegalStateException("Native sample does not imply benchmark or query response");
            String parent = Path.of(ref).getParent() == null ? "" : Path.of(ref).getParent().toString().replace('\\','/') + "/";
            Map<String,JsonNode> declarations = new TreeMap<>();
            List<ProductSourceInventoryReads.Reference> reads = new ArrayList<>();
            Set<String> references = new LinkedHashSet<>();
            references.add(ref);
            var inventory = java.security.MessageDigest.getInstance("SHA-256");
            for (JsonNode item : artifacts) {
                String name = text(item,"file"), sha = text(item,"sha256");
                if (!ARTIFACT.matcher(name).matches() || name.contains("..")
                        || !SHA.matcher(sha).matches() || declarations.put(name,item) != null)
                    throw new IllegalStateException("Invalid/duplicate native artifact");
                long size = number(item,"bytes");
                references.add(parent + name);
                if (name.startsWith("raw/archives/")) files.archivePresent(parent + name,sha,size);
                else {
                    reads.add(new ProductSourceInventoryReads.Reference(name,parent + name,sha,size));
                }
            }
            Map<String,byte[]> originals = ProductSourceInventoryReads.read(files,reads);
            for (var entry : declarations.entrySet()) {
                JsonNode item = entry.getValue();
                inventory.update((entry.getKey() + "\0" + text(item,"sha256") + "\0"
                        + number(item,"bytes") + "\n").getBytes(StandardCharsets.UTF_8));
            }
            JsonNode archives = manifest.path("archives");
            Map<String,List<ProductPatentsViewArchiveProof.Segment>> segments = new HashMap<>();
            Set<String> used = new HashSet<>();
            String provider = publisher(archives,"claims","15062183","g_claims_1976.tsv", originals,declarations,used)
                    + ":" + publisher(archives,"metadata","15783125","g_patent.tsv",originals,declarations,used);
            if (!provider.equals(text(manifest,"sourceVersion"))) throw new IllegalStateException("Native repository version differs");
            segments.put("claims",new ArrayList<>()); segments.put("metadata",new ArrayList<>());
            Map<String,Original> documents = new LinkedHashMap<>();
            for (JsonNode item : records) {
                String grant = text(item,"originalGrantId"), file = text(item,"file"), sha = text(item,"sha256");
                byte[] raw = originals.get(file);
                String id = "US_GRANT:" + grant, version = "RAW_SHA256:" + sha;
                if (raw == null || !file.equals("records/" + grant + ".json")
                        || !sha.equals(ProductCapturedArchive.digest(raw)) || number(item,"bytes") != raw.length
                        || !id.equals(text(item,"documentId")) || !version.equals(text(item,"documentVersion"))
                        || !item.path("fields").equals(mapper.valueToTree(List.of("TITLE","CLAIMS"))))
                    throw new IllegalStateException("Native document identity/content differs");
                JsonNode record = mapper.readTree(raw);
                ProductPatentsViewRecord.verify(record,grant,originals,archives,segments);
                if (!used.add(file) || documents.put(id,new Original(id,version,parent + file,sha,raw,record)) != null)
                    throw new IllegalStateException("Duplicate native document");
                used.add(text(record.path("metadata").path("sourceLocator"),"file"));
                for (JsonNode claim : record.path("claims")) used.add(text(claim.path("sourceLocator"),"file"));
            }
            if (!originals.containsKey("raw/source-records/cc-by-4-legalcode.html"))
                throw new IllegalStateException("Native license original missing");
            used.add("raw/source-records/cc-by-4-legalcode.html");
            if (!used.equals(declarations.keySet())) throw new IllegalStateException("Unbound/missing native artifact");
            if (deep) for (String kind : List.of("claims","metadata")) {
                JsonNode declaration = archives.path(kind);
                files.verifiedStream(parent + text(declaration,"file"),text(declaration,"sha256"),number(declaration,"bytes"),
                        stream -> ProductPatentsViewArchiveProof.verify(stream,text(declaration,"archiveMember"),
                                segments.get(kind),text(declaration,"publishedChecksum").substring(4)));
            }
            return new Result(SOURCE,"CAPTURE_SHA256:" + ProductCapturedArchive.digest(bytes),PROVENANCE,ProductCapturedArchive.digest(bytes),
                    java.util.HexFormat.of().formatHex(inventory.digest()),documents.size(),declarations.size(),
                    Set.copyOf(references),Collections.unmodifiableMap(documents));
        } catch (Exception failure) { throw new IllegalStateException("PatentsView source inventory invalid", failure); }
    }

    private String publisher(JsonNode archives, String kind, String recordId, String member,
            Map<String,byte[]> originals, Map<String,JsonNode> declarations, Set<String> used) throws java.io.IOException {
        JsonNode declaration = archives.path(kind);
        String name = "raw/archives/" + member + ".zip", recordName = "raw/source-records/record-" + recordId + ".json";
        byte[] original = originals.get(recordName);
        if (original == null || !recordId.equals(text(declaration,"recordId")) || !name.equals(text(declaration,"file"))
                || !member.equals(text(declaration,"archiveMember")) || number(declaration,"bytes") > 512L * 1024 * 1024
                || !ProductCapturedArchive.digest(original).equals(text(declaration,"recordSha256")))
            throw new IllegalStateException("Native publisher binding differs");
        JsonNode record = mapper.readTree(original), artifact = declarations.get(name);
        if (!record.path("id").asText().equals(recordId) || !"cc-by-4.0".equals(record.path("metadata").path("license").path("id").asText())
                || artifact == null || number(artifact,"bytes") != number(declaration,"bytes")
                || !text(artifact,"sha256").equals(text(declaration,"sha256")))
            throw new IllegalStateException("Native license/archive declaration differs");
        int matches = 0;
        for (JsonNode file : record.path("files")) if ((member + ".zip").equals(file.path("key").asText())) {
            matches++;
            if (number(file,"size") != number(declaration,"bytes")
                    || !text(file,"checksum").equals(text(declaration,"publishedChecksum"))
                    || !MD5.matcher(text(file,"checksum")).matches()
                    || !text(file.path("links"),"self").equals(text(declaration,"originalUrl"))
                    || !text(declaration,"originalUrl").equals("https://zenodo.org/api/records/" + recordId + "/files/" + member + ".zip/content"))
                throw new IllegalStateException("Native public archive evidence differs");
        }
        if (matches != 1 || !used.add(name) || !used.add(recordName)) throw new IllegalStateException("Native archive not uniquely bound");
        return text(declaration,"recordSha256");
    }
}
