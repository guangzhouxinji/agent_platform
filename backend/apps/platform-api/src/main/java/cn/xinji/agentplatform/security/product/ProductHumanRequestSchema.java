package cn.xinji.agentplatform.security.product;

import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only column admission. No row contents, DDL, defaults, or maintenance operations. */
final class ProductHumanRequestSchema {
    private static final String SCOPE_COLUMNS = "scope_id,organization_id,task_id,generation,state,intent_id,"
            + "policy_id,policy_version,policy_sha,history_sha,created_at,valid_until";
    private ProductHumanRequestSchema() { }

    static void require(JdbcTemplate main, JdbcTemplate authority) {
        columns(main, "id2_subject", "issuer,subject_id,subject_kind,status,credential_version");
        columns(main, "id2_client_current", "registered_id,status,version");
        columns(main, "con6_human_package", "package_id,package_revision,task_id,organization_id,data_scope,task_version,"
                + "requester_issuer,requester_subject,manifest_json,manifest_sha,created_at,body_until,body_access_until");
        columns(main, "con6_human_handling", "package_id,package_revision,processing_version,state,last_change_at");
        columns(main, "con6_human_event", "package_id,package_revision,sequence_no,operation,before_version,after_version,"
                + "actor_issuer,actor_subject,manifest_sha,request_sha,occurred_at,audit_until");
        columns(main, "con6_human_receipt", "task_id,actor_issuer,actor_subject,command_code,request_id,request_sha,"
                + "package_id,package_revision,event_sequence,created_at,audit_until");
        columns(main, "con6_human_cleanup_scope", SCOPE_COLUMNS);
        columns(main, "con6_human_cleanup_event", "scope_id,generation,event_json,event_sha,occurred_at,audit_until");
        columns(authority, "id6_human_cleanup_scope", SCOPE_COLUMNS);
    }
    private static void columns(JdbcTemplate db, String table, String columns) {
        db.query("SELECT " + columns + " FROM " + table + " WHERE 1=0", (rs, row) -> 0);
    }
}
