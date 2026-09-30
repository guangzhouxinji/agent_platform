package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class SyntheticUsePolicy {
    public enum Use { QUERY, SAVE, INDEX, DISPLAY, DOWNLOAD, MODEL_USE, CROSS_DOMAIN }

    private final DevelopmentSubjectPolicy subjects;
    private final Instant validFrom;
    private final Instant validUntil;
    private final String organization;
    private final Set<String> principals;
    private final Map<String, Dataset> datasets;
    private final AtomicReference<Set<String>> withdrawn = new AtomicReference<>(Set.of());

    public SyntheticUsePolicy(ObjectMapper mapper, Path path, DevelopmentSubjectPolicy subjects) {
        this.subjects = subjects;
        try {
            JsonNode root = mapper.readTree(path.toFile());
            if (!"1.0".equals(text(root, "schemaVersion"))
                    || !"SYNTHETIC_DEV_ONLY".equals(text(root, "status"))
                    || root.path("manifestVersion").asInt() < 1 || !root.path("withdrawalRef").isNull()) {
                throw new IllegalArgumentException("Invalid synthetic development manifest");
            }
            text(root, "approvalRef");
            if (!"isolated-development-only".equals(text(root, "environment"))) {
                throw new IllegalArgumentException("Synthetic manifest is not isolated");
            }
            validFrom = OffsetDateTime.parse(text(root, "validFrom")).toInstant();
            validUntil = OffsetDateTime.parse(text(root, "validUntil")).toInstant();
            if (!validFrom.isBefore(validUntil)) throw new IllegalArgumentException("Invalid manifest interval");
            organization = text(root, "organizationRef");
            principals = strings(root.path("allowedPrincipalRefs"));
            if (principals.size() < 2) throw new IllegalArgumentException("Missing synthetic principals");
            Map<String, Dataset> parsed = new HashMap<>();
            for (JsonNode dataset : array(root, "datasets")) {
                if (!"SYNTHETIC".equals(text(dataset, "sourceKind"))
                        || !"ACTIVE".equals(text(dataset, "status"))) {
                    throw new IllegalArgumentException("Unapproved synthetic dataset");
                }
                text(dataset, "owner");
                Set<Use> uses = new HashSet<>();
                for (String name : strings(dataset.path("allowedActions"))) uses.add(Use.valueOf(name));
                if (uses.contains(Use.MODEL_USE) || uses.contains(Use.CROSS_DOMAIN)) {
                    throw new IllegalArgumentException("Synthetic data cannot leave the development boundary");
                }
                Set<String> fields = strings(dataset.path("fields"));
                JsonNode kinds = dataset.path("contentKinds");
                if (!kinds.isArray()) throw new IllegalArgumentException("Missing content kinds");
                Map<String, Record> records = new HashMap<>();
                for (JsonNode record : array(dataset, "records")) {
                    String id = text(record, "recordId");
                    String content = record.hasNonNull("claimText") ? text(record, "claimText") : null;
                    String digest = record.hasNonNull("contentSha256") ? text(record, "contentSha256") : null;
                    if ((content == null) != (digest == null)
                            || (content != null && !sha256(content).equals(digest))) {
                        throw new IllegalArgumentException("Synthetic content digest mismatch");
                    }
                    if (content != null && (!fields.contains("claimText") || !strings(kinds).contains("CLAIM_TEXT"))) {
                        throw new IllegalArgumentException("Undeclared synthetic content");
                    }
                    Record item = new Record(text(record, "sourceVersion"), text(record, "locator"), digest, content);
                    if (records.putIfAbsent(id, item) != null) throw new IllegalArgumentException("Duplicate synthetic record");
                }
                if (kinds.isEmpty() && (uses.contains(Use.SAVE) || uses.contains(Use.INDEX)
                        || uses.contains(Use.DISPLAY) || uses.contains(Use.DOWNLOAD))) {
                    throw new IllegalArgumentException("Metadata-only dataset cannot expose content");
                }
                Dataset item = new Dataset(Set.copyOf(uses), fields, Map.copyOf(records));
                if (parsed.putIfAbsent(text(dataset, "datasetId"), item) != null) {
                    throw new IllegalArgumentException("Duplicate synthetic dataset");
                }
            }
            if (parsed.isEmpty()) throw new IllegalArgumentException("Missing synthetic datasets");
            datasets = Map.copyOf(parsed);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("Cannot load validated synthetic development manifest", failure);
        }
    }

    public boolean allows(DevelopmentSubjectPolicy.Subject subject, String datasetId, String recordId,
                          String sourceVersion, String field, Use use, Instant now) {
        if (!subjects.current(subject, now) || subject.kind() != DevelopmentBoundaryProperties.Kind.HUMAN
                || !organization.equals(subject.organizationRef()) || !principals.contains(subject.stableSubjectId())
                || now.isBefore(validFrom) || !now.isBefore(validUntil) || withdrawn.get().contains(datasetId)) {
            return false;
        }
        Dataset dataset = datasets.get(datasetId);
        if (dataset == null || use == null || !dataset.uses.contains(use)
                || field == null || !dataset.fields.contains(field)) return false;
        Record record = dataset.records.get(recordId);
        return record != null && record.version.equals(sourceVersion)
                && (record.digest != null || use == Use.QUERY);
    }

    public void withdraw(String datasetId) {
        withdrawn.updateAndGet(current -> {
            Set<String> next = new HashSet<>(current);
            next.add(datasetId);
            return Set.copyOf(next);
        });
    }

    public DevTextResultStore.Source claim(DevelopmentSubjectPolicy.Subject subject, String recordId,
                                            String version, String expectedDigest, boolean query, boolean save,
                                            boolean display, Instant now) {
        String dataset = "DEV-CON-SYN-01";
        if ((query && !allows(subject, dataset, recordId, version, "claimText", Use.QUERY, now))
                || (save && !allows(subject, dataset, recordId, version, "claimText", Use.SAVE, now))
                || (display && !allows(subject, dataset, recordId, version, "claimText", Use.DISPLAY, now))) {
            return null;
        }
        if (!query && !save && !display) return null;
        Record record = datasets.get(dataset).records.get(recordId);
        if (record == null || record.content == null || !record.version.equals(version)
                || !sha256(record.content).equals(record.digest)
                || (expectedDigest != null && !expectedDigest.equals(record.digest))) return null;
        return new DevTextResultStore.Source(recordId, record.version, record.digest, record.locator, record.content);
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray() || value.isEmpty()) throw new IllegalArgumentException("Missing manifest list: " + field);
        return value;
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) throw new IllegalArgumentException("Missing manifest values");
        Set<String> values = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || !values.add(value.asText())) {
                throw new IllegalArgumentException("Invalid manifest value");
            }
        }
        return Set.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing manifest field: " + field);
        }
        return value.asText();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Dataset(Set<Use> uses, Set<String> fields, Map<String, Record> records) { }
    private record Record(String version, String locator, String digest, String content) { }
}
