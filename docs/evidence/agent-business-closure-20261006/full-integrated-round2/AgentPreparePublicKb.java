import java.nio.file.*;
import java.sql.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.knowledgebase.adapter.out.dm8.LocalKnowledgeBaseImporter;
class AgentPreparePublicKb {
 public static void main(String[] args) throws Exception {
  if (!"jdbc:dm://127.0.0.1:5391".equals(System.getenv("DM8_JDBC_URL"))) throw new IllegalArgumentException("Owned isolated endpoint only");
  var ds = new DriverManagerDataSource(System.getenv("DM8_JDBC_URL"), System.getenv("DM8_JDBC_USER"), System.getenv("DM8_JDBC_PASSWORD"));
  ds.setDriverClassName("dm.jdbc.driver.DmDriver");
  try (var c=ds.getConnection(); var st=c.createStatement()) {
   var rs=st.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='KB_RECORD_VERSION'"); rs.next(); boolean exists=rs.getInt(1)!=0; rs.close(); System.out.println("Existing owned KB="+exists);
   if(!exists) for(var sql:Files.readString(Path.of("db/dm8/V008__local_public_knowledge_base_v1.sql")).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())st.execute(sql);
  }
  System.out.println(new LocalKnowledgeBaseImporter(ds,new ObjectMapper()).importAll());
 }
}



