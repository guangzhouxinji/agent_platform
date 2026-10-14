package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only product-browser DB/file/receipt reconciliation, never an input setup fixture. */
@EnabledIfEnvironmentVariable(named="ENF_DOCUMENT_RULE_FINAL_ID",matches=".+")
class DocumentRuleRuntimeDm8Test {
    @Test void actualBrowserRulesTemplateSourceTrialReceiptsAndEventsReconcile() throws Exception {
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var json=new ObjectMapper().findAndRegisterModules();String id=required("ENF_DOCUMENT_RULE_FINAL_ID");
        var rules=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->{
            try{return json.readValue(rs.getString(1),Rule.class);}catch(Exception e){throw new IllegalStateException(e);}},id);
        assertEquals(3,rules.size());assertEquals(List.of(State.DRAFT,State.PUBLISHED,State.DISABLED),rules.stream().map(Rule::state).toList());
        assertEquals(List.of(1L,2L,3L),rules.stream().map(Rule::revision).toList());
        var first=rules.getFirst();var tpl=json.readTree(db.queryForObject("SELECT payload FROM enf9_document_template_revision WHERE template_id=? AND revision=?",String.class,first.templateId(),first.templateRevision()));
        assertEquals("PUBLISHED",tpl.path("state").asText());
        var fileRoot=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();
        var templateFile=fileRoot.resolve(tpl.path("fileKey").asText()).normalize();assertTrue(templateFile.startsWith(fileRoot));
        String templateSha=hash(Files.readAllBytes(templateFile));assertEquals(tpl.path("fileSha256").asText(),templateSha);
        for(var r:rules){assertEquals(templateSha,r.templateSha256());assertEquals(first.mappings(),r.mappings());assertEquals(first.schemaId(),r.schemaId());}
        var catalogFile=Path.of(required("ENF_DOCUMENT_RULE_FINAL_CATALOG"));var catalog=json.readValue(Files.readAllBytes(catalogFile),FileDocumentRuleInputs.Catalog.class);
        var snapshot=catalog.schemas().stream().filter(s->s.organization().equals(first.organization())&&s.schema().id().equals(first.schemaId())&&s.schema().version().equals(first.schemaVersion())).findFirst().orElseThrow();
        Path sourceFile=Path.of(snapshot.sourceFile());if(!sourceFile.isAbsolute())sourceFile=catalogFile.getParent().resolve(sourceFile).normalize();
        byte[] source=Files.readAllBytes(sourceFile);String sourceSha=hash(source);assertEquals(snapshot.sourceSha256(),sourceSha);var sourceValues=json.readTree(source);
        var browser=json.readTree(Files.readAllBytes(Path.of(required("ENF_DOCUMENT_RULE_BROWSER_RESULT"))));assertTrue(browser.path("passed").asBoolean());assertEquals(id,browser.path("ruleId").asText());
        com.fasterxml.jackson.databind.JsonNode trial=null;boolean rejectedDisabled=false;
        for(var record:browser.path("records")) {
            if(record.path("path").asText().endsWith("/trial")&&record.path("status").asInt()==200)trial=record.path("body");
            if(record.path("path").asText().endsWith("/trial")&&record.path("status").asInt()==409)rejectedDisabled=true;
        }
        assertNotNull(trial);assertTrue(trial.path("complete").asBoolean());assertTrue(trial.path("reviewRequired").asBoolean());assertEquals(6,trial.path("fields").size());assertTrue(rejectedDisabled);
        for(var field:trial.path("fields")) {
            var mapping=first.mappings().stream().filter(m->m.fieldId().equals(field.path("fieldId").asText())).findFirst().orElseThrow();
            var original=sourceValues.path(mapping.sourcePath());assertEquals(original.path("value"),field.path("value"));
            assertEquals(original.path("sourceRef"),field.path("sourceRef"));assertEquals(original.path("sourceVersion"),field.path("sourceVersion"));assertEquals(original.path("locator"),field.path("locator"));
        }
        var receipts=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);
        assertEquals(3,receipts.size());assertEquals(receipts,events);for(var r:receipts)assertEquals(required("ENF_ACCEPTANCE_SUBJECT"),r.get("ACTOR_REF"));
        json.writeValue(Path.of(required("ENF_DOCUMENT_RULE_FINAL_OUTPUT")).toFile(),java.util.Map.of("ruleId",id,"history",rules,"receipts",receipts,"events",events,"templateSha256",templateSha,"sourceSha256",sourceSha,"trial",trial,"disabledTrialStatus",409,"scope","REAL_PRODUCT_BROWSER_DB_FILE_RECONCILIATION_NOT_FINAL_ACCEPTANCE"));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required; never pass skipped");return value;}
}

