package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** Existing final-contract service dispatch components ONLY: no DataSource, JDBC connection or SQL execution.
 * No certificate issuer/current authority/physical rollback or process-restart acceptance is claimed. */
public final class ConsultationSourceCandidateBoundaryTest {
  static int passed;
  static final Instant NOW=Instant.parse("2026-10-11T02:00:00Z");
  interface Checked {void run();}
  static void require(boolean yes){if(!yes)throw new AssertionError("SOURCE_REGISTRY_BOUNDARY_REFUSED");}
  static void good(String name,Checked check){check.run();passed++;System.out.println("PASS "+name);}
  static void conflict(Checked call){try{call.run();}catch(ResponseStatusException e){require(e.getStatusCode().value()==409);return;}throw new AssertionError("Expected conflict");}
  static final class DispatchDb extends JdbcTemplate {
    ProductSourceCandidates.Candidate current;boolean casLost=false,readFailed=false;final List<String> attemptedStatements=new ArrayList<>();
    @Override public <T>T queryForObject(String sql,Class<T> type){require(sql.startsWith("SELECT COUNT(*)"));return type.cast(Integer.valueOf(0));}
    @Override @SuppressWarnings("unchecked") public <T>List<T> query(String sql,RowMapper<T> mapper,Object... args){
      if(readFailed)throw new DataAccessResourceFailureException("COMPONENT_ONLY_READ_FAILED");
      if(sql.contains("con4_source_request"))return List.of();
      require(sql.contains("con4_source_contract"));return current==null?List.of():List.of((T)current);
    }
    @Override public int update(String sql,Object... args){attemptedStatements.add(sql);require(sql.startsWith("UPDATE con4_source_contract")&&sql.contains("status='CANDIDATE'")&&sql.contains("row_version=1"));return casLost?0:1;}
  }
  static final class DispatchTransactions extends TransactionTemplate {
    int entered;
    @Override public <T>T execute(TransactionCallback<T> callback){entered++;return callback.doInTransaction(new SimpleTransactionStatus());}
  }
  static ProductSourceCandidates.Candidate fixture(String status,long version){
    var declaration=new ProductSourceCandidates.Declaration("source-registry-component","v1","capture1",
      "self-authored-component-metadata","component-owner","COMPONENT_ONLY_NOT_A_LICENSE","a".repeat(64),
      Map.of("jurisdiction",List.of("CN")),List.of("title"),Map.of("title",List.of("DISPLAY")),List.of(),
      false,false,false,false,true,NOW.minusSeconds(60),NOW.plusSeconds(3600));
    return new ProductSourceCandidates.Candidate(declaration.sourceId(),declaration.contractVersion(),declaration.sourceVersion(),status,version,declaration,"COMPONENT_ONLY_NOT_DEPLOYMENT_RETENTION",1,"b".repeat(64));
  }
  static ProductSourceCandidates service(DispatchDb db,DispatchTransactions transactions){return new ProductSourceCandidates(db,transactions,new ObjectMapper(),new ProductRetentionPolicy("COMPONENT_ONLY_NOT_DEPLOYMENT_RETENTION",1,"b".repeat(64),Duration.ofHours(2)),Clock.fixed(NOW,ZoneOffset.UTC));}
  static void withdraw(ProductSourceCandidates service,long version){service.withdraw("source-registry-component","v1",version,"component-request","COMPONENT_ONLY_NOT_ACTUAL_CERTIFICATE");}
  public static void main(String[] args){
    require(args.length==0);
    good("wrong CAS refuses before transaction or dispatch",()->{var db=new DispatchDb();db.current=fixture("CANDIDATE",1);var tx=new DispatchTransactions();var s=service(db,tx);conflict(()->withdraw(s,2));require(tx.entered==0&&db.attemptedStatements.isEmpty());});
    good("absent candidate cannot create or withdraw",()->{var db=new DispatchDb();var tx=new DispatchTransactions();conflict(()->withdraw(service(db,tx),1));require(tx.entered==1&&db.attemptedStatements.isEmpty());});
    for(String state:List.of("WITHDRAWN","ACTIVE"))good(state+" authority state cannot be changed by candidate withdraw",()->{var db=new DispatchDb();db.current=fixture(state,2);var tx=new DispatchTransactions();conflict(()->withdraw(service(db,tx),1));require(db.attemptedStatements.isEmpty()&&db.current.status().equals(state));});
    good("current candidate version drift refuses before update",()->{var db=new DispatchDb();db.current=fixture("CANDIDATE",2);conflict(()->withdraw(service(db,new DispatchTransactions()),1));require(db.attemptedStatements.isEmpty());});
    good("CAS zero cannot dispatch event or receipt",()->{var db=new DispatchDb();db.current=fixture("CANDIDATE",1);db.casLost=true;conflict(()->withdraw(service(db,new DispatchTransactions()),1));require(db.attemptedStatements.size()==1&&db.current.version()==1&&db.current.status().equals("CANDIDATE"));});
    good("new service instance reads unchanged pending metadata, never promotes",()->{var db=new DispatchDb();db.current=fixture("CANDIDATE",1);var first=service(db,new DispatchTransactions()).read("source-registry-component","v1");var second=service(db,new DispatchTransactions()).read("source-registry-component","v1");require(first.equals(second)&&second.status().equals("CANDIDATE")&&db.attemptedStatements.isEmpty());});
    good("read failure returns no candidate or write",()->{var db=new DispatchDb();db.current=fixture("CANDIDATE",1);var s=service(db,new DispatchTransactions());db.readFailed=true;try{s.read("source-registry-component","v1");throw new AssertionError("Cannot return success");}catch(DataAccessResourceFailureException expected){require(db.attemptedStatements.isEmpty());}});
    System.out.println("SOURCE_REGISTRY_NEW_BOUNDARY_COMPONENTS="+passed+"; CONNECTIONS=0; SQL_EXECUTIONS=0; REAL_ROLLBACK_OR_RESTART_PROVEN=false; CURRENT_AUTHORITY_PROVEN=false");
  }
}
