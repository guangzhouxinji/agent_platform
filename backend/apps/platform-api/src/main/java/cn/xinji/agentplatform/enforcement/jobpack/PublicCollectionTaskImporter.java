package cn.xinji.agentplatform.enforcement.jobpack;

import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.PublicInput;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
final class PublicCollectionTaskImporter implements ApplicationRunner {
    private final RealBusinessCollectionTaskStore tasks;
    private final MaterialFiles files;
    private final ObjectMapper json;
    private final String candidatesPath;

    PublicCollectionTaskImporter(RealBusinessCollectionTaskStore tasks, MaterialFiles files, ObjectMapper json,
            @Value("${enforcement.v2.job-pack.public-candidates-path:}") String candidatesPath) {
        this.tasks = tasks; this.files = files; this.json = json; this.candidatesPath = candidatesPath;
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        if (candidatesPath == null || candidatesPath.isBlank()) return;
        Path manifest = Path.of(candidatesPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("Public job candidates unavailable");
        JsonNode root = json.readTree(Files.readAllBytes(manifest));
        for (JsonNode candidate : root.path("candidates")) importCandidate(manifest.getParent(), candidate);
    }

    private void importCandidate(Path root, JsonNode candidate) throws Exception {
        if (!"PUBLIC_DATA_READY".equals(candidate.path("publicDataState").asText())
                || !candidate.path("collection").path("allowed").asBoolean(false)
                || !"COLLECTION_ALLOWED".equals(candidate.path("collection").path("decision").asText())) return;
        JsonNode source = candidate.path("publicSourceFacts");
        String candidateId = required(candidate, "candidateId");
        String taskId = taskId(candidateId);
        if (tasks.find(taskId).isPresent()) return;
        Path raw = root.resolve(required(source, "rawFile")).normalize();
        if (!raw.startsWith(root) || !Files.isRegularFile(raw)) throw new IllegalStateException("Public original unavailable");
        byte[] bytes = Files.readAllBytes(raw);
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!digest.equals(required(source, "rawSha256"))) throw new IllegalStateException("Public original digest mismatch");
        var staged = files.stage(bytes);
        var publicFacts = new LinkedHashMap<String, String>();
        JsonNode facts = candidate.path("publicBusinessFacts");
        facts.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue().path("value");
            if (value.isTextual() && !value.asText().isBlank()) publicFacts.put(entry.getKey(), value.asText());
        });
        publicFacts.put("contentLocator", required(source, "contentLocator"));
        var input = new PublicInput(candidateId, required(source, "originalUrl"), required(source, "sourceName"),
                source.path("publishedAt").asText(""), required(source, "capturedAt"), digest, staged.key(),
                raw.getFileName().toString(), required(candidate, "collectionBatchId"), "COLLECTION_ALLOWED",
                Map.copyOf(publicFacts), null);
        var seed = new RealBusinessCollectionTask.Seed(taskId, candidateId, packageId(candidateId),
                required(source, "title"), required(candidate, "collectionBatchId"), input);
        if (!tasks.seed(seed)) files.discard(staged.key());
    }

    private static String taskId(String candidateId) {
        if ("ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION".equals(candidateId))
            return "ENF-RBC-CNIPA-GUIDANCE-CASE-1";
        return "ENF-RBC-" + suffix(candidateId);
    }
    private static String packageId(String candidateId) {
        if ("ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION".equals(candidateId))
            return "ENF-JOB-CNIPA-GUIDANCE-CASE-1";
        return "ENF-JOB-" + suffix(candidateId);
    }
    private static String suffix(String candidateId) {
        String value = candidateId.replaceFirst("^ENF-PUBLIC-", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-").replaceAll("(^-|-$)", "");
        return value.substring(0, Math.min(value.length(), 90));
    }
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Missing public candidate field: " + field);
        return value;
    }
}
