package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.ClueData;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort.EvidenceData;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorizedEnforcementPackageValidatorTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    @TempDir Path directory;

    @Test void acceptsCompleteAuthorizedPackage() throws Exception {
        writePackage("PUBLISHED", "controlled://evidence/E-1");

        AuthorizedEnforcementPackage value = new AuthorizedEnforcementPackageValidator(mapper).load(directory);

        assertThat(value.manifest().datasetId()).isEqualTo("ENF-AUTHORIZED-TEST");
        assertThat(value.recordCount()).isEqualTo(4);
        assertThat(value.rules()).singleElement().satisfies(rule ->
                assertThat(rule.status()).isEqualTo("PUBLISHED"));
        assertThat(value.packageSha256()).hasSize(64);
    }

    @Test void rejectsTamperedFileAgainstManifestDigest() throws Exception {
        writePackage("PUBLISHED", "controlled://evidence/E-1");
        Files.writeString(directory.resolve("evidence.json"), "[]", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("PACKAGE_DIGEST_MISMATCH:evidence.json");
    }

    @Test void rejectsUnpublishedRulesAndUncontrolledEvidence() throws Exception {
        writePackage("DRAFT", "controlled://evidence/E-1");
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("RULE_NOT_PUBLISHED");

        writePackage("PUBLISHED", "C:\\evidence\\E-1.pdf");
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("PACKAGE_RESOURCE_UNCONTROLLED");
    }

    @Test void acceptsEventRetentionAndRejectsPolicyDateMismatch() throws Exception {
        writePackage("PUBLISHED", "controlled://evidence/E-1", "UNTIL_DEVELOPMENT_PILOT_END", null);
        AuthorizedEnforcementPackage value = new AuthorizedEnforcementPackageValidator(mapper).load(directory);
        assertThat(value.manifest().retentionPolicy()).isEqualTo("UNTIL_DEVELOPMENT_PILOT_END");
        assertThat(value.manifest().retentionUntil()).isNull();

        writePackage("PUBLISHED", "controlled://evidence/E-1", "UNTIL_DEVELOPMENT_PILOT_END",
                Instant.parse("2027-09-10T00:00:00Z"));
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("PACKAGE_RETENTION_INVALID");

        writePackage("PUBLISHED", "controlled://evidence/E-1", "FIXED_UNTIL", null);
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("PACKAGE_RETENTION_INVALID");
    }

    @Test void preservesMissingRuleEffectiveDateWithoutInventingOne() throws Exception {
        writePackage("PUBLISHED", "controlled://evidence/E-1", "UNTIL_DEVELOPMENT_PILOT_END", null,
                null, "NO_EXPLICIT_EFFECTIVE_DATE", "official source does not state an effective date");
        AuthorizedEnforcementPackage value = new AuthorizedEnforcementPackageValidator(mapper).load(directory);
        assertThat(value.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.effectiveFrom()).isNull();
            assertThat(rule.effectivityStatus()).isEqualTo("NO_EXPLICIT_EFFECTIVE_DATE");
        });

        writePackage("PUBLISHED", "controlled://evidence/E-1", "FIXED_UNTIL",
                Instant.parse("2027-09-10T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z"),
                "NO_EXPLICIT_EFFECTIVE_DATE", "official source does not state an effective date");
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("RULE_EFFECTIVITY_DATE_INVALID");

        writePackage("PUBLISHED", "controlled://evidence/E-1", "FIXED_UNTIL",
                Instant.parse("2027-09-10T00:00:00Z"), null,
                "EXPLICIT_EFFECTIVE_DATE", "source states effective date");
        assertThatThrownBy(() -> new AuthorizedEnforcementPackageValidator(mapper).load(directory))
                .hasMessage("RULE_EFFECTIVITY_DATE_INVALID");
    }

    private void writePackage(String ruleStatus, String controlledRef) throws Exception {
        writePackage(ruleStatus, controlledRef, "FIXED_UNTIL", Instant.parse("2027-09-10T00:00:00Z"));
    }

    private void writePackage(String ruleStatus, String controlledRef, String retentionPolicy,
                              Instant retentionUntil) throws Exception {
        writePackage(ruleStatus, controlledRef, retentionPolicy, retentionUntil,
                Instant.parse("2026-09-11T00:00:00Z"), "EXPLICIT_EFFECTIVE_DATE",
                "source states effective date");
    }

    private void writePackage(String ruleStatus, String controlledRef, String retentionPolicy,
                              Instant retentionUntil, Instant ruleEffectiveFrom,
                              String effectivityStatus, String effectivityBasis) throws Exception {
        SourceLocator clueSource = new SourceLocator("authorized-clue-system", "CLUE-1", "v1", "record:1");
        ClueData clue = new ClueData(clueSource, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        EvidenceData evidence = new EvidenceData(
                new SourceLocator("authorized-evidence-system", "EVIDENCE-1", "v1", "record:1"),
                controlledRef, "CLUE-1", "collector-1", null, "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z", "2026-09-03T00:00:00Z", "AVAILABLE", "identity",
                "SUPPORT", "source states the candidate relationship", null, null);
        String ruleContent = "type=REQUIRE_HUMAN_REVIEW";
        var rule = new AuthorizedEnforcementPackage.RuleRecord(
                new SourceLocator("authorized-rule-system", "RULE-1", "v1", "rule:1"), ruleStatus,
                "professional-owner", Instant.parse("2026-09-10T00:00:00Z"),
                ruleEffectiveFrom, effectivityStatus, effectivityBasis,
                "test scope", "DETERMINISTIC_CHECKLIST",
                ruleContent, AuthorizedEnforcementPackageValidator.sha(ruleContent.getBytes(StandardCharsets.UTF_8)));
        var right = new AuthorizedEnforcementPackage.RightStatusRecord(
                new SourceLocator("authorized-right-system", "RIGHT-1", "v1", "record:1"), "CLUE-1", "AVAILABLE");

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("clues.json", mapper.writeValueAsBytes(List.of(clue)));
        files.put("evidence.json", mapper.writeValueAsBytes(List.of(evidence)));
        files.put("rules.json", mapper.writeValueAsBytes(List.of(rule)));
        files.put("right-statuses.json", mapper.writeValueAsBytes(List.of(right)));
        Map<String, AuthorizedEnforcementPackage.FileDeclaration> declarations = new LinkedHashMap<>();
        files.forEach((name, bytes) -> declarations.put(name,
                new AuthorizedEnforcementPackage.FileDeclaration(AuthorizedEnforcementPackageValidator.sha(bytes), 1)));
        var manifest = new AuthorizedEnforcementPackage.Manifest(
                AuthorizedEnforcementPackageValidator.SCHEMA_VERSION, "ENF-AUTH-BATCH-1", "ENF-AUTHORIZED-TEST", "v1",
                "authorized local handoff", "AUTHORIZED_LOCAL_HANDOFF", "APPROVAL-1", "project-owner",
                Instant.parse("2026-09-10T00:00:00Z"), "internal enforcement assistance", "one clue and its evidence",
                retentionPolicy, retentionUntil, "data-owner@example.invalid", "disable and withdraw index",
                false, declarations);
        files.forEach((name, bytes) -> {
            try { Files.write(directory.resolve(name), bytes); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        Files.write(directory.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));
    }
}
