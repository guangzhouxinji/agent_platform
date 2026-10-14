package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Reads actual current product records and attempt artifacts. Missing real input fails, never skips. */
class DocumentRuleAttemptRuntimeDm8Test {
    @Test void browserExecutionAttemptsPersistWithArtifactsAndAuditWithoutBusinessReceiptInflation() throws Exception {
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var json=new ObjectMapper().findAndRegisterModules();
        var browser=json.readTree(Files.readAllBytes(Path.of(required("ENF_DOCUMENT_RULE_ATTEMPT_BROWSER_RESULT"))));assertTrue(browser.path("passed").asBoolean());
        String id=browser.path("ruleId").asText();assertFalse(id.isBlank());
        var rows=db.queryForList("SELECT attempt_id,rule_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",id);
        var events=db.queryForList("SELECT attempt_id,rule_id,revision,actor_ref,action_code,outcome FROM enf10_document_rule_attempt_event WHERE rule_id=? ORDER BY created_at,attempt_id",id);
        assertEquals(3,rows.size());assertEquals(3,events.size());
        Path root=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();
        for(int i=0;i<rows.size();i++){
            var row=rows.get(i);var event=events.get(i);
            for(String key:new String[]{"ATTEMPT_ID","RULE_ID","REVISION","ACTOR_REF","ACTION_CODE","OUTCOME"})assertEquals(row.get(key),event.get(key));
            Path file=root.resolve((String)row.get("ARTIFACT_KEY")).normalize();assertTrue(file.startsWith(root));
            byte[] raw=Files.readAllBytes(file);assertEquals(row.get("ARTIFACT_SHA256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            var payload=json.readTree(raw);assertEquals(json.readTree((String)row.get("PAYLOAD")),payload);
            assertEquals(row.get("ACTOR_REF"),payload.path("actor").asText());assertEquals(row.get("ATTEMPT_ID"),payload.path("id").asText());
            String action=payload.path("action").asText().toLowerCase();boolean found=false;
            for(var record:browser.path("records"))if(record.path("path").asText().endsWith("/"+action)){
                if(i==2&&record.path("status").asInt()==409)found=true;
                if(i<2&&record.path("status").asInt()==200){
                    com.fasterxml.jackson.databind.node.ObjectNode body=record.path("body").deepCopy();body.remove("ruleRevision");
                    if(body.equals(payload.path(i==0?"validation":"trial")))found=true;
                }
            }
            assertTrue(found,"Actual HTTP result must match persistent immutable attempt");
            assertEquals(i==2?"FAILED":"COMPLETED",payload.path("outcome").asText());
            if(i==2){assertEquals("RULE_NOT_USABLE",payload.path("failureCode").asText());assertTrue(payload.path("trial").isNull());}
        }
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM enf10_document_rule_receipt WHERE rule_id=?",Integer.class,id));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM enf10_document_rule_event WHERE rule_id=?",Integer.class,id));
        json.writeValue(Path.of(required("ENF_DOCUMENT_RULE_ATTEMPT_OUTPUT")).toFile(),java.util.Map.of("ruleId",id,"attempts",rows,"attemptEvents",events,"scope","REAL_PRODUCT_ATTEMPT_DB_FILE_SHA_HTTP_RECONCILIATION","businessAcceptance","PENDING"));
    }
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
