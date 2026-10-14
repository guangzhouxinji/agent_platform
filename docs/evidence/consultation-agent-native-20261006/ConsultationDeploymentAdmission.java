import java.nio.file.*;
import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
public class ConsultationDeploymentAdmission {
 public static void main(String[] args)throws Exception {
  var file=Path.of(args[0]);if(Files.exists(file))throw new IllegalStateException("PRESERVE_REPORT");var report=new LinkedHashMap<String,Object>();report.put("observedAt",Instant.now().toString());report.put("scope","EXACT_PRODUCT_CURRENT_ADMISSION_READONLY_NO_GRANTS_OR_SOURCE_DECISIONS");
  try(var c=ConsultationNativeMigration.open("CON_NATIVE_PRIMARY",5301,"CONPROD");var a=ConsultationNativeMigration.open("CON_NATIVE_AUTHORITY",5302,"CONAUTH_SVC")){
   report.put("maintainer",ConsultationNativeMigration.rows(a,"SELECT subject_ref,organization,certificate_sha,allowed_roles,status,version FROM CONAUTH.id2_maintainer WHERE singleton_key=1"));
   report.put("nativeRunCounts",ConsultationNativeMigration.rows(c,"SELECT COUNT(*) AS TOTAL FROM AGENT_RUN_HEAD"));report.put("nativeRunRevisionCounts",ConsultationNativeMigration.rows(c,"SELECT COUNT(*) AS TOTAL FROM AGENT_RUN_REVISION"));
   report.put("nativeModelFenceCounts",ConsultationNativeMigration.rows(c,"SELECT COUNT(*) AS TOTAL FROM CON3_MODEL_INVOCATION"));
   report.put("newTaskOrGrantCreated",false);report.put("sourceDecisionChanged",false);
  }
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),report);
 }
}
