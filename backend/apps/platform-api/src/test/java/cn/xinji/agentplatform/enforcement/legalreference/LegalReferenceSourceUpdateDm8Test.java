package cn.xinji.agentplatform.enforcement.legalreference;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only reconciliation of a real browser-produced runtime chain; no fixture target or access stub. */
@EnabledIfEnvironmentVariable(named = "ENF_LEGAL_L06_REFERENCE_ID", matches = ".+")
class LegalReferenceSourceUpdateDm8Test {
    @Test void browserProducedTargetReferenceOriginalReceiptsAndEventsReconcile() throws Exception {
        var db = new JdbcTemplate(new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),
                required("ENF_ACCEPTANCE_BUSINESS_USER"), required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var json = new ObjectMapper().findAndRegisterModules();
        String id = required("ENF_LEGAL_L06_REFERENCE_ID");
        var references = db.query("SELECT payload FROM enf9_legal_reference WHERE reference_id=? ORDER BY revision",
                (rs, row) -> { try { return json.readValue(rs.getString(1), LegalReferenceContract.ReferenceRevision.class); }
                    catch (Exception e) { throw new IllegalStateException(e); } }, id);
        assertTrue(required("ENF_ACCEPTANCE_BUSINESS_URL").startsWith("jdbc:dm://"));
        assertEquals(2, references.size(), "bind and actual changed-source recheck only");
        assertEquals(List.of(1L,2L),references.stream().map(LegalReferenceContract.ReferenceRevision::revision).toList());
        assertEquals("2010-01-09",references.getFirst().sourceVersion());
        assertEquals("2023-12-11",references.getLast().sourceVersion());
        assertEquals(LegalReferenceContract.BindingState.ACTIVE,references.getFirst().bindingState());
        assertEquals(LegalReferenceContract.BindingState.NEEDS_REVIEW,references.getLast().bindingState());
        assertTrue(references.stream().allMatch(r->r.reviewState()==LegalReferenceContract.ReviewState.PENDING));
        assertNotEquals(references.getFirst().verbatimText(),references.getLast().verbatimText());
        assertEquals(0,references.getFirst().previousRevision());
        assertEquals(1,references.getLast().previousRevision());
        var source = new FileLegalKnowledgeSource(Path.of(required("ENF_LEGAL_L06_CATALOG")), json);
        var target = new LegalReferenceTargetStore(db, json);
        for (var ref : references) {
            var original = source.exact(ref.sourceId(), ref.sourceVersion(), ref.candidateId());
            assertEquals(original.verbatimText(), ref.verbatimText());
            assertEquals(original.verbatimSha256(), ref.verbatimSha256());
            assertEquals(ref.targetDigest(), target.require(required("ENF_ACCEPTANCE_SUBJECT"), ref.organization(),
                    ref.targetType(), ref.targetId(), ref.targetRevision(), ref.targetAnchor(), "LEGAL_REFERENCE"));
            assertFalse(ref.gaps().isEmpty(), "Technical data must not acquire legal applicability");
        }
        var receipts = db.queryForList("SELECT request_key,actor_ref,operation,object_revision FROM enf9_legal_receipt WHERE object_id=? ORDER BY object_revision", id);
        var events = db.queryForList("SELECT request_key,actor_ref,operation,object_revision FROM enf9_legal_event WHERE object_id=? ORDER BY object_revision", id);
        assertEquals(2, receipts.size()); assertEquals(receipts, events);
        for (var row : receipts) assertEquals(required("ENF_ACCEPTANCE_SUBJECT"), row.get("ACTOR_REF"));
        var last = references.getLast();
        assertEquals(1,db.queryForObject("SELECT COUNT(DISTINCT reference_id) FROM enf9_legal_reference WHERE search_id=?",Integer.class,last.searchId()));
        String payload = db.queryForObject("SELECT payload FROM enf6_procedure_report WHERE report_id=? AND revision=?", String.class, last.targetId(), last.targetRevision());
        assertNotNull(payload);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt WHERE result_id=?", Integer.class, last.targetId()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event WHERE result_id=?", Integer.class, last.targetId()));
        var catalog = json.readTree(Files.readAllBytes(Path.of(required("ENF_LEGAL_L06_CATALOG"))));
        var originalPath = Path.of(required("ENF_LEGAL_L06_ORIGINAL"));
        String originalSha = digest(Files.readAllBytes(originalPath));
        assertEquals(catalog.path("provenance").path("snapshotSha256").asText(), originalSha);
        var output = Path.of(required("ENF_LEGAL_L06_OUTPUT")); Files.createDirectories(output.getParent());
        json.writeValue(output.toFile(), java.util.Map.of("referenceId", id, "versions", references,
                "receipts", receipts, "events", events, "targetSha256", digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "originalSha256", originalSha, "historicalOriginalSha256", digest(Files.readAllBytes(Path.of(required("ENF_LEGAL_L06_HISTORICAL_ORIGINAL")))), "inputNature", "FUNCTIONAL_VALIDATION", "businessAcceptance", "PENDING"));
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required; never skipped"); return value;
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

