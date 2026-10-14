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

/** Exact source/physical-restore ledger. No fixture, optional environment or skip path. */
class DocumentRuleRecoveryRuntimeDm8Test {
    @Test void activeProductAndEveryAttemptOriginalReceiptAndEventReconcile() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var browser=json.readTree(Files.readAllBytes(Path.of(required("ENF_DOCUMENT_RULE_RECOVERY_BASELINE"))));
        assertTrue(browser.path("passed").asBoolean());assertEquals("recovery_seed",browser.path("scenario").asText());
        String id=browser.path("ruleId").asText();assertFalse(id.isBlank());
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var rows=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->rs.getString(1),id);
        int expectedRules=System.getenv("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES")==null?2:Integer.parseInt(System.getenv("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_RULES"));
        assertTrue(expectedRules==2||expectedRules==3);assertEquals(expectedRules,rows.size());assertEquals("DRAFT",json.readTree(rows.getFirst()).path("state").asText());
        var rule=json.readTree(rows.getLast());assertEquals(expectedRules==2?"PUBLISHED":"REVIEW_REQUIRED",rule.path("state").asText());assertEquals(expectedRules,rule.path("revision").asInt());
        assertEquals(browser.path("db").path("history"),json.valueToTree(rows.subList(0,2).stream().map(p->{try{return json.readTree(p);}catch(Exception e){throw new IllegalStateException(e);}}).toList()));
        var receipts=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);
        assertEquals(expectedRules,receipts.size());assertEquals(receipts,events);
        var attempts=db.queryForList("SELECT attempt_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",id);
        int expectedAttempts=System.getenv("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS")==null?2:Integer.parseInt(System.getenv("ENF_DOCUMENT_RULE_RECOVERY_EXPECTED_ATTEMPTS"));
        assertEquals(expectedAttempts,attempts.size());Path root=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();
        for(var row:attempts){
            var audit=db.queryForMap("SELECT revision,actor_ref,action_code,outcome FROM enf10_document_rule_attempt_event WHERE attempt_id=? AND rule_id=?",row.get("ATTEMPT_ID"),id);
            for(String key:new String[]{"REVISION","ACTOR_REF","ACTION_CODE","OUTCOME"})assertEquals(row.get(key),audit.get(key));
            Path file=root.resolve((String)row.get("ARTIFACT_KEY")).normalize();assertTrue(file.startsWith(root));byte[] raw=Files.readAllBytes(file);
            assertEquals(row.get("ARTIFACT_SHA256"),sha(raw));assertEquals(json.readTree((String)row.get("PAYLOAD")),json.readTree(raw));
        }
        var template=json.readTree(db.queryForObject("SELECT payload FROM enf9_document_template_revision WHERE template_id=? AND revision=?",String.class,rule.path("templateId").asText(),rule.path("templateRevision").asLong()));
        Path original=root.resolve(template.path("fileKey").asText()).normalize();assertTrue(original.startsWith(root));String templateSha=sha(Files.readAllBytes(original));assertEquals(rule.path("templateSha256").asText(),templateSha);
        Path catalogFile=Path.of(required("ENF_DOCUMENT_RULE_FINAL_CATALOG"));var catalog=json.readTree(Files.readAllBytes(catalogFile));var source=catalog.path("schemas").get(0);
        Path sourceFile=Path.of(source.path("sourceFile").asText());if(!sourceFile.isAbsolute())sourceFile=catalogFile.getParent().resolve(sourceFile).normalize();String sourceSha=sha(Files.readAllBytes(sourceFile));assertEquals(source.path("sourceSha256").asText(),sourceSha);
        json.writeValue(Path.of(required("ENF_DOCUMENT_RULE_RECOVERY_OUTPUT")).toFile(),java.util.Map.of("ruleId",id,"rules",rows,"receipts",receipts,"events",events,"attempts",attempts,"templateSha256",templateSha,"sourceSha256",sourceSha,"templateFileKey",template.path("fileKey").asText(),"businessAcceptance","PENDING"));
    }
    private static String sha(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
