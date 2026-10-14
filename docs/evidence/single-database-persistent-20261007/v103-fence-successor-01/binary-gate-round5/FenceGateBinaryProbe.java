package cn.xinji.agentplatform.security.product;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
public final class FenceGateBinaryProbe {
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static List<Map<String,Object>> exceptions=new ArrayList<>();
 static void exception(Throwable e){var chain=new ArrayList<Map<String,Object>>();for(Throwable c=e;c!=null;c=c.getCause()){var x=new LinkedHashMap<String,Object>();x.put("type",c.getClass().getName());x.put("message",c.getMessage());if(c instanceof SQLException s){x.put("sqlState",s.getSQLState());x.put("code",s.getErrorCode());}chain.add(x);}var sw=new StringWriter();e.printStackTrace(new PrintWriter(sw));exceptions.add(Map.of("causeChain",chain,"stackTrace",sw.toString()));}
 public static void main(String[] args)throws Exception{
  var output=new LinkedHashMap<String,Object>();int exit=0;
  try{
   Class<?> klass=Class.forName("cn.xinji.agentplatform.security.product.ProductComparisonStartSchema");
   var resource=klass.getResource("ProductComparisonStartSchema.class");String sha;
   try(var in=resource.openStream()){sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}
   output.put("codeSource",klass.getProtectionDomain().getCodeSource().getLocation().toString());output.put("classResource",resource.toString());output.put("loadedClassSha256",sha);output.put("javaClassPath",System.getProperty("java.class.path"));
   if(!sha.equals("0ef5d9897f91010da46ddd220ef1ba3093965698b69032a634fb912591375f82"))throw new IllegalStateException("Loaded binary is not exact round4 new-SHA compiled class");
   Method required=klass.getDeclaredMethod("requiredClause",String.class);required.setAccessible(true);output.put("requiredClauseMethodExists",true);
   var samples=new ArrayList<Map<String,Object>>();String[] clauses={" (ATTEMPT_ID IS NULL OR NOT (FENCING_TOKEN IS NULL))"," (ATTEMPT_ID IS NULL  OR  NOT (FENCING_TOKEN IS NULL ) )","(attempt_id IS NULL OR fencing_token IS NOT NULL)","(attempt_id IS NULL OR fencing_token>0)","(attempt_id IS NULL OR fencing_token IS NOT NULL OR TRUE)","NULL",null};
   for(int i=0;i<clauses.length;i++){boolean value=(Boolean)required.invoke(null,(Object)clauses[i]);var item=new LinkedHashMap<String,Object>();item.put("clause",clauses[i]);item.put("actualReflectionResult",value);item.put("expected",i<3);samples.add(item);if(value!=(i<3))throw new IllegalStateException("Unexpected reflection result for sample "+i);}output.put("reflectedSamples",samples);
   var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:dm://127.0.0.1:5492","STARTMAIN_SVC",System.getenv("V103_STARTMAIN_SVC_PASSWORD")){
    @Override public Connection getConnection()throws SQLException{var c=super.getConnection();try{c.setSchema("STARTMAIN");return c;}catch(SQLException e){c.close();throw e;}}
   });
   output.put("actualRuntimeAccount","STARTMAIN_SVC");output.put("actualVendor",db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName()));
   output.put("actualDictionaryRows",db.queryForList("SELECT OWNER,TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE,STATUS,SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER='STARTMAIN' AND TABLE_NAME='CON5_START_COMMAND' AND CONSTRAINT_NAME='CK_CON5_START_FENCE_PRESENT'"));
   Method verify=klass.getDeclaredMethod("verify",JdbcTemplate.class,String.class);verify.setAccessible(true);
   try{verify.invoke(null,db,"STARTMAIN");}catch(InvocationTargetException e){throw e.getCause();}output.put("actualRuntimeVerifyPassed",true);
  }catch(Throwable e){exit=2;exception(e);output.put("actualRuntimeVerifyPassed",false);}
  output.put("actualExceptions",exceptions);output.put("exitCode",exit);output.put("noDdlOrDml",true);output.put("noStorageCounterexamplesRepeated",true);output.put("inheritsStorageCoverage","probe-fixed-round4/execution-result.json");
  Files.write(Path.of(System.getenv("V103_OUTPUT"),"binary-and-actual-gate-result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(output),StandardOpenOption.CREATE_NEW);
  System.out.println("Loaded codeSource="+output.get("codeSource"));System.out.println("Loaded classResource="+output.get("classResource"));System.out.println("Loaded class SHA="+output.get("loadedClassSha256"));System.out.println("Reflection samples="+JSON.writeValueAsString(output.get("reflectedSamples")));System.out.println("Actual runtime gate="+output.get("actualRuntimeVerifyPassed")+"; actual exception count="+exceptions.size()+"; exit="+exit);System.exit(exit);
 }
}
