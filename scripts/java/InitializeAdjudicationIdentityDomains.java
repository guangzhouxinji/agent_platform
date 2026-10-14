import java.sql.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import cn.xinji.agentplatform.security.product.ProductIdentityBootstrap;
/** Installs existing DDL and invokes normal pinned first initialization; never writes a business grant. */
class InitializeAdjudicationIdentityDomains {
 static String env(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing private deployment setting: "+n);return v;}
 static void migrate(DriverManagerDataSource ds,String table,String file)throws Exception {
  var db=new JdbcTemplate(ds);if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME=?",Integer.class,table)==0)try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of(file)));}
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=1)throw new IllegalArgumentException("Pinned bootstrap manifest required");
  if(!env("PRODUCT_IDENTITY_DB_URL").equals("jdbc:dm://127.0.0.1:5301")||!env("PRODUCT_IDENTITY_DB_DEPLOY_USER").equals("ADJLLM20261003")||!env("PRODUCT_IDENTITY_AUTHORITY_DB_URL").equals("jdbc:dm://127.0.0.1:5370")||!env("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER").equals("ADJAUTH20261004"))throw new SecurityException("Exact independent ADJ deployment only");
  var primary=new DriverManagerDataSource(env("PRODUCT_IDENTITY_DB_URL"),env("PRODUCT_IDENTITY_DB_DEPLOY_USER"),env("PRODUCT_IDENTITY_DB_DEPLOY_PASSWORD"));var db=new JdbcTemplate(primary);
  String ledger="SELECT policy_key,approval_key,case_id,calls,input_tokens,output_tokens,cost_micros FROM adj3_model_budget";var before=db.queryForList(ledger);
  if(before.size()!=1||!before.getFirst().get("CASE_ID").equals("adjudication-materials-case-01")||((Number)before.getFirst().get("CALLS")).longValue()!=1)throw new IllegalStateException("Persistent original allowance must exist; never reset it");
  try(var c=DriverManager.getConnection(env("PRODUCT_IDENTITY_AUTHORITY_DB_URL"),"SYSDBA",env("ADJ_AUTHORITY_ADMIN_PASSWORD"));var st=c.createStatement()){
   for(String name:List.of("ADJAUTH20261004","ADJAUTH_SVC","ADJAUTH_BKP")) {
    String secret=env(name.equals("ADJAUTH20261004")?"PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD":name.equals("ADJAUTH_SVC")?"ADJ_AUTHORITY_SERVICE_PASSWORD":"ADJ_AUTHORITY_BACKUP_PASSWORD");if(!secret.matches("[A-Za-z0-9]{32,64}"))throw new SecurityException("Controlled deployment secret required");
    try(var q=c.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?")){q.setString(1,name);try(var r=q.executeQuery()){r.next();if(r.getInt(1)==0)st.execute("CREATE USER "+name+" IDENTIFIED BY \""+secret+"\"");}}
    if(name.equals("ADJAUTH20261004"))st.execute("GRANT RESOURCE TO "+name);
   }
  }
  var authority=new DriverManagerDataSource(env("PRODUCT_IDENTITY_AUTHORITY_DB_URL"),env("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_USER"),env("PRODUCT_IDENTITY_AUTHORITY_DEPLOY_PASSWORD"));
  migrate(primary,"ID2_SUBJECT","db/dm8/V032__product_identity.sql");migrate(primary,"OAUTH2_REGISTERED_CLIENT","db/dm8/V033__product_oauth2.sql");
  if(db.queryForObject("SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='ID2_SUBJECT' AND COLUMN_NAME='SUBJECT_KIND'",Integer.class)==0)try(var c=primary.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V046__product_workload_subject.sql"));}
  migrate(primary,"ID2_CLIENT_CURRENT","db/dm8/V057__product_oauth_client_control.sql");migrate(primary,"ADJ2_CASE_WORK","db/dm8/V086__adjudication_case_work.sql");
  migrate(authority,"ID2_MAINTAINER","db/identity-authority/V001__grant_watermark.sql");migrate(authority,"ADJ2_SOURCE_USE","db/dm8/adjudication_source_authority.sql");
  ProductIdentityBootstrap.main(args);
  var a=new JdbcTemplate(authority);a.execute("GRANT SELECT,UPDATE ON adj2_source_generation TO ADJAUTH_SVC");a.execute("GRANT SELECT,INSERT,UPDATE,REFERENCES ON adj2_source_use TO ADJAUTH_SVC");a.execute("GRANT SELECT,INSERT ON adj2_source_event TO ADJAUTH_SVC");for(String t:List.of("adj2_source_generation","adj2_source_use","adj2_source_event"))a.execute("GRANT SELECT ON "+t+" TO ADJAUTH_BKP");
  if(!before.equals(db.queryForList(ledger)))throw new IllegalStateException("Original budget changed during initialization");
  System.out.println("ADJ_INDEPENDENT_BOOTSTRAP_COMMITTED_ORIGINAL_ALLOWANCE_UNCHANGED_NO_BUSINESS_GRANT_WRITES");
 }
}
