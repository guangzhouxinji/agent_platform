import java.sql.*;
import java.util.*;
public class NativeAdmission {
  static void query(Connection c,String label,String sql) throws SQLException {
    if(!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT "))throw new IllegalArgumentException("SELECT_ONLY");
    System.out.println("QUERY "+label+" SQL="+sql);
    try(Statement s=c.createStatement()){s.setQueryTimeout(15);try(ResultSet r=s.executeQuery(sql)){
      var m=r.getMetaData();while(r.next()){for(int i=1;i<=m.getColumnCount();i++)System.out.print((i==1?"":" | ")+m.getColumnLabel(i)+"="+r.getString(i));System.out.println();}
    }}
  }
  public static void main(String[] args) throws Exception {
    Class.forName("dm.jdbc.driver.DmDriver");
    boolean failed=false;
    for(String kind:List.of("BUSINESS","AUTHORITY")){
      System.out.println("DATABASE "+kind);
      try(Connection c=DriverManager.getConnection(System.getenv("ENF_RO_"+kind+"_URL"),System.getenv("ENF_RO_"+kind+"_USER"),System.getenv("ENF_RO_"+kind+"_PASSWORD"))){
        c.setAutoCommit(false);
        try{c.setReadOnly(true);System.out.println("JDBC_READ_ONLY="+c.isReadOnly());}catch(SQLException e){System.out.println("JDBC_READ_ONLY_UNAVAILABLE SQLSTATE="+e.getSQLState());}
        query(c,"clock","SELECT CURRENT_TIMESTAMP AS DB_TIME FROM DUAL");
        String owner=kind.equals("BUSINESS")?"ENFACC_BIZ":"ENFACC_AUTH";
        List<String> tables=new ArrayList<>();
        try(PreparedStatement s=c.prepareStatement("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME")){s.setString(1,owner);try(ResultSet r=s.executeQuery()){while(r.next())tables.add(r.getString(1));}}
        System.out.println("TABLES="+String.join(",",tables));
        for(String table:tables){
          if(!table.matches("[A-Z][A-Z0-9_]*") || !table.matches(".*(AGENT|TASK|GRANT|SOURCE|SESSION|SUBJECT|KEY|MATERIAL|DOCUMENT|PROCEDURE|CASE|TEMPLATE|RULE|WATERMARK).*"))continue;
          List<String> cols=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID")){s.setString(1,owner);s.setString(2,table);try(ResultSet r=s.executeQuery()){while(r.next())cols.add(r.getString(1));}}
          System.out.println("COLUMNS "+table+"="+String.join(",",cols));
          String target=owner+"."+table;
          query(c,table+"_count","SELECT COUNT(*) AS ROW_COUNT FROM "+target);
          for(String col:List.of("VALID_UNTIL","EXPIRES_AT","RETAIN_UNTIL","RETAINED_UNTIL","RETENTION_UNTIL","REVOKED_AT"))if(cols.contains(col))
            query(c,table+"_"+col,"SELECT COUNT(*) AS WITH_DATE,MIN("+col+") AS EARLIEST,MAX("+col+") AS LATEST FROM "+target+" WHERE "+col+" IS NOT NULL");
          for(String col:List.of("STATUS","STATE","RESOURCE_TYPE","PURPOSE"))if(cols.contains(col))
            query(c,table+"_"+col,"SELECT "+col+",COUNT(*) AS ROW_COUNT FROM "+target+" GROUP BY "+col);
        }
        if(kind.equals("BUSINESS")){
          query(c,"runtime_tables","SELECT COUNT(*) AS PRESENT_RUNTIME_TABLES FROM ALL_TABLES WHERE OWNER='ENFACC_BIZ' AND TABLE_NAME IN ('AGENT_RUN_HEAD','AGENT_RUN_REVISION')");
          query(c,"live_grants_by_domain_purpose_action","SELECT DOMAIN_CODE,PURPOSE,RESOURCE_TYPE,ACTION_CODE,COUNT(*) AS LIVE_ROWS FROM ENFACC_BIZ.ID2_GRANT WHERE STATUS='ACTIVE' AND EXPIRES_AT>CURRENT_TIMESTAMP AND (PURPOSE='DOCUMENT_DRAFT' OR PURPOSE='PROCEDURE_CHECK') GROUP BY DOMAIN_CODE,PURPOSE,RESOURCE_TYPE,ACTION_CODE");
          query(c,"target_purpose_totals","SELECT PURPOSE,COUNT(*) AS TOTAL_ROWS,SUM(CASE WHEN STATUS='ACTIVE' AND EXPIRES_AT>CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS LIVE_ROWS FROM ENFACC_BIZ.ID2_GRANT WHERE PURPOSE IN ('DOCUMENT_DRAFT','PROCEDURE_CHECK') GROUP BY PURPOSE");
          query(c,"target_purpose_expiry","SELECT PURPOSE,STATUS,COUNT(*) AS EXPIRY_ROW_COUNT,MIN(EXPIRES_AT) AS EARLIEST_EXPIRY,MAX(EXPIRES_AT) AS LATEST_EXPIRY FROM ENFACC_BIZ.ID2_GRANT WHERE PURPOSE IN ('DOCUMENT_DRAFT','PROCEDURE_CHECK') GROUP BY PURPOSE,STATUS");
          query(c,"current_target_grants","SELECT COUNT(*) AS CURRENT_TARGET_ROWS FROM ENFACC_BIZ.ID2_GRANT g JOIN ENFACC_BIZ.ID2_CURRENT_EPOCH c ON c.CURRENT_GRANT_ID=g.GRANT_ID JOIN ENFACC_BIZ.ID2_SUBJECT u ON u.ISSUER=g.ISSUER AND u.SUBJECT_ID=g.SUBJECT_ID WHERE g.DOMAIN_CODE='ENFORCEMENT' AND g.PURPOSE IN ('DOCUMENT_DRAFT','PROCEDURE_CHECK') AND g.STATUS='ACTIVE' AND u.STATUS='ACTIVE' AND g.EXPIRES_AT>CURRENT_TIMESTAMP");
          query(c,"live_other_grants","SELECT PURPOSE,COUNT(*) AS LIVE_ROWS FROM ENFACC_BIZ.ID2_GRANT WHERE STATUS='ACTIVE' AND EXPIRES_AT>CURRENT_TIMESTAMP GROUP BY PURPOSE");
          query(c,"live_sessions","SELECT COUNT(*) AS LIVE_SESSIONS FROM ENFACC_BIZ.ID2_SESSION WHERE STATUS='ACTIVE' AND EXPIRES_AT>CURRENT_TIMESTAMP AND REVOKED_AT IS NULL");
          query(c,"live_sources","SELECT COUNT(*) AS UNEXPIRED_SOURCES FROM ENFACC_BIZ.ENF3_SOURCE WHERE EXPIRES_AT>CURRENT_TIMESTAMP");
          query(c,"live_materials","SELECT COUNT(*) AS UNEXPIRED_MATERIALS FROM ENFACC_BIZ.ENF3_MATERIAL WHERE EXPIRES_AT>CURRENT_TIMESTAMP");
          query(c,"draft_review_states","SELECT REVIEW_STATE,COUNT(*) AS REVISION_ROWS FROM ENFACC_BIZ.ENF12_DOCUMENT_DRAFT_REVISION GROUP BY REVIEW_STATE");
        }else{
          query(c,"live_delete_watermarks","SELECT COUNT(*) AS LIVE_READY_ROWS FROM ENFACC_AUTH.ENF3_DELETE_WATERMARK WHERE STATE='READY' AND VALID_UNTIL>CURRENT_TIMESTAMP");
          query(c,"issuer_epoch_matches","SELECT GENERATION,STATE FROM ENFACC_AUTH.ID2_AUTHORITY_WATERMARK");
        }
        c.rollback();
      }catch(SQLException e){failed=true;System.out.println("DATABASE_OR_QUERY_FAILED SQLSTATE="+e.getSQLState()+" ERROR_CODE="+e.getErrorCode());}
    }
    if(failed)throw new IllegalStateException("READ_ONLY_SELECT_FAILED");
  }
}
