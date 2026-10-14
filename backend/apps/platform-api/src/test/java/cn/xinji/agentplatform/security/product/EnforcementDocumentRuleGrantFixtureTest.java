package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit technical input grants through the existing identity system; never a business approval. */
@EnabledIfEnvironmentVariable(named="ENF_DOCUMENT_RULE_PREP_OUTPUT",matches=".+")
class EnforcementDocumentRuleGrantFixtureTest {
    @Test void prepareExactProductClientAndMappingInputGrants() throws Exception {
        var source=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                var c=super.getConnection();try{c.setSchema(required("ENF_ACCEPTANCE_AUTHORITY_SCHEMA"));return c;}catch(java.sql.SQLException e){c.close();throw e;}
            }
        };
        var db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var clients=new ProductClients(db,new ProductRegisteredClients(new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
        String clientId="enforcement-document-rule-20260930";
        String redirect="https://127.0.0.1:5176/app/enforcement/document-rules";
        if(db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND redirect_uri=? AND status='ACTIVE'",Integer.class,clientId,redirect)==0)
            clients.register(new ProductClients.Input(clientId,"Enforcement document mapping",redirect),UUID.randomUUID().toString(),"codex-functional-validation");
        var grants=new ProductGrants(db,tx,Clock.systemUTC());
        var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),required("ENF_ACCEPTANCE_ISSUER"));
        String subject=required("ENF_ACCEPTANCE_SUBJECT"),org=required("ENF_ACCEPTANCE_ORGANIZATION"),issuer=required("ENF_ACCEPTANCE_ISSUER");
        String rule=required("ENF_DOCUMENT_RULE_ID");long revision=Long.parseLong(required("ENF_DOCUMENT_RULE_REVISION"));
        if("true".equals(System.getenv("ENF_DOCUMENT_RULE_REVOKE_READ"))){
            var grant=db.queryForMap("SELECT g.grant_id,g.grant_version FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.resource_type='DOCUMENT_RULE' AND g.resource_id=? AND g.action_code='READ' AND g.purpose='DOCUMENT_MAPPING'",issuer,subject,org,rule);
            var receipt=watermark.change(()->grants.revoke((String)grant.get("GRANT_ID"),((Number)grant.get("GRANT_VERSION")).longValue(),"codex-functional-validation",UUID.randomUUID().toString(),"d".repeat(64)));
            new ObjectMapper().findAndRegisterModules().writeValue(Path.of(required("ENF_DOCUMENT_RULE_PREP_OUTPUT")).toFile(),receipt);
            return;
        }
        for(String action:List.of("READ","VALIDATE","TRIAL","PUBLISH","DISABLE"))issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_RULE",rule,revision,action,"DOCUMENT_MAPPING");
        issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_RULE",rule,revision+1,"EDIT","DOCUMENT_MAPPING");
        if(revision==1)issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_RULE",rule,1,"EDIT","DOCUMENT_MAPPING");
        issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_TEMPLATE",required("ENF_ACCEPTANCE_TEMPLATE_ID"),1,"READ","DOCUMENT_TEMPLATE");
        if(List.of("dependency","recovery_seed").contains(System.getenv("ENF_DOCUMENT_RULE_SCENARIO")==null?"":System.getenv("ENF_DOCUMENT_RULE_SCENARIO")))
            issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_TEMPLATE",required("ENF_ACCEPTANCE_TEMPLATE_ID"),1,"EDIT","DOCUMENT_TEMPLATE");
        issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_SCHEMA","PROJECT-ENF-DOCUMENT-SCHEMA-01",1,"READ","DOCUMENT_MAPPING");
        String sourceId=System.getenv("ENF_DOCUMENT_RULE_SOURCE_ID");
        long sourceRevision=System.getenv("ENF_DOCUMENT_RULE_SOURCE_REVISION")==null?1:Long.parseLong(System.getenv("ENF_DOCUMENT_RULE_SOURCE_REVISION"));
        issue(db,grants,watermark,issuer,subject,org,"DOCUMENT_MAPPING_SOURCE",sourceId==null?"ENF13-CONTROLLED-SOURCE-20260930":sourceId,sourceRevision,"READ","DOCUMENT_MAPPING");
        var json=new ObjectMapper().findAndRegisterModules();
        var rules=db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",(rs,n)->{try{return json.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},rule);
        var receipts=db.queryForList("SELECT request_key,operation,revision FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",rule);
        var events=db.queryForList("SELECT request_key,operation,revision FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",rule);
        var attempts=db.queryForList("SELECT attempt_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",rule);
        var attemptEvents=db.queryForList("SELECT attempt_id,revision,actor_ref,action_code,outcome FROM enf10_document_rule_attempt_event WHERE rule_id=? ORDER BY created_at,attempt_id",rule);
        json.writeValue(Path.of(required("ENF_DOCUMENT_RULE_PREP_OUTPUT")).toFile(),Map.of("ruleId",rule,"history",rules,"receipts",receipts,"events",events,"attempts",attempts,"attemptEvents",attemptEvents,"inputNature","FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE"));
    }
    private void issue(JdbcTemplate db, ProductGrants grants, ProductGrantWatermark watermark,
            String issuer, String subject, String org, String type, String id, long version,
            String action, String purpose) {
        var scopes = db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch "
                + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' "
                + "AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",
                (rs, row) -> new Epoch(rs.getLong(1), rs.getString(2)), issuer, subject, org, type, id, action, purpose);
        long epoch = scopes.isEmpty() ? 0 : scopes.getFirst().version();
        if (!scopes.isEmpty() && scopes.getFirst().grant() != null) {
            String old = scopes.getFirst().grant();
            var usable = db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? "
                    + "AND resource_version=? AND status='ACTIVE' AND expires_at>?", Integer.class,
                    old, version, java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
            if (usable != null && usable == 1) return;
            Long oldVersion = db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?", Long.class, old);
            epoch = watermark.change(() -> grants.revoke(old, oldVersion, "codex-functional-validation",
                    UUID.randomUUID().toString(), "e".repeat(64))).epoch();
        }
        long current = epoch;
        watermark.change(() -> grants.issue(new ProductGrants.Scope(issuer, subject, org, "ENFORCEMENT",
                "ENF17-FUNCTIONAL", "FUNCTIONAL_VALIDATION", type, id, version, action, purpose),
                Instant.now().plusSeconds(7200), current, "codex-functional-validation",
                UUID.randomUUID().toString(), "f".repeat(64)));
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
    private record Epoch(long version,String grant){}
}
