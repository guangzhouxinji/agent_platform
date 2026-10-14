package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real DM8/files/command evidence reconciliation, explicitly not document generation I. */
class EnforcementDocumentDraftPublicReconciliationIT {
    @Test void reconcileAdmittedOriginalsAndExactPublishedMappingWithoutClaimingGeneration()throws Exception {
        var s=new EnforcementDocumentDraftPublicAdmissionIT();s.initialize();
        var root=Path.of(EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_LINKED_CASE_ROOT"));
        var context=s.json.readTree(Files.readAllBytes(root.resolve("case.json")));
        var materialRoot=Path.of(EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_MATERIAL_ROOT"));
        var mappingReport=s.json.readTree(Files.readAllBytes(Path.of(EnforcementDocumentDraftPublicAdmissionIT.required("ENF_DRAFT_MAPPING_REPORT"))));
        String ruleId=mappingReport.at("/rule/id").asText();assertFalse(ruleId.isBlank());
        var checked=new ArrayList<Map<String,Object>>();
        for(var ref:context.path("materials")) {
            String id=ref.path("id").asText();
            var material=s.db.queryForMap("SELECT revision,state,sha256,file_key,clue_id,organization_id FROM enf3_material WHERE material_id=?",id);
            assertEquals(3,((Number)material.get("REVISION")).longValue());assertEquals("VERIFIED",material.get("STATE"));assertEquals(s.org,material.get("ORGANIZATION_ID"));
            var original=new cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles(materialRoot).read(material.get("FILE_KEY").toString());
            assertEquals(ref.path("sha256").asText(),EnforcementDocumentDraftPublicAdmissionIT.sha(original));
            var receipts=s.db.queryForList("SELECT operation,result_version,actor_ref,request_id FROM enf3_receipt WHERE result_id=? ORDER BY created_at",id);
            var events=s.db.queryForList("SELECT operation,actor_ref,request_id FROM enf3_event WHERE result_id=? ORDER BY occurred_at",id);
            assertEquals(3,receipts.size());assertEquals(3,events.size());
            for(var receipt:receipts)assertEquals(1,events.stream().filter(e->Objects.equals(receipt.get("REQUEST_ID"),e.get("REQUEST_ID"))&&Objects.equals(receipt.get("ACTOR_REF"),e.get("ACTOR_REF"))&&Objects.equals(receipt.get("OPERATION"),e.get("OPERATION"))).count());
            checked.add(Map.of("id",id,"revision",3,"sha256",ref.path("sha256").asText(),"receipts",receipts,"events",events,"originalMatched",true));
        }
        var history=s.db.queryForList("SELECT revision,state,payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",ruleId);
        assertEquals(2,history.size());assertEquals("DRAFT",history.getFirst().get("STATE"));assertEquals("PUBLISHED",history.getLast().get("STATE"));
        for(var row:history){var payload=s.json.readTree(row.get("PAYLOAD").toString());assertEquals("ENF14-BUILTIN-FACT_RECORD",payload.path("templateId").asText());assertEquals(1,payload.path("templateRevision").asLong());}
        var receipts=s.db.queryForList("SELECT request_key,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY created_at",ruleId);
        var events=s.db.queryForList("SELECT request_key,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY created_at",ruleId);
        assertEquals(2,receipts.size());assertEquals(2,events.size());
        for(var receipt:receipts)assertEquals(1,events.stream().filter(e->Objects.equals(receipt.get("REQUEST_KEY"),e.get("REQUEST_KEY"))&&Objects.equals(receipt.get("OPERATION"),e.get("OPERATION"))&&Objects.equals(receipt.get("REVISION"),e.get("REVISION"))).count());
        var attempts=s.db.queryForList("SELECT attempt_id,action_code,outcome,artifact_sha256 FROM enf10_document_rule_attempt WHERE rule_id=?",ruleId);assertEquals(2,attempts.size());
        s.json.writerWithDefaultPrettyPrinter().writeValue(s.output.resolve("public-input-reconciliation.json").toFile(),Map.of("run",s.run,"scope","MATERIAL_AND_MAPPING_RECONCILIATION_NOT_DOCUMENT_GENERATION_I","materials",checked,"ruleHistory",history,"ruleReceipts",receipts,"ruleEvents",events,"attempts",attempts,"documentGeneration","NOT_RUN","businessAcceptance","PENDING"));
    }
}
