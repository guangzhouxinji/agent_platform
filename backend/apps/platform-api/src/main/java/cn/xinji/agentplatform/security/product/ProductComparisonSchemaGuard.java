package cn.xinji.agentplatform.security.product;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Startup-only zero-row structural probe. Never migration history or current business authority. */
final class ProductComparisonSchemaGuard {
    record Shape(String migration,String table,String columns) { }
    static final List<Shape> INPUT = List.of(
            new Shape("V094","con5_preparation","preparation_id,task_id,organization_id,task_version,goal_id,goal_revision,input_id,target_message_id,command_json,snapshot_json,prepare_sha,material_sha,actor_issuer,actor_subject,created_at,expires_at,retention_ref,retention_version,retention_evidence_sha"),
            new Shape("V094","con5_prepared_document","preparation_id,document_role,document_key,ordinal,source_id,contract_version,decision_id,source_epoch,source_row_version,document_id,document_version,source_version,raw_sha,original_ref,json_pointer,branch_json,display_grant_id,display_generation,compare_grant_id,compare_generation,expires_at"),
            new Shape("V094","con5_feature_revision","revision_id,task_id,revision_number,previous_revision_id,preparation_id,prepare_sha,target_sha,package_sha,feature_count,actor_issuer,actor_subject,request_id,request_sha,statement_json,confirmed_at,expires_at"),
            new Shape("V094","con5_feature","revision_id,feature_id,ordinal,parent_feature_id,char_start,char_end,original_version,original_ref,json_pointer,original_sha,byte_start,byte_end,span_sha,claim_number,user_text_json"),
            new Shape("V094","con5_feature_current","task_id,revision_id,revision_number"),
            new Shape("V094","con5_feature_event","event_id,task_id,revision_id,actor_issuer,actor_subject,request_sha,package_sha,occurred_at"),
            new Shape("V095","con5_comparison_input","comparison_id,task_id,feature_revision_id,feature_revision_number,preparation_id,task_version,previous_comparison_id,input_json,input_sha,candidate_count,actor_issuer,actor_subject,request_id,request_sha,created_at,expires_at"),
            new Shape("V095","con5_comparison_candidate","comparison_id,preparation_id,document_role,candidate_id,ordinal,status"),
            new Shape("V095","con5_comparison_current","task_id,comparison_id"),
            new Shape("V095","con5_comparison_event","event_id,task_id,comparison_id,actor_issuer,actor_subject,request_sha,input_sha,occurred_at"),
            new Shape("V098","con5_comparison_delegation","delegation_id,task_id,comparison_id,candidate_id,input_sha,binding_sha,service_subject,created_at,expires_at"),
            new Shape("V099","con5_candidate_attempt","attempt_id,task_id,comparison_id,candidate_id,input_sha,task_version,cancel_epoch,worker_subject,delegation_id,request_id,request_sha,originals_sha,fencing_token,status,error_code,created_at,lease_until"),
            new Shape("V099","con5_candidate_lease","comparison_id,candidate_id,attempt_id,fencing_token,status,lease_until"),
            new Shape("V101","con5_preparation_request","task_id,actor_issuer,actor_subject,request_id,request_sha,preparation_id"));
    static final List<Shape> MATRIX = List.of(
            new Shape("V100","con5_matrix_invocation","attempt_id,approval_sha,request_sha,configuration_version,status,response_sha,response_json,created_at"),
            new Shape("V100","con5_matrix_report","report_id,task_id,comparison_id,candidate_id,attempt_id,fencing_token,input_sha,report_sha,package_json,created_at,expires_at"),
            new Shape("V100","con5_matrix_row","report_id,ordinal,feature_id,branch_sha,row_json,row_sha"),
            new Shape("V100","con5_matrix_citation","report_id,row_ordinal,citation_ordinal,citation_json,citation_sha"),
            new Shape("V100","con5_matrix_receipt","attempt_id,report_id,report_sha,receipt_json,receipt_sha"));
    static final List<Shape> LINEAGE = List.of(
            new Shape("V108","con5_matrix_dependency_material","attempt_id,material_json,material_sha"),
            new Shape("V108","con5_matrix_dependency_original","report_id,attempt_id,original_json,original_sha"));
    static void lineage(JdbcTemplate db){probe(db,LINEAGE);}
    ProductComparisonSchemaGuard(JdbcTemplate db,boolean matrixEnabled) {
        probe(db,INPUT);
        if(matrixEnabled)probe(db,MATRIX);
    }
    private static void probe(JdbcTemplate db,List<Shape> shapes) {
        for(var shape:shapes) {
            try {db.query("SELECT "+shape.columns()+" FROM "+shape.table()+" WHERE 1=0",(ResultSetExtractor<Void>)rs->null);}
            catch(org.springframework.dao.DataAccessException unavailable) {
                // No datasource exception text or credentials are included in the public startup error.
                throw new IllegalStateException("COMPARISON_SCHEMA_REQUIRED:"+shape.migration()+":"+shape.table());
            }
        }
    }
}
