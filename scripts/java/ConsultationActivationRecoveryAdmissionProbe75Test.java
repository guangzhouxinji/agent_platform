import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

public final class ConsultationActivationRecoveryAdmissionProbe75Test {
  static int passed;static String phase="SOURCE_IDLE",mode="0",schema="SYSDBA",version="8.1.4.170",user="SYSDBA";static long owner=1;static List<String> ids=List.of("9223372036854775807"),users=List.of("SYSDBA");
  static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},h));}
  static ResultSet rows(List<List<String>> rows){int[] at={-1};return proxy(ResultSet.class,(o,m,a)->switch(m.getName()){case "next"->++at[0]<rows.size();case "getString"->rows.get(at[0]).get((Integer)a[0]-1);case "getLong"->Long.parseLong(rows.get(at[0]).get((Integer)a[0]-1));case "wasNull"->false;case "close"->null;default->throw new AssertionError(m.getName());});}
  static Connection connection(){return proxy(Connection.class,(o,m,a)->switch(m.getName()){
    case "getSchema"->schema;case "getAutoCommit"->true;
    case "getMetaData"->proxy(DatabaseMetaData.class,(p,n,b)->switch(n.getName()){case "getURL"->ConsultationActivationRecoveryAdmissionProbe75.url(phase);case "getUserName"->user;case "getDatabaseProductVersion"->version;case "getDatabaseProductName"->"DM DBMS";default->throw new AssertionError(n.getName());});
    case "prepareStatement"->proxy(PreparedStatement.class,(p,n,b)->switch(n.getName()){
      case "close","setQueryTimeout"->null;case "executeQuery"->{String sql=(String)a[0];if(sql.contains("V$DM_INI"))yield rows(List.of(List.of(mode)));if(sql.contains("V$SESSIONS")){List<List<String>> r=new ArrayList<>();for(int i=0;i<ids.size();i++)r.add(List.of(ids.get(i),users.get(i)));yield rows(r);}if(sql.contains("SYS.SYSOBJECTS"))yield rows(List.of(List.of(String.valueOf(owner))));throw new AssertionError("UNAPPROVED_SQL");}
      default->throw new AssertionError("NON_SELECT_OPERATION:"+n.getName());});
    case "close"->null;default->throw new AssertionError("NON_READ_CONNECTION:"+m.getName());
  });}
  static void good(String name)throws Exception {ConsultationActivationRecoveryAdmissionProbe75.inspect(connection(),phase);passed++;System.out.println("PASS "+name);}
  static void bad(String name)throws Exception {boolean refused=false;try{ConsultationActivationRecoveryAdmissionProbe75.inspect(connection(),phase);}catch(Exception e){refused=true;}if(!refused)throw new AssertionError(name);passed++;System.out.println("PASS refuses "+name);}
  public static void main(String[] args)throws Exception {
    var first=ConsultationActivationRecoveryAdmissionProbe75.inspect(connection(),phase);if(!first.get("sessionIds").equals(ids))throw new AssertionError("BIGINT_STRING");passed++;System.out.println("PASS exact BIGINT string without SQL_TEXT");
    phase="SOURCE_HELD";ids=List.of("1","2");users=List.of("SYSDBA","SYSDBA");good("held two SYSDBA sessions");
    phase="TARGET_IMPORTED";ids=List.of("3");users=List.of("SYSDBA");good("target sole session");
    phase="ARBITRARY";bad("phase");phase="SOURCE_IDLE";
    mode="1";bad("mode");mode="0";
    schema="ACTR66_EMPTY";bad("schema");schema="SYSDBA";
    version="8.1.3.140";bad("version");version="8.1.4.170";
    user="CONPROD";bad("user");user="SYSDBA";
    owner=0;bad("owner calibration");owner=1;
    ids=List.of("1","2");users=List.of("SYSDBA","SYSDBA");bad("extra session");
    phase="SOURCE_HELD";ids=List.of("1","1");bad("duplicate session");
    ids=List.of("1","2");users=List.of("SYSDBA","CONPROD");bad("unowned session");
    System.out.println("ADMISSION75_JAVA_COMPONENTS="+passed+"; DM8_CONNECTIONS=0; SQL_WRITES=0; SESSION_MODEL=EXPLICIT_JDBC_PROXY");
  }
}
