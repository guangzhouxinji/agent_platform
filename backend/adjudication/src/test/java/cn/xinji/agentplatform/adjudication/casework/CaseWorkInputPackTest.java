package cn.xinji.agentplatform.adjudication.casework;

import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** This input pack is project-authored engineering data, not a real case or approved law source. */
class CaseWorkInputPackTest {
    @Test void explicitPackConsumptionKeepsUnknownLegalContextAndEvidenceAsGaps() throws Exception {
        var root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("data/adjudication/task-first-loop-input-v1/manifest.json"))) root = root.getParent();
        assertNotNull(root, "Workspace engineering input pack must exist");
        var directory = root.resolve("data/adjudication/task-first-loop-input-v1");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var manifest = mapper.readTree(Files.readString(directory.resolve("manifest.json")));
        var records = Files.readString(directory.resolve("records.json"));
        assertEquals(manifest.path("files").get(0).path("sha256").asText(), sha256(records));
        assertFalse(manifest.path("realBusinessData").asBoolean());
        var packet = mapper.readTree(records); assertTrue(packet.path("notRealCase").asBoolean());
        var input = mapper.treeToValue(packet.path("caseInput"), CaseInput.class);
        assertEquals(input.source().sha256(), sha256(packet.path("caseOriginalText").asText()));
        var now = Instant.parse("2026-10-02T12:00:00Z");
        var state = open(input, "mechanical-test-actor-not-identity-evidence", now);
        for (var material : packet.path("materials")) state = addMaterial(state, state.version(),
                mapper.treeToValue(material, Material.class), "mechanical-test-actor-not-identity-evidence", now);
        state = selectClaim(state, state.version(), mapper.treeToValue(packet.path("claim"), Claim.class),
                "mechanical-test-actor-not-identity-evidence", now);
        state = generateReport(state, state.version(), Policy.standard(), "mechanical-test-actor-not-identity-evidence", now);
        var report = state.report(1);
        assertEquals(2, state.materials().size()); assertFalse(report.complete());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code().equals("LEGAL_CONTEXT_MISSING")));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code().equals("LEGAL_SELECTION_MISSING")));
        assertEquals(2, report.diagnostics().stream().filter(d -> d.code().equals("ELEMENT_SUPPORT_MISSING")).count());
        assertEquals(ReviewState.DRAFT, report.reviewState()); assertTrue(report.reviews().isEmpty());
        var finalState = state;
        assertThrows(IllegalArgumentException.class, () -> submit(finalState, finalState.version(), 1, report.contentSha256(),
                "mechanical-test-actor-not-identity-evidence", now));
    }
}
