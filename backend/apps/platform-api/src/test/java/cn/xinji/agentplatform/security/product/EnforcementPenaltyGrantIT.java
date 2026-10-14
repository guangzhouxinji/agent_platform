package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Product identity provisioning only, not business approval or product acceptance. No conditional skipping. */
class EnforcementPenaltyGrantIT {
    @Test void prepareExactTechnicalProductIdentityAndReadOnlyLegalInput()throws Exception {
        var data=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(java.sql.SQLException e){c.close();throw e;}}
        };
        assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(data.getUrl(),authority.getUrl());
        var db=new JdbcTemplate(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        String issuer=required("ENF_ACCEPTANCE_ISSUER"),subject=required("ENF_ACCEPTANCE_SUBJECT"),org=required("ENF_ACCEPTANCE_ORGANIZATION");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,subject));
        var clients=new ProductClients(db,new ProductRegisteredClients(new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
        String client=System.getenv().getOrDefault("ENF_PENALTY_CLIENT_ID","enforcement-penalty-20261001");
        String port=System.getenv().getOrDefault("ENF_PENALTY_FRONTEND_PORT","5178");assertTrue(port.matches("[0-9]{4,5}"));
        String redirect="https://127.0.0.1:"+port+"/app/enforcement/penalty";
        if(db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND status='ACTIVE'",Integer.class,client)==0)
            clients.register(new ProductClients.Input(client,"Enforcement penalty product",redirect),UUID.randomUUID().toString(),"codex-functional-validation");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND redirect_uri=? AND status='ACTIVE'",Integer.class,client,redirect));
        var json=new ObjectMapper().findAndRegisterModules();
        var candidates=db.query("SELECT r.payload FROM enf9_legal_reference r WHERE r.organization_id=? AND r.source_id='CNIPA_PATENT_IMPLEMENTING_RULES_2023' AND r.source_version='2023-12-11' "
            +"AND r.revision=(SELECT MAX(x.revision) FROM enf9_legal_reference x WHERE x.reference_id=r.reference_id) ORDER BY created_at DESC",(r,n)->{try{return json.readTree(r.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},org);
        assertFalse(candidates.isEmpty(),"Existing real V2 official-source reference required; do not fabricate one");var ref=candidates.getFirst();
        var grants=new ProductGrants(db,tx,Clock.systemUTC());var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),issuer);
        String id=required("ENF_PENALTY_RULE_ID");
        for(String action:List.of("READ","EDIT","TRIAL","PUBLISH","DISABLE"))issue(db,grants,watermark,issuer,subject,org,"PENALTY_RULE",id,1,action,"PENALTY_ANALYSIS");
        issue(db,grants,watermark,issuer,subject,org,"LEGAL_REFERENCE",ref.path("referenceId").asText(),ref.path("revision").asLong(),"READ","LEGAL_BASIS");
        issue(db,grants,watermark,issuer,subject,org,"LEGAL_SOURCE",ref.path("sourceId").asText(),ref.path("sourceRevision").asLong(),"READ_VERBATIM","LEGAL_BASIS");
        issue(db,grants,watermark,issuer,subject,org,ref.path("targetType").asText(),ref.path("targetId").asText(),ref.path("targetRevision").asLong(),"READ_LEGAL_BASIS","LEGAL_BASIS");
        var rules=db.query("SELECT revision,state,artifact_key,artifact_sha,payload FROM enf11_penalty_revision WHERE resource_kind='PENALTY_RULE' AND resource_id=? ORDER BY revision",
            (r,n)->{try{return Map.of("revision",r.getLong(1),"state",r.getString(2),"artifactKey",r.getString(3),"artifactSha",r.getString(4),"payload",json.readTree(r.getString(5)));}catch(java.io.IOException e){throw new IllegalStateException(e);}},id);
        var receipts=db.queryForList("SELECT request_key,operation,revision FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,operation,revision FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);assertEquals(receipts,events);
        json.writeValue(Path.of(required("ENF_PENALTY_PREP_EVIDENCE")).toFile(),Map.of("clientId",client,"redirectUri",redirect,"ruleId",id,"legalReference",ref,"rules",rules,"receipts",receipts,"events",events,"scope","TECHNICAL_IDENTITY_PREPARATION_NOT_BUSINESS_ACCEPTANCE"));
    }
    private void issue(JdbcTemplate db,ProductGrants grants,ProductGrantWatermark watermark,String issuer,String subject,String org,String type,String id,long revision,String action,String purpose) {
        var rows=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",
            (r,n)->new Epoch(r.getLong(1),r.getString(2)),issuer,subject,org,type,id,action,purpose);
        long epoch=rows.isEmpty()?0:rows.getFirst().version();
        if(!rows.isEmpty()&&rows.getFirst().grant()!=null) {
            String old=rows.getFirst().grant();
            int usable=db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND resource_version=? AND status='ACTIVE' AND expires_at>?",Integer.class,old,revision,java.sql.Timestamp.from(Instant.now().plusSeconds(600)));
            if(usable==1)return;
            long oldVersion=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,old);
            epoch=watermark.change(()->grants.revoke(old,oldVersion,"codex-functional-validation",UUID.randomUUID().toString(),"e".repeat(64))).epoch();
        }
        long prior=epoch;watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,subject,org,"ENFORCEMENT","ENF-PENALTY-FUNCTIONAL","FUNCTIONAL_VALIDATION",type,id,revision,action,purpose),Instant.now().plusSeconds(7200),prior,"codex-functional-validation",UUID.randomUUID().toString(),"f".repeat(64)));
    }
    private record Epoch(long version,String grant) {}
    static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException(n+" required; no skip");return v;}
}
