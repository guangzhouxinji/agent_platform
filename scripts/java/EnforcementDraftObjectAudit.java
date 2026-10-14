import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only inventory, not a claim of current authorization. */
public class EnforcementDraftObjectAudit {
    public static void main(String[] args) throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        var result=new LinkedHashMap<String,Object>();
        result.put("checkedAt",java.time.Instant.now().toString());
        result.put("scope","READ_ONLY_RUNTIME_OBJECT_INVENTORY_NOT_AUTHORIZATION");
        try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))) {
            db.setReadOnly(true);
            result.put("templates",query(db,"SELECT template_id,revision,state,organization_id,file_sha256,file_key FROM enf9_document_template_revision r WHERE revision=(SELECT MAX(revision) FROM enf9_document_template_revision x WHERE x.template_id=r.template_id)"));
            result.put("materialSources",query(db,"SELECT source_id,organization_id,rights_ref,source_epoch,expires_at,allowed_uses FROM enf3_source"));
            result.put("materials",query(db,"SELECT material_id,clue_id,organization_id,sha256,file_key,rights_ref,expires_at,state,revision FROM enf3_material"));
            result.put("rules",query(db,"SELECT rule_id,revision,state,organization_id,payload FROM enf10_document_rule r WHERE revision=(SELECT MAX(revision) FROM enf10_document_rule x WHERE x.rule_id=r.rule_id)"));
            result.put("references",query(db,"SELECT reference_id,revision,organization_id,review_state,binding_state,source_id,source_revision,verbatim_sha256 FROM enf9_legal_reference r WHERE revision=(SELECT MAX(revision) FROM enf9_legal_reference x WHERE x.reference_id=r.reference_id)"));
            String draftRoot=System.getenv("ENF_DRAFT_AUDIT_ROOT");
            if(draftRoot!=null&&!draftRoot.isBlank()) {
                String run=System.getenv("ENF_DRAFT_AUDIT_RUN_ID");
                if(run==null||!run.matches("[A-Za-z0-9-]{1,60}"))throw new IllegalArgumentException("Explicit artifact audit run required");
                var drafts=query(db,"SELECT draft_id,revision,artifact_key,artifact_sha256,content_sha256,input_manifest_sha256 FROM enf12_document_draft_revision WHERE draft_id LIKE ?","%"+run);
                for(var row:drafts) {
                    var file=java.nio.file.Path.of(draftRoot).resolve((String)row.get("artifact_key")).normalize();
                    if(!file.startsWith(java.nio.file.Path.of(draftRoot).toAbsolutePath().normalize()))throw new SecurityException("Artifact outside audit root");
                    if(java.nio.file.Files.exists(file)) {
                        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
                        row.put("actual_artifact_sha256",digest);row.put("artifact_matches",digest.equals(row.get("artifact_sha256")));
                    } else row.put("artifact_matches",false);
                }
                result.put("draftArtifacts",drafts);
                result.put("draftReceiptEventCounts",query(db,"SELECT d.draft_id,d.revision,(SELECT COUNT(*) FROM enf12_document_draft_receipt r WHERE r.draft_id=d.draft_id AND r.revision=d.revision) AS receipt_count,(SELECT COUNT(*) FROM enf12_document_draft_event e WHERE e.draft_id=d.draft_id AND e.revision=d.revision) AS event_count FROM enf12_document_draft_revision d WHERE d.draft_id LIKE ?","%"+run));
                var candidates=query(db,"SELECT candidate_id,draft_id,base_revision,artifact_key,artifact_sha256,content_sha256 FROM enf12_document_draft_candidate WHERE draft_id LIKE ?","%"+run);
                for(var row:candidates)artifact(row,draftRoot,"artifact_sha256");
                result.put("candidateArtifacts",candidates);
                var exports=query(db,"SELECT export_id,draft_id,revision,artifact_key,artifact_sha256,content_sha256,renderer_version,request_key FROM enf12_document_draft_export WHERE draft_id LIKE ?","%"+run);
                for(var row:exports)artifact(row,draftRoot,"artifact_sha256");
                result.put("exportArtifacts",exports);
                result.put("reviewRecords",query(db,"SELECT draft_id,revision,review_sequence,content_sha256,outcome,actor_ref,request_key FROM enf12_document_draft_review WHERE draft_id LIKE ? ORDER BY draft_id,revision,review_sequence","%"+run));
                result.put("workflowReceiptEvents",query(db,"SELECT r.request_key,e.operation,r.draft_id,r.revision FROM enf12_document_draft_receipt r JOIN enf12_document_draft_event e ON e.request_key=r.request_key WHERE r.draft_id LIKE ? ORDER BY r.created_at","%"+run));
            }
            String templateRoot=System.getenv("ENF_DRAFT_TEMPLATE_AUDIT_ROOT");
            if(templateRoot!=null&&!templateRoot.isBlank()) {
                var templates=query(db,"SELECT template_id,revision,state,organization_id,file_sha256,file_key AS artifact_key FROM enf9_document_template_revision WHERE template_id LIKE 'ENF14-BUILTIN-%'");
                for(var row:templates)artifact(row,templateRoot,"file_sha256");
                result.put("baselineTemplateArtifacts",templates);
                result.put("baselineTemplateReceiptEvents",query(db,"SELECT r.request_key,r.operation,e.event_id FROM enf9_document_template_receipt r JOIN enf9_document_template_event e ON e.request_key=r.request_key WHERE r.request_key LIKE 'baseline-ENF14-BUILTIN-%' ORDER BY r.created_at"));
            }
            result.put("currentGrants",query(db,"SELECT g.resource_type,g.action_code,g.purpose,g.organization,COUNT(*) AS grant_count FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.domain_code='ENFORCEMENT' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP GROUP BY g.resource_type,g.action_code,g.purpose,g.organization"));
        }
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private static void artifact(Map<String,Object> row,String root,String expected) throws Exception {
        var parent=java.nio.file.Path.of(root).toAbsolutePath().normalize();
        var file=parent.resolve((String)row.get("artifact_key")).normalize();
        if(!file.startsWith(parent)||file.equals(parent))throw new SecurityException("Artifact outside explicit root");
        if(java.nio.file.Files.exists(file)) {
            var actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
            row.put("actual_artifact_sha256",actual);row.put("artifact_matches",actual.equals(row.get(expected)));
        }else row.put("artifact_matches",false);
    }
    private static List<Map<String,Object>> query(Connection db,String sql,Object... parameters) throws SQLException {
        var rows=new ArrayList<Map<String,Object>>();
        try(var statement=db.prepareStatement(sql)) {
            for(int i=0;i<parameters.length;i++)statement.setObject(i+1,parameters[i]);
            try(var rs=statement.executeQuery()) {
            var meta=rs.getMetaData();
            while(rs.next()) {
                var row=new LinkedHashMap<String,Object>();
                for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getString(i));
                rows.add(row);
            }
        }
        }
        return rows;
    }
}
