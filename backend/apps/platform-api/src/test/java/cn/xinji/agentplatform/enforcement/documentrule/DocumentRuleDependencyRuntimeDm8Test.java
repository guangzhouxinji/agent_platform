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

class DocumentRuleDependencyRuntimeDm8Test {
    @Test void actualChangedTemplateCreatesOneImmutableReviewRevisionAndTwoAuditedAttempts() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var browser=json.readTree(Files.readAllBytes(Path.of(required("ENF_DOCUMENT_RULE_DEPENDENCY_RESULT"))));
        assertTrue(browser.path("passed").asBoolean());assertEquals("dependency",browser.path("scenario").asText());String id=browser.path("ruleId").asText();assertFalse(id.isBlank());
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var rules=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->rs.getString(1),id);assertEquals(3,rules.size());
        var published=json.readTree(rules.get(1));var review=json.readTree(rules.get(2));assertEquals("PUBLISHED",published.path("state").asText());assertEquals("REVIEW_REQUIRED",review.path("state").asText());assertEquals(published.path("mappings"),review.path("mappings"));
        var receipts=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);var events=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);assertEquals(3,receipts.size());assertEquals(receipts,events);assertEquals("DEPENDENCY_REVIEW",receipts.getLast().get("OPERATION"));
        var attempts=db.queryForList("SELECT attempt_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",id);assertEquals(4,attempts.size());assertEquals(2,attempts.stream().filter(a->"REVIEW_REQUIRED".equals(a.get("OUTCOME"))).count());
        Path root=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();
        for(var a:attempts){var audit=db.queryForMap("SELECT revision,actor_ref,action_code,outcome FROM enf10_document_rule_attempt_event WHERE attempt_id=? AND rule_id=?",a.get("ATTEMPT_ID"),id);for(String key:new String[]{"REVISION","ACTOR_REF","ACTION_CODE","OUTCOME"})assertEquals(a.get(key),audit.get(key));Path file=root.resolve((String)a.get("ARTIFACT_KEY")).normalize();assertTrue(file.startsWith(root));byte[] raw=Files.readAllBytes(file);assertEquals(a.get("ARTIFACT_SHA256"),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));assertEquals(json.readTree((String)a.get("PAYLOAD")),json.readTree(raw));}
        var templateRows=db.queryForList("SELECT revision,payload FROM enf9_document_template_revision WHERE template_id=? ORDER BY revision",published.path("templateId").asText());assertEquals(2,templateRows.size());
        var old=json.readTree((String)templateRows.getFirst().get("PAYLOAD"));var current=json.readTree((String)templateRows.getLast().get("PAYLOAD"));assertEquals(old.path("fileSha256"),current.path("fileSha256"));
        for(var item:templateRows){var template=json.readTree((String)item.get("PAYLOAD"));assertEquals(template.path("fileSha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(template.path("fileKey").asText())))));}
        json.writeValue(Path.of(required("ENF_DOCUMENT_RULE_DEPENDENCY_OUTPUT")).toFile(),java.util.Map.of("ruleId",id,"rules",rules,"receipts",receipts,"events",events,"attempts",attempts,"templateRevisions",templateRows,"businessAcceptance","PENDING"));
    }
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
