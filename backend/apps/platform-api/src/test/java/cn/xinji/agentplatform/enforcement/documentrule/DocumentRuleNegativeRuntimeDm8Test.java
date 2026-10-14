package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only reconciliation of R02 actual product-browser submissions; absence of input fails, never skips. */
class DocumentRuleNegativeRuntimeDm8Test {
    @Test void unknownPathCannotPublishOrWriteAnArtifact() throws Exception {
        reconcile("ENF_DOCUMENT_RULE_UNKNOWN_RESULT", "unknown_path", "UNKNOWN_SOURCE_PATH");
    }
    @Test void wrongTypeCannotPublishOrWriteAnArtifact() throws Exception {
        reconcile("ENF_DOCUMENT_RULE_TYPE_RESULT", "wrong_type", "TYPE_MISMATCH");
    }
    private void reconcile(String variable, String scenario, String diagnostic) throws Exception {
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL"); assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var json=new ObjectMapper().findAndRegisterModules();
        Path resultFile=Path.of(required(variable));var result=json.readTree(Files.readAllBytes(resultFile));
        assertTrue(result.path("passed").asBoolean()); assertEquals(scenario,result.path("scenario").asText());
        String id=result.path("ruleId").asText();assertFalse(id.isBlank());
        var payloads=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->rs.getString(1),id);
        assertEquals(1,payloads.size());var rule=json.readTree(payloads.getFirst());
        assertEquals("DRAFT",rule.path("state").asText());assertEquals(1,rule.path("revision").asInt());
        var receipts=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);
        assertEquals(1,receipts.size());assertEquals(receipts,events);assertEquals("SAVE_DOCUMENT_RULE",receipts.getFirst().get("OPERATION"));
        boolean validated=false,tried=false,rejected=false;
        for(var record:result.path("records")) {
            String path=record.path("path").asText();int status=record.path("status").asInt();
            if(path.endsWith("/validate")&&status==200){validated=true;assertFalse(record.path("body").path("valid").asBoolean());assertTrue(has(record.path("body").path("diagnostics"),diagnostic));}
            if(path.endsWith("/trial")&&status==200){tried=true;assertFalse(record.path("body").path("complete").asBoolean());assertEquals(0,record.path("body").path("fields").size());}
            if(path.endsWith("/publish")&&status==409)rejected=true;
            assertFalse(path.endsWith("/publish")&&status>=200&&status<300);
        }
        assertTrue(validated&&tried&&rejected);
        var files=json.readTree(Files.readAllBytes(resultFile.getParent().resolve("file-side-effects.json")));
        assertTrue(files.path("unchanged").asBoolean());assertEquals(files.path("before"),files.path("after"));assertTrue(files.path("after").size()>0);
        Path root=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();
        for(var file:files.path("after")){Path p=Path.of(file.path("path").asText()).toAbsolutePath().normalize();assertTrue(p.startsWith(root));assertEquals(file.path("sha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}
    }
    private static boolean has(JsonNode diagnostics,String code){for(var d:diagnostics)if(code.equals(d.path("code").asText())&&d.path("blocking").asBoolean()&&"task_ref".equals(d.path("fieldId").asText()))return true;return false;}
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required for real product reconciliation");return value;}
}
