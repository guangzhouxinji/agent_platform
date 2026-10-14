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
@EnabledIfEnvironmentVariable(named = "ENF_LEGAL_FINAL_REFERENCE_ID", matches = ".+")
class LegalReferenceRuntimeDm8Test {
    @Test void browserProducedTargetReferenceOriginalReceiptsAndEventsReconcile() throws Exception {
        var db = new JdbcTemplate(new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),
                required("ENF_ACCEPTANCE_BUSINESS_USER"), required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var json = new ObjectMapper().findAndRegisterModules();
        String id = required("ENF_LEGAL_FINAL_REFERENCE_ID");
        var references = db.query("SELECT payload FROM enf9_legal_reference WHERE reference_id=? ORDER BY revision",
                (rs, row) -> { try { return json.readValue(rs.getString(1), LegalReferenceContract.ReferenceRevision.class); }
                    catch (Exception e) { throw new IllegalStateException(e); } }, id);
        assertEquals(3, references.size(), "bind, technical return and unbind only");
        assertEquals(List.of(1L, 2L, 3L), references.stream().map(LegalReferenceContract.ReferenceRevision::revision).toList());
        assertEquals(LegalReferenceContract.ReviewState.PENDING, references.getFirst().reviewState());
        assertEquals(LegalReferenceContract.ReviewState.RETURNED, references.getLast().reviewState());
        assertEquals(LegalReferenceContract.BindingState.UNBOUND, references.getLast().bindingState());
        assertTrue(references.stream().noneMatch(r -> r.reviewState() == LegalReferenceContract.ReviewState.CONFIRMED));
        assertEquals(0, references.getFirst().previousRevision());
        assertEquals(1, references.get(1).previousRevision()); assertEquals(2, references.getLast().previousRevision());
        var source = new FileLegalKnowledgeSource(Path.of(required("ENF_LEGAL_FINAL_CATALOG")), json);
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
        assertEquals(3, receipts.size()); assertEquals(receipts, events);
        for (var row : receipts) assertEquals(required("ENF_ACCEPTANCE_SUBJECT"), row.get("ACTOR_REF"));
        var last = references.getLast();
        assertEquals(1, db.queryForObject("SELECT COUNT(DISTINCT r.reference_id) FROM enf9_legal_reference r JOIN enf9_legal_search s "
                + "ON s.search_id=r.search_id AND s.revision=r.search_revision WHERE s.target_type=? AND s.target_id=? AND s.target_revision=? AND r.organization_id=?",
                Integer.class, last.targetType(), last.targetId(), last.targetRevision(), last.organization()), "Replay/failure must not create additional binding IDs");
        String payload = db.queryForObject("SELECT payload FROM enf6_procedure_report WHERE report_id=? AND revision=?", String.class, last.targetId(), last.targetRevision());
        assertNotNull(payload);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt WHERE result_id=?", Integer.class, last.targetId()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event WHERE result_id=?", Integer.class, last.targetId()));
        var catalog = json.readTree(Files.readAllBytes(Path.of(required("ENF_LEGAL_FINAL_CATALOG"))));
        var originalPath = Path.of(required("ENF_LEGAL_FINAL_ORIGINAL"));
        String originalSha = digest(Files.readAllBytes(originalPath));
        assertEquals(catalog.path("provenance").path("snapshotSha256").asText(), originalSha);
        var output = Path.of(required("ENF_LEGAL_FINAL_OUTPUT")); Files.createDirectories(output.getParent());
        json.writeValue(output.toFile(), java.util.Map.of("referenceId", id, "versions", references,
                "receipts", receipts, "events", events, "targetSha256", digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "originalSha256", originalSha, "inputNature", "FUNCTIONAL_VALIDATION", "businessAcceptance", "PENDING"));
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " required; never skipped"); return value;
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
