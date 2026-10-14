package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Binds captured publisher metadata to the archive identity, without adjudicating license statements. */
final class ProductHupdPublisherEvidence {
    static void verify(JsonNode manifest, String parent, ProductSourceEvidenceFiles files,
            ObjectMapper mapper) throws java.io.IOException {
        Map<String, JsonNode> responses = new LinkedHashMap<>();
        for (JsonNode response : manifest.path("responses")) responses.put(response.path("file").asText(), response);
        var required = List.of("raw/author-commit.json", "raw/author-README.md", "raw/author-code-LICENSE",
                "raw/hosted-metadata.json", "raw/hosted-README.md", "raw/archive-tree.json", "raw/archive-prefix.tar.gz");
        if (!responses.keySet().containsAll(required)) throw new IllegalStateException("Native publisher originals missing");
        JsonNode hosted = json("raw/hosted-metadata.json", parent, responses, files, mapper);
        JsonNode author = json("raw/author-commit.json", parent, responses, files, mapper);
        String version = manifest.path("sourceVersion").asText(), authorVersion = author.path("sha").asText();
        if (!hosted.path("sha").asText().equals(version) || !authorVersion.matches("[0-9a-f]{40}")
                || !authorVersion.equals(manifest.path("licenseEvidence").path("authorRevision").asText())
                || !hosted.path("cardData").path("license").equals(manifest.path("licenseEvidence").path("hostedDataStatement")))
            throw new IllegalStateException("Native publisher version or declaration binding differs");
        Map<String,String> urls = Map.of(
                "raw/author-commit.json", "https://api.github.com/repos/suzgunmirac/hupd/commits/main",
                "raw/author-README.md", "https://raw.githubusercontent.com/suzgunmirac/hupd/" + authorVersion + "/README.md",
                "raw/author-code-LICENSE", "https://raw.githubusercontent.com/suzgunmirac/hupd/" + authorVersion + "/LICENSE",
                "raw/hosted-metadata.json", "https://huggingface.co/api/datasets/HUPD/hupd",
                "raw/hosted-README.md", "https://huggingface.co/datasets/HUPD/hupd/resolve/" + version + "/README.md",
                "raw/archive-tree.json", "https://huggingface.co/api/datasets/HUPD/hupd/tree/" + version + "/data?recursive=false&expand=true");
        for (var entry : urls.entrySet()) {
            JsonNode response = responses.get(entry.getKey());
            if (!entry.getValue().equals(response.path("url").asText()) || !response.path("status").isIntegralNumber()
                    || response.path("status").asInt() != 200) throw new IllegalStateException("Native publisher response binding differs");
        }
        JsonNode tree = json("raw/archive-tree.json", parent, responses, files, mapper);
        if (!tree.isArray()) throw new IllegalStateException("Native archive tree missing");
        List<JsonNode> archive = new ArrayList<>();
        for (JsonNode item : tree)
            if (item.path("path").asText().equals(manifest.path("archivePath").asText())) archive.add(item);
        if (archive.size() != 1 || !archive.getFirst().path("size").equals(manifest.path("archiveSize"))
                || !archive.getFirst().path("lfs").path("oid").equals(manifest.path("publishedArchiveSha256")))
            throw new IllegalStateException("Native published archive identity differs");
    }
    private static JsonNode json(String name, String parent, Map<String,JsonNode> responses,
            ProductSourceEvidenceFiles files, ObjectMapper mapper) throws java.io.IOException {
        return mapper.readTree(files.verified(parent + name, responses.get(name).path("sha256").asText()));
    }
}
