package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.ClueData;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AuthorizedEnforcementPackageValidator {
    public static final String SCHEMA_VERSION = "enforcement-authorized-package-v1";
    private static final List<String> DATA_FILES = List.of(
            "clues.json", "evidence.json", "rules.json", "right-statuses.json");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private final ObjectMapper mapper;

    public AuthorizedEnforcementPackageValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuthorizedEnforcementPackage load(Path directory) {
        try {
            Path root = directory.toAbsolutePath().normalize();
            require(Files.isDirectory(root), "PACKAGE_DIRECTORY_NOT_FOUND");
            Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
            bytes.put("manifest.json", read(root, "manifest.json"));
            for (String file : DATA_FILES) bytes.put(file, read(root, file));
            String manifestJson = new String(bytes.get("manifest.json"), StandardCharsets.UTF_8);
            AuthorizedEnforcementPackage.Manifest manifest = mapper.readValue(
                    manifestJson, AuthorizedEnforcementPackage.Manifest.class);
            validateManifest(manifest, bytes);
            List<ClueData> clues = mapper.readValue(bytes.get("clues.json"), new TypeReference<>() {});
            List<EvidenceData> evidence = mapper.readValue(bytes.get("evidence.json"), new TypeReference<>() {});
            List<AuthorizedEnforcementPackage.RuleRecord> rules = mapper.readValue(
                    bytes.get("rules.json"), new TypeReference<>() {});
            List<AuthorizedEnforcementPackage.RightStatusRecord> rights = mapper.readValue(
                    bytes.get("right-statuses.json"), new TypeReference<>() {});
            validateRecords(manifest, clues, evidence, rules, rights);
            String manifestHash = sha(bytes.get("manifest.json"));
            String packageHash = sha((manifestHash + DATA_FILES.stream()
                    .map(name -> name + ":" + sha(bytes.get(name))).sorted().reduce("", String::concat))
                    .getBytes(StandardCharsets.UTF_8));
            return new AuthorizedEnforcementPackage(manifest, manifestJson, manifestHash, packageHash,
                    List.copyOf(clues), List.copyOf(evidence), List.copyOf(rules), List.copyOf(rights));
        } catch (PackageValidationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PackageValidationException("PACKAGE_INVALID", failure);
        }
    }

    private static byte[] read(Path root, String name) throws Exception {
        Path file = root.resolve(name).normalize();
        require(file.getParent().equals(root) && Files.isRegularFile(file), "PACKAGE_FILE_MISSING:" + name);
        return Files.readAllBytes(file);
    }

    private static void validateManifest(AuthorizedEnforcementPackage.Manifest value, Map<String, byte[]> files) {
        require(value != null && SCHEMA_VERSION.equals(value.schemaVersion()), "PACKAGE_SCHEMA_UNSUPPORTED");
        required(value.batchId(), "batchId"); required(value.datasetId(), "datasetId");
        required(value.datasetVersion(), "datasetVersion"); required(value.sourceName(), "sourceName");
        required(value.sourceType(), "sourceType"); required(value.approvalDecisionId(), "approvalDecisionId");
        required(value.approvedBy(), "approvedBy"); required(value.allowedUse(), "allowedUse");
        required(value.recordScope(), "recordScope"); required(value.withdrawalContact(), "withdrawalContact");
        required(value.withdrawalProcess(), "withdrawalProcess");
        require(value.approvedAt() != null, "PACKAGE_APPROVAL_TIME_MISSING");
        require(Set.of("FIXED_UNTIL", "UNTIL_DEVELOPMENT_PILOT_END").contains(value.retentionPolicy()),
                "PACKAGE_RETENTION_POLICY_INVALID");
        boolean fixed = "FIXED_UNTIL".equals(value.retentionPolicy());
        require(fixed
                        ? value.retentionUntil() != null && value.retentionUntil().isAfter(value.approvedAt())
                        : value.retentionUntil() == null,
                "PACKAGE_RETENTION_INVALID");
        require(!value.externalModelAllowed(), "EXTERNAL_MODEL_NOT_ALLOWED");
        require(value.files() != null && value.files().keySet().equals(Set.copyOf(DATA_FILES)), "PACKAGE_FILE_SET_INVALID");
        for (String name : DATA_FILES) {
            AuthorizedEnforcementPackage.FileDeclaration declaration = value.files().get(name);
            require(declaration != null && declaration.recordCount() >= 0
                    && declaration.sha256() != null && SHA_256.matcher(declaration.sha256()).matches(),
                    "PACKAGE_FILE_DECLARATION_INVALID:" + name);
            require(declaration.sha256().equals(sha(files.get(name))), "PACKAGE_DIGEST_MISMATCH:" + name);
        }
    }

    private static void validateRecords(AuthorizedEnforcementPackage.Manifest manifest, List<ClueData> clues,
                                        List<EvidenceData> evidence,
                                        List<AuthorizedEnforcementPackage.RuleRecord> rules,
                                        List<AuthorizedEnforcementPackage.RightStatusRecord> rights) {
        require(clues != null && evidence != null && rules != null && rights != null, "PACKAGE_RECORDS_MISSING");
        require(manifest.files().get("clues.json").recordCount() == clues.size(), "PACKAGE_COUNT_MISMATCH:clues.json");
        require(manifest.files().get("evidence.json").recordCount() == evidence.size(), "PACKAGE_COUNT_MISMATCH:evidence.json");
        require(manifest.files().get("rules.json").recordCount() == rules.size(), "PACKAGE_COUNT_MISMATCH:rules.json");
        require(manifest.files().get("right-statuses.json").recordCount() == rights.size(), "PACKAGE_COUNT_MISMATCH:right-statuses.json");
        require(!clues.isEmpty() && !rules.isEmpty(), "PACKAGE_REQUIRED_RECORDS_MISSING");
        Set<String> clueIds = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (ClueData clue : clues) {
            validateSource(clue.source());
            require(clueIds.add(clue.source().sourceId()), "PACKAGE_DUPLICATE_CLUE");
            require(identities.add(identity(clue.source())), "PACKAGE_DUPLICATE_RECORD");
            clue.subjects().forEach(item -> validateSource(item.source()));
            clue.facts().forEach(item -> validateSource(item.source()));
            clue.times().forEach(item -> validateSource(item.source()));
            clue.places().forEach(item -> validateSource(item.source()));
            clue.evidenceMentions().forEach(item -> validateSource(item.source()));
            clue.conflicts().forEach(item -> item.candidates().forEach(candidate -> validateSource(candidate.source())));
        }
        Set<String> evidenceIds = new HashSet<>();
        for (EvidenceData item : evidence) {
            validateSource(item.source());
            require(clueIds.contains(item.relatedClueId()), "PACKAGE_EVIDENCE_CLUE_MISSING");
            require(evidenceIds.add(item.source().sourceId()), "PACKAGE_DUPLICATE_EVIDENCE_ID");
            require(Set.of("AVAILABLE", "SOURCE_UNAVAILABLE", "UNSUPPORTED_CONTENT", "WITHDRAWN")
                    .contains(item.availability()), "PACKAGE_EVIDENCE_STATUS_INVALID");
            require((item.collector() != null && !item.collector().isBlank())
                    || (item.collectionMissingReason() != null && !item.collectionMissingReason().isBlank()),
                    "PACKAGE_EVIDENCE_COLLECTION_MISSING");
            require(item.controlledResourceRef() != null
                    && (item.controlledResourceRef().startsWith("controlled://")
                    || item.controlledResourceRef().startsWith("object://")), "PACKAGE_RESOURCE_UNCONTROLLED");
            require(identities.add(identity(item.source())), "PACKAGE_DUPLICATE_RECORD");
        }
        for (AuthorizedEnforcementPackage.RuleRecord rule : rules) {
            validateSource(rule.source());
            require("PUBLISHED".equals(rule.status()), "RULE_NOT_PUBLISHED");
            required(rule.approvedBy(), "rule.approvedBy"); required(rule.applicability(), "rule.applicability");
            required(rule.ruleType(), "rule.ruleType"); required(rule.content(), "rule.content");
            required(rule.effectivityBasis(), "rule.effectivityBasis");
            require(rule.approvedAt() != null, "RULE_RELEASE_INCOMPLETE");
            require(Set.of("EXPLICIT_EFFECTIVE_DATE", "NO_EXPLICIT_EFFECTIVE_DATE")
                    .contains(rule.effectivityStatus()), "RULE_EFFECTIVITY_STATUS_INVALID");
            require("EXPLICIT_EFFECTIVE_DATE".equals(rule.effectivityStatus())
                            ? rule.effectiveFrom() != null
                            : rule.effectiveFrom() == null,
                    "RULE_EFFECTIVITY_DATE_INVALID");
            require(rule.contentSha256() != null && rule.contentSha256().equals(
                    sha(rule.content().getBytes(StandardCharsets.UTF_8))), "RULE_DIGEST_MISMATCH");
            require(identities.add(identity(rule.source())), "PACKAGE_DUPLICATE_RECORD");
        }
        for (AuthorizedEnforcementPackage.RightStatusRecord right : rights) {
            validateSource(right.source());
            require(clueIds.contains(right.clueId()), "PACKAGE_RIGHT_CLUE_MISSING");
            require(Set.of("AVAILABLE", "SOURCE_UNAVAILABLE", "WITHDRAWN").contains(right.availability()),
                    "PACKAGE_RIGHT_STATUS_INVALID");
            require(identities.add(identity(right.source())), "PACKAGE_DUPLICATE_RECORD");
        }
    }

    private static void validateSource(SourceLocator source) {
        require(source != null, "PACKAGE_SOURCE_MISSING");
        required(source.sourceSystem(), "source.system"); required(source.sourceId(), "source.id");
        required(source.version(), "source.version"); required(source.location(), "source.location");
        require(!source.location().matches("^[A-Za-z]:[\\\\/].*") && !source.location().startsWith("file:"),
                "PACKAGE_SOURCE_LOCATION_UNCONTROLLED");
    }

    private static String identity(SourceLocator source) { return source.sourceId() + "\u001f" + source.version(); }
    private static void required(String value, String field) { require(value != null && !value.isBlank(), "PACKAGE_FIELD_MISSING:" + field); }
    private static void require(boolean condition, String code) { if (!condition) throw new PackageValidationException(code); }
    static String sha(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class PackageValidationException extends RuntimeException {
        public PackageValidationException(String code) { super(code); }
        public PackageValidationException(String code, Throwable cause) { super(code, cause); }
    }
}
