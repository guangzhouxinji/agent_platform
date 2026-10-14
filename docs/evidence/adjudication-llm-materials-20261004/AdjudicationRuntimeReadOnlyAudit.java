import java.sql.*;
public class AdjudicationRuntimeReadOnlyAudit {
  static Connection open(String prefix) throws Exception {
    Connection c=DriverManager.getConnection(System.getenv(prefix+"_URL"),System.getenv(prefix+"_USER"),System.getenv(prefix+"_PASSWORD"));
    c.setReadOnly(true);return c;
  }
  static void query(Connection c,String label,String sql)throws Exception {
    try(var st=c.createStatement()){st.setQueryTimeout(15);try(var r=st.executeQuery(sql)) {
      int n=r.getMetaData().getColumnCount();System.out.print(label+"_COLUMNS");for(int i=1;i<=n;i++)System.out.print("\t"+r.getMetaData().getColumnLabel(i));System.out.println();
      int rows=0;while(r.next()){rows++;System.out.print(label);for(int i=1;i<=n;i++){String v=r.getString(i);System.out.print("\t"+(v==null?"NULL":v.replace("\t"," ").replace("\r"," ").replace("\n"," ")));}System.out.println();}
      System.out.println(label+"_ROWS\t"+rows);
    }}
  }
  public static void main(String[] args)throws Exception {
    Class.forName("dm.jdbc.driver.DmDriver");System.out.println("SCOPE\tREAD_ONLY_NO_AUTHORIZATION_OR_PROVIDER_REQUEST");System.out.println("CHECKED_AT_UTC\t"+java.time.Instant.now());
    try(var c=open("ADJ_AUDIT_MODEL")) {
      query(c,"MODEL_TABLES","SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME LIKE 'ADJ%' OR TABLE_NAME LIKE 'ID2%' ORDER BY TABLE_NAME");
      query(c,"BUDGET","SELECT case_id,approval_key,policy_key,calls,input_tokens,output_tokens,cost_micros,frozen,expires_at FROM adj3_model_budget");
      query(c,"RUNS","SELECT run_id,state,created_at,expires_at,payload_sha FROM adj3_material_run");
    }
    try(var c=open("ADJ_AUDIT_AUTHORITY")) {
      query(c,"MAINTAINER","SELECT subject_ref,organization,allowed_roles,status,version FROM id2_maintainer WHERE singleton_key=1");
    }
    try(var c=open("ADJ_AUDIT_PRIMARY")) {
      query(c,"PRIMARY_ADJ_TABLES","SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME LIKE 'ADJ2%' OR TABLE_NAME LIKE 'ADJ3%' ORDER BY TABLE_NAME");
      query(c,"ADJ_GRANTS","SELECT issuer,subject_id,organization,resource_id,action_code,status,expires_at FROM id2_grant WHERE domain_code='ADJUDICATION'");
      query(c,"ADJ_NAMED_HUMANS","SELECT issuer,subject_id,login_name,status FROM id2_subject WHERE subject_kind='HUMAN' AND (LOWER(login_name) LIKE '%adjudication%' OR LOWER(login_name) LIKE '%adj-%')");
    }
  }
}
