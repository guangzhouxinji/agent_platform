package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Reconciles actual final-product browser negatives. Missing real evidence fails, never skips. */
class DocumentRuleFrozenNegativeRuntimeDm8Test {
    @ParameterizedTest
    @ValueSource(strings={"unknown_path","wrong_type","missing_party","missing_date","missing_material","no_interview","unconfirmed_amount","unconfirmed_legal","unreadable_source"})
    void frozenFailureHasNoFabricatedFieldOrBusinessTransition(String scenario) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var manifest=json.readTree(Files.readAllBytes(Path.of(required("ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST"))));
        Path input=Path.of(manifest.path(scenario).asText());assertTrue(Files.isRegularFile(input));
        var browser=json.readTree(Files.readAllBytes(input));assertTrue(browser.path("passed").asBoolean());assertEquals(scenario,browser.path("scenario").asText());
        String id=browser.path("ruleId").asText();assertFalse(id.isBlank());
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");assertTrue(url.startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(new DriverManagerDataSource(url,required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD")));
        var rules=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->rs.getString(1),id);
        assertEquals(1,rules.size());assertEquals("DRAFT",json.readTree(rules.getFirst()).path("state").asText());
        var receipts=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,actor_ref,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);
        assertEquals(1,receipts.size());assertEquals(receipts,events);assertEquals("SAVE_DOCUMENT_RULE",receipts.getFirst().get("OPERATION"));
        var attempts=db.queryForList("SELECT attempt_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",id);
        assertEquals(2,attempts.size());
        for(var row:attempts){
            var audit=db.queryForMap("SELECT revision,actor_ref,action_code,outcome FROM enf10_document_rule_attempt_event WHERE attempt_id=? AND rule_id=?",row.get("ATTEMPT_ID"),id);
            for(String key:new String[]{"REVISION","ACTOR_REF","ACTION_CODE","OUTCOME"})assertEquals(row.get(key),audit.get(key));
            Path root=Path.of(required("ENF_DOCUMENT_RULE_TEMPLATE_ROOT")).toAbsolutePath().normalize();Path file=root.resolve((String)row.get("ARTIFACT_KEY")).normalize();assertTrue(file.startsWith(root));
            byte[] raw=Files.readAllBytes(file);assertEquals(row.get("ARTIFACT_SHA256"),sha(raw));assertEquals(json.readTree((String)row.get("PAYLOAD")),json.readTree(raw));
            if(scenario.equals("unreadable_source")){assertEquals("FAILED",row.get("OUTCOME"));assertEquals("DEPENDENCY_UNAVAILABLE",json.readTree(raw).path("failureCode").asText());}
        }
        boolean validate=false,trial=false,rejected=false;boolean missing=scenario.startsWith("missing_");
        for(var record:browser.path("records")){
            String path=record.path("path").asText();int status=record.path("status").asInt();var body=record.path("body");
            if(path.endsWith("/validate")){validate=true;if(scenario.equals("unreadable_source"))assertEquals(503,status);else{assertEquals(200,status);assertEquals(missing,body.path("valid").asBoolean());}}
            if(path.endsWith("/trial")){trial=true;if(scenario.equals("unreadable_source"))assertEquals(503,status);else{assertEquals(200,status);assertFalse(body.path("complete").asBoolean());
                String absent=switch(scenario){case "missing_party"->"party_name";case "missing_date"->"recorded_date";case "missing_material"->"materials[].ref";default->"";};
                if(!missing)assertEquals(0,body.path("fields").size());else for(var field:body.path("fields"))assertNotEquals(absent,field.path("fieldId").asText());
            }}
            if(path.endsWith("/publish")){assertEquals(409,status);rejected=true;}
        }
        assertTrue(validate&&trial);if(!missing&&!scenario.equals("unreadable_source"))assertTrue(rejected);
        var side=json.readTree(Files.readAllBytes(input.getParent().resolve("file-side-effects.json")));assertTrue(side.path("unchanged").asBoolean());assertEquals(side.path("before"),side.path("after"));
        for(var file:side.path("after"))assertEquals(file.path("sha256").asText(),sha(Files.readAllBytes(Path.of(file.path("path").asText()))));
        Path out=Path.of(required("ENF_DOCUMENT_RULE_NEGATIVE_OUTPUT"));Files.createDirectories(out);
        json.writeValue(out.resolve(scenario+".json").toFile(),java.util.Map.of("scenario",scenario,"ruleId",id,"attempts",attempts,"receipts",receipts,"events",events,"result","ACTUAL_DM8_FILE_SHA_BROWSER_RECONCILED","businessAcceptance","PENDING"));
    }
    private static String sha(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    private static String required(String name){String value=DocumentRuleRuntimeEnvironment.value(name, System.getenv());if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
