import java.sql.*;import java.time.*;import java.util.*;
import cn.xinji.agentplatform.adjudication.casework.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.Scope;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
/** Metadata diagnostic only. Does NOT bypass the denied product API or emit material text. */
public class NativeSourceDiagnosis {
 public static void main(String[] args)throws Exception {
  var mapper=new ObjectMapper().findAndRegisterModules();var codec=new CaseWorkCodec(mapper);State state;
  try(var c=DriverManager.getConnection("jdbc:dm://127.0.0.1:5301","ADJLLM20261003",System.getenv("ADJ_DIAG_PRIMARY_PASSWORD"))){c.setReadOnly(true);
   try(var s=c.createStatement();var r=s.executeQuery("SELECT r.payload,r.payload_sha256 FROM adj2_case_revision r JOIN adj2_case_work h ON h.case_id=r.case_id AND h.head_version=r.work_version WHERE h.case_id='adjudication-materials-case-01'")){if(!r.next())throw new IllegalStateException("EXACT_CASE_MISSING");String raw=r.getString(1);CaseWorkCodec.hash(raw,r.getString(2));state=codec.decode(raw,State.class);}
   try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM agent_run_head")){r.next();System.out.println("ACTUAL_RUN_HEAD_COUNT="+r.getLong(1));}
   try(var s=c.createStatement();var r=s.executeQuery("SELECT calls,input_tokens,output_tokens,cost_micros FROM adj3_model_budget WHERE case_id='adjudication-materials-case-01'")){r.next();System.out.println("UNCHANGED_MODEL_LEDGER="+r.getLong(1)+"/"+r.getLong(2)+"/"+r.getLong(3)+"/"+r.getLong(4));}
   try(var s=c.createStatement();var r=s.executeQuery("SELECT head_version,head_sha256 FROM adj2_case_work WHERE case_id='adjudication-materials-case-01'")){r.next();System.out.println("ACTUAL_CASE_HEAD="+r.getLong(1)+"/"+r.getString(2));}
  }
  var ds=new DriverManagerDataSource("jdbc:dm://127.0.0.1:5370","ADJAUTH20261004",System.getenv("ADJ_DIAG_AUTHORITY_PASSWORD"));
  var authority=new CaseSourceAuthority(new JdbcTemplate(ds),new TransactionTemplate(),codec,Clock.systemUTC());
  for(var source:codec.sources(state)){try {authority.require(Scope.of(state.caseInput()),List.of(source),"READ");System.out.println("SOURCE="+source.id()+"/"+source.version()+" READ_ALLOWED=true");}catch(SecurityException denied){System.out.println("SOURCE="+source.id()+"/"+source.version()+" READ_ALLOWED=false CODE="+denied.getMessage());}}
  System.out.println("DIAGNOSTIC_ONLY_NO_AUTHORIZATION_OR_SOURCE_CHANGE_NO_PRODUCT_READ_BYPASS");
 }
}
