package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact permission preparation or read-only browser-result reconciliation. Never signs a review. */
class EnforcementDocumentDraftFactRecordProductIT {
    @Test void prepareExactAccessOrReconcileActualBrowserGeneratedIncompleteRecord()throws Exception {
        var s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
        String id=EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_FACT_DOCUMENT_ID");
        if("PREPARE_ACCESS".equals(System.getenv("ENF_DRAFT_FACT_PHASE"))) {
            assertEquals(0,s.db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_revision WHERE draft_id=?",Integer.class,id));
            for(String action:List.of("PREPARE","CREATE","READ","EDIT","REGENERATE","ADOPT"))
                s.grant(s.actor,"DOCUMENT_DRAFT",id,1,action,"DOCUMENT_DRAFT");
            s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("document-access-preparation.json").toFile(),Map.of("id",id,"scope","EXACT_TECHNICAL_ACCESS_NOT_DOMAIN_I","reviewGrantIssued",false,"businessAcceptance","PENDING"));
            return;
        }
        assertEquals("RECONCILE",System.getenv("ENF_DRAFT_FACT_PHASE"));
        var rows=s.db.queryForList("SELECT revision,review_state,content_sha256,input_manifest_sha256,artifact_key,artifact_sha256,payload FROM enf12_document_draft_revision WHERE draft_id=? ORDER BY revision",id);
        assertEquals(1,rows.size());var row=rows.getFirst();assertEquals("INCOMPLETE_DRAFT",row.get("REVIEW_STATE"));
        Path root=Path.of(EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_FACT_FILE_ROOT")).toAbsolutePath().normalize();
        Path file=root.resolve(row.get("ARTIFACT_KEY").toString()).normalize();assertTrue(file.startsWith(root));
        byte[] original=Files.readAllBytes(file);assertEquals(row.get("ARTIFACT_SHA256"),EnforcementDocumentDraftPublicAdmissionIT.sha(original));
        var persisted=s.json.readTree(original);assertEquals(s.json.readTree(row.get("PAYLOAD").toString()),persisted);
        assertEquals(row.get("CONTENT_SHA256"),EnforcementDocumentDraftPublicAdmissionIT.sha(persisted.path("contentJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("FACT_RECORD",persisted.at("/manifest/type").asText());assertEquals(0,persisted.at("/manifest/legalReferences").size());
        assertTrue(persisted.path("missingItems").size()>0);
        var events=s.db.queryForList("SELECT request_key,operation,revision,actor_ref FROM enf12_document_draft_event WHERE draft_id=?",id);
        var receipts=s.db.queryForList("SELECT request_key,payload,actor_ref FROM enf12_document_draft_receipt WHERE draft_id=?",id);
        assertEquals(1,events.size());assertEquals(1,receipts.size());assertEquals("GENERATE",events.getFirst().get("OPERATION"));
        assertEquals(events.getFirst().get("REQUEST_KEY"),receipts.getFirst().get("REQUEST_KEY"));
        assertEquals(0,s.db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_review WHERE draft_id=?",Integer.class,id));
        s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("fact-document-reconciliation.json").toFile(),Map.of("run",s.run,"id",id,"scope","ACTUAL_BROWSER_GENERATION_DM8_FILE_RECONCILIATION","revisions",rows,"events",events,"receipts",receipts,"originalSHA",row.get("ARTIFACT_SHA256"),"document",persisted,"review","NOT_RUN_INCOMPLETE","businessAcceptance","PENDING"));
    }
}
