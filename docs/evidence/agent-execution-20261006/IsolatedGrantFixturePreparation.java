import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
/** One-run engineering fixture preparation. Never connects to product ports or existing schemas. */
public class IsolatedGrantFixturePreparation {
 static String env(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException(name);return value;}
 static Connection create(String prefix) throws Exception {
  String url=env(prefix+"_URL"),schema=env(prefix+"_USER"),secret=env(prefix+"_PASSWORD");
  if(!url.matches("jdbc:dm://127\\.0\\.0\\.1:538[12]") || !schema.matches("ENFFIX_[A-F0-9]{12}") || !secret.matches("[A-Za-z0-9!]{24,80}"))throw new IllegalArgumentException("Exact isolated scope required");
  try(var admin=DriverManager.getConnection(url,"SYSDBA",env(prefix+"_ADMIN"));var query=admin.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?")) {
   query.setString(1,schema);try(var result=query.executeQuery()){result.next();if(result.getInt(1)!=0)throw new IllegalStateException("Refuse existing schema");}
   try(var statement=admin.createStatement()){statement.execute("CREATE USER "+schema+" IDENTIFIED BY \""+secret+"\"");statement.execute("GRANT RESOURCE TO "+schema);}
  } catch(SQLException error) {throw new IllegalStateException("Isolated schema preparation SQL code="+error.getErrorCode());}
  return DriverManager.getConnection(url,schema,secret);
 }
 public static void main(String[] args) throws Exception {
  if(env("ENF_ACCEPTANCE_BUSINESS_URL").equals(env("ENF_ACCEPTANCE_AUTHORITY_URL")))throw new IllegalArgumentException("Independent instances required");
  try(var business=create("ENF_ACCEPTANCE_BUSINESS");var authority=create("ENF_ACCEPTANCE_AUTHORITY")) {
   for(String path:new String[]{"db/dm8/V032__product_identity.sql","db/dm8/V033__product_oauth2.sql","db/dm8/V057__product_oauth_client_control.sql"})ScriptUtils.executeSqlScript(business,new FileSystemResource(path));
   ScriptUtils.executeSqlScript(authority,new FileSystemResource("db/identity-authority/V001__grant_watermark.sql"));
   try(var statement=business.prepareStatement("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,'component-only-not-login','ACTIVE',1,?)")){
    statement.setString(1,env("ENF_ACCEPTANCE_ISSUER"));statement.setString(2,env("ENF_ACCEPTANCE_SUBJECT"));statement.setString(3,"isolated-fixture-only");statement.setTimestamp(4,Timestamp.from(Instant.now()));statement.executeUpdate();
   }
   try(var statement=authority.prepareStatement("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) VALUES(?,0,'READY',NULL,?)")) {
    statement.setString(1,env("ENF_ACCEPTANCE_ISSUER"));statement.setString(2,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");statement.executeUpdate();
   }
   System.out.println("Fresh two-instance identity fixture prepared; no existing schemas or product authority touched");
  }
 }
}
