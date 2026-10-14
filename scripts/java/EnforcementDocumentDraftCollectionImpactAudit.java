import java.sql.*;
public class EnforcementDocumentDraftCollectionImpactAudit {
 public static void main(String[] args)throws Exception {
  try(var db=DriverManager.getConnection(System.getenv("ENF_AUDIT_URL"),System.getenv("ENF_AUDIT_USER"),System.getenv("ENF_AUDIT_PASSWORD"))) {
   db.setReadOnly(true);
   for(String sql:new String[]{
    "SELECT COUNT(*) FROM enf8_real_business_task WHERE task_id='ENF-RBC-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf8_real_business_task",
    "SELECT COUNT(*) FROM enf8_real_business_task_event WHERE task_id='ENF-RBC-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf8_job_pack_revision WHERE package_id='ENF-JOB-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf8_job_pack_event WHERE package_id='ENF-JOB-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf8_job_pack_receipt WHERE package_id='ENF-JOB-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf8_integration_trigger WHERE package_id='ENF-JOB-CNIPA-GUIDANCE-CASE-1'",
    "SELECT COUNT(*) FROM enf12_document_draft_revision WHERE draft_id='ENF14-FACT-DOCUMENT-20261003-70'"
   })try(var query=db.createStatement();var rows=query.executeQuery(sql)){rows.next();System.out.println(sql+" => "+rows.getLong(1));}
  }
 }
}

