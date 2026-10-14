package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Offline ENGINEERING input integrity only. Never substitutes for the fourteen real DM8 runtime assertions. */
class DocumentRuleRegressionEnvironmentSupportTest {
    private final ObjectMapper json = new ObjectMapper();
    private Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("AGENTS.md"))) p = p.getParent();
        assertNotNull(p, "Repository root required");
        return p;
    }
    private JsonNode audit() throws Exception {
        // This support suite requires a freshly generated engineering audit, not a business result fixture.
        String supplied = System.getProperty("enf.regression.audit",
                root().resolve("docs/evidence/enforcement-regression-repair-20261007/offline-audit-02/retained-input-audit.json").toString());
        Path file = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(file.startsWith(root().resolve("docs/evidence/enforcement-regression-repair-20261007")));
        return json.readTree(Files.readAllBytes(file));
    }
    @Test void currentOriginalStillContainsTwentyUnfilteredErrorsAndFourteenOwnedErrors() throws Exception {
        var original = json.readTree(Files.readAllBytes(root().resolve("docs/evidence/single-database-engineering-20261007/full-environment-errors.json")));
        assertEquals("CURRENT_UNFILTERED_ERRORS_NOT_FILTERED_OUT", original.path("scope").asText());
        assertEquals(20, original.path("errors").size());
        int count = 0;
        for (var error : original.path("errors")) if (error.path("suite").asText().contains(".documentrule.")) count++;
        assertEquals(14, count);
        assertEquals(14, audit().path("ownedErrors").asInt());
    }
    @Test void everyRetainedOriginalAndArtifactStillMatchesItsObservedSha() throws Exception {
        var audit = audit();
        assertEquals(0, audit.path("problems").size());
        assertTrue(audit.path("files").size() >= 100);
        for (var file : audit.path("files")) {
            assertTrue(file.path("readable").asBoolean(), file.toString());
            byte[] raw = Files.readAllBytes(Path.of(file.path("path").asText()));
            assertEquals(file.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)), file.path("path").asText());
            assertEquals(file.path("bytes").asLong(), raw.length);
        }
    }
    @Test void allNineNegativeScenarioInputsRemainOriginalBrowserResults() throws Exception {
        var audit = audit();
        var manifest = json.readTree(Files.readAllBytes(Path.of(audit.path("candidateEnvironment").path("ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST").asText())));
        Set<String> expected = Set.of("unknown_path", "wrong_type", "missing_party", "missing_date", "missing_material", "no_interview", "unconfirmed_amount", "unconfirmed_legal", "unreadable_source");
        var actual = new HashSet<String>();
        manifest.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
        for (String scenario : expected) {
            Path file = Path.of(manifest.path(scenario).asText());
            var browser = json.readTree(Files.readAllBytes(file));
            assertTrue(browser.path("passed").asBoolean());
            assertEquals(scenario, browser.path("scenario").asText());
            assertFalse(browser.path("ruleId").asText().isBlank());
            assertTrue(browser.path("records").size() > 0);
        }
    }
    @Test void historicalRecoveryCountsComeFromRawOutputRatherThanAnotherScenario() throws Exception {
        var raw = json.readTree(Files.readAllBytes(root().resolve("docs/evidence/enforcement-followup-final-20260930/ENF13-FINAL-20261001-101/recovery-source-post-reconciliation.json")));
        var candidate = audit().path("candidateEnvironment");
        assertEquals(raw.path("rules").size(), Integer.parseInt(candidate.path("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES").asText()));
        assertEquals(raw.path("attempts").size(), Integer.parseInt(candidate.path("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS").asText()));
        assertEquals("PENDING", raw.path("businessAcceptance").asText());
    }
    @Test void candidateInputsNeverProvideDatabaseIdentityOrApproval() throws Exception {
        var audit = audit();
        assertEquals("OFFLINE_RETAINED_INPUT_INTEGRITY_NOT_RUNTIME_NOT_BUSINESS_ACCEPTANCE", audit.path("scope").asText());
        assertEquals("NOT_CLAIMED", audit.path("businessAcceptance").asText());
        assertTrue(audit.path("candidateEnvironmentNotApprovedForCurrentProduct").asBoolean());
        assertEquals(0, audit.path("networkConnections").asInt());
        assertEquals(0, audit.path("databaseMutations").asInt());
        assertFalse(audit.path("candidateEnvironment").has("ENF_ACCEPTANCE_BUSINESS_URL"));
        assertFalse(audit.path("candidateEnvironment").has("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertTrue(audit.path("requiredForRuntime").size() >= 4);
    }
}
