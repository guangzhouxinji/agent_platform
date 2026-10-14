import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** Closed, SELECT-only admission metadata. No SQL text or credential is returned. */
public final class ConsultationActivationRecoveryAdmissionProbe75 {
  static final String SOURCE="jdbc:dm://agent-platform-single-final-primary-20261009:5236";
  static final String TARGET="jdbc:dm://agent-platform-single-final-restore-20261009:5236";
  static void require(boolean ok,String code)throws Exception {if(!ok)throw new Exception(code);}
  static String url(String phase)throws Exception {
    require(Set.of("SOURCE_IDLE","SOURCE_HELD","TARGET_IMPORTED").contains(phase),"ADMISSION_PHASE_REFUSED");
    return phase.equals("TARGET_IMPORTED")?TARGET:SOURCE;
  }
  static Map<String,Object> inspect(Connection c,String phase)throws Exception {
    String expected=url(phase);DatabaseMetaData m=c.getMetaData();
    require(expected.equals(m.getURL())&&"SYSDBA".equals(m.getUserName())&&"8.1.4.170".equals(m.getDatabaseProductVersion())&&m.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("dm")&&"SYSDBA".equals(c.getSchema())&&c.getAutoCommit(),"ADMISSION_CONNECTION_REFUSED");
    try(PreparedStatement q=c.prepareStatement("SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='COMPATIBLE_MODE'")){
      q.setQueryTimeout(15);try(ResultSet r=q.executeQuery()){require(r.next()&&"0".equals(r.getString(1))&&!r.next(),"ADMISSION_MODE_REFUSED");}
    }
    List<String> sessions=new ArrayList<>();
    try(PreparedStatement q=c.prepareStatement("SELECT SESS_ID,USER_NAME FROM V$SESSIONS")){
      q.setQueryTimeout(15);try(ResultSet r=q.executeQuery()){while(r.next()){
        String id=r.getString(1);require(id!=null&&id.matches("[0-9]{1,20}")&&"SYSDBA".equals(r.getString(2))&&!sessions.contains(id)&&sessions.size()<2,"ADMISSION_SESSION_REFUSED");sessions.add(id);
      }}
    }
    require(sessions.size()==(phase.equals("SOURCE_HELD")?2:1),"ADMISSION_SESSION_COUNT_REFUSED");
    try(PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM SYS.SYSOBJECTS S JOIN SYS.SYSOBJECTS U ON S.PID=U.ID WHERE S.NAME='SYSDBA' AND S.TYPE$='SCH' AND U.NAME='SYSDBA' AND U.TYPE$='UR' AND U.SUBTYPE$='USER'")){
      q.setQueryTimeout(15);try(ResultSet r=q.executeQuery()){require(r.next()&&r.getLong(1)==1&&!r.wasNull()&&!r.next(),"ADMISSION_OWNER_CALIBRATION_REFUSED");}
    }
    Collections.sort(sessions);
    return Map.of("format","ACTR75_NATIVE_ADMISSION/v1","phase",phase,"url",expected,"databaseVersion","8.1.4.170","compatibleMode",0,"user","SYSDBA","schema","SYSDBA","sessionIds",sessions,"defaultOwnerCalibrated",true);
  }
  public static void main(String[] args){
    try {
      require(args.length==0,"ADMISSION_ARGUMENTS_REFUSED");String phase=System.getenv("ACTR75_PROBE_PHASE"),expected=url(phase);
      require(expected.equals(System.getenv("LIFECYCLE_JDBC_URL"))&&"SYSDBA".equals(System.getenv("LIFECYCLE_JDBC_USER")),"ADMISSION_ENVIRONMENT_REFUSED");
      String password=System.getenv("LIFECYCLE_JDBC_PASSWORD");require(password!=null&&!password.isEmpty(),"ADMISSION_MANAGED_CREDENTIAL_REQUIRED");
      try(Connection c=DriverManager.getConnection(expected,"SYSDBA",password)){System.out.println(new ObjectMapper().writeValueAsString(inspect(c,phase)));}
    }catch(Exception e){
      String code=e instanceof SQLException?"ADMISSION_NATIVE_QUERY_REFUSED":e.getMessage()!=null&&e.getMessage().matches("[A-Z][A-Z0-9_]{0,100}")?e.getMessage():"ADMISSION_PROBE_REFUSED";
      String state=e instanceof SQLException s&&s.getSQLState()!=null&&s.getSQLState().matches("[A-Z0-9]{5}")?s.getSQLState():"UNKNOWN";
      try{System.out.println(new ObjectMapper().writeValueAsString(Map.of("type","ERROR","code",code,"sqlState",state,"vendorCode",e instanceof SQLException s?s.getErrorCode():0,"automaticRetryAllowed",false)));}catch(Exception ignored){}
      System.exit(1);
    }
  }
}
