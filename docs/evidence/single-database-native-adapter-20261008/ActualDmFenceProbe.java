import java.io.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** No DML/DDL, raw rows, source originals or backup body. Actual DM8 lock semantics only. */
public final class ActualDmFenceProbe {
 static final ExecutorService IO=Executors.newSingleThreadExecutor();
 static String line(BufferedReader r)throws Exception{return IO.submit(r::readLine).get(20,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception {
  Process child=null;
  try {
   child=new ProcessBuilder(System.getenv("ENGINEERING_JAVA"),"-Xmx128m","-cp",System.getenv("ENGINEERING_CP"),"SingleDatabaseFence")
       .redirectError(ProcessBuilder.Redirect.DISCARD).start();
   BufferedWriter out=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));
   BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
   out.write("CONPROD,CONAUTH,ENFPROD,ENFAUTH,ADJPROD,ADJAUTH\n");out.flush();
   String held=line(in);if(held==null||!held.matches("HELD [a-f0-9]{64} [1-9][0-9]*"))throw new Exception("ACTUAL_FENCE_NOT_HELD");
   String table;
   try(Connection c=DriverManager.getConnection(System.getenv("LIFECYCLE_FENCE_URL"),System.getenv("LIFECYCLE_FENCE_USER"),System.getenv("LIFECYCLE_FENCE_PASSWORD"))) {
    c.setAutoCommit(false);
    try(PreparedStatement p=c.prepareStatement("SELECT TABLE_NAME FROM DBA_TABLES WHERE OWNER='CONPROD' ORDER BY TABLE_NAME")) {
     p.setQueryTimeout(5);try(ResultSet r=p.executeQuery()){if(!r.next())throw new Exception("NO_ENGINEERING_TABLE");table=r.getString(1);}
    }
    if(!table.matches("[A-Z][A-Z0-9_]{0,63}"))throw new Exception("INVALID_TABLE");
    boolean blocked=false;String state="";int vendor=0;
    try(Statement s=c.createStatement()) {s.setQueryTimeout(3);s.execute("LOCK TABLE \"CONPROD\".\""+table+"\" IN EXCLUSIVE MODE");}
    catch(SQLException expected){blocked=true;state=expected.getSQLState();vendor=expected.getErrorCode();}
    c.rollback();if(!blocked)throw new Exception("COMPETING_EXCLUSIVE_NOT_BLOCKED");
    out.write("CHECK\n");out.flush();if(!held.equals(line(in)))throw new Exception("HELD_CHECK_DRIFT");
    out.write("RELEASE\n");out.flush();String release=line(in);if(!release.equals(held.replace("HELD ","RELEASED ")))throw new Exception("RELEASE_NOT_CONFIRMED");
    if(!child.waitFor(10,TimeUnit.SECONDS))throw new Exception("FENCE_PROCESS_NOT_RELEASED");
    try(Statement s=c.createStatement()){s.setQueryTimeout(3);s.execute("LOCK TABLE \"CONPROD\".\""+table+"\" IN EXCLUSIVE MODE");}finally{c.rollback();}
    System.out.println("{\"actualDm8\":true,\"allSixSchemasLocked\":true,\"tableCount\":"+held.split(" ")[2]+",\"tableSetSha256\":\""+held.split(" ")[1]+"\",\"competingExclusiveBlocked\":true,\"heldRecheck\":true,\"exclusiveAfterRelease\":true,\"vendorCode\":"+vendor+",\"writeStatements\":0,\"nativeExportPerformed\":false,\"aggregateRecoveryProven\":false}");
   }
  }catch(Throwable failure){System.out.println("{\"passed\":false,\"code\":\"ACTUAL_DM8_FENCE_PROBE_REFUSED\"}");throw new IllegalStateException("ACTUAL_DM8_FENCE_PROBE_REFUSED");}
  finally{if(child!=null)child.destroyForcibly();IO.shutdownNow();}
 }
}
