package cn.xinji.agentplatform.security.product;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import cn.xinji.agentplatform.enforcement.documentdraft.DocumentProfileCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit controlled-identity engineering preparation, NOT domain I or business acceptance. */
class EnforcementDocumentDraftPreparationIT {
    @Test void registerPkceClientAndIssueActualCurrentGrantsWithoutCreatingAnIdentity() {
        assertEquals("FUNCTIONAL_VALIDATION",required("ENF_DRAFT_IDENTITY_NATURE"));
        var source=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public Connection getConnection() throws SQLException {var c=super.getConnection();try(var s=c.createStatement()){s.execute("SET SCHEMA ENFACC_AUTH");}return c;}
        };
        assertTrue(source.getUrl().startsWith("jdbc:dm://"));assertTrue(authority.getUrl().startsWith("jdbc:dm://"));
        var db=new JdbcTemplate(source);var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        String issuer=required("ENF_ACCEPTANCE_ISSUER"),actor=required("ENF_ACCEPTANCE_SUBJECT"),org=required("ENF_ACCEPTANCE_ORGANIZATION");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,actor));
        var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),issuer);
        var grants=new ProductGrants(db,tx,Clock.systemUTC());
        var clients=new ProductClients(db,new ProductRegisteredClients(new JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
        String clientId="enforcement-document-draft-20261002",redirect="https://127.0.0.1:5174/app/enforcement/document-drafts";
        int exists=db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND redirect_uri=? AND status='ACTIVE'",Integer.class,clientId,redirect);
        if(exists==0)clients.register(new ProductClients.Input(clientId,"Enforcement document drafting",redirect),UUID.randomUUID().toString(),"codex-engineering-preparation");
        var scopes=new ArrayList<String[]>();scopes.add(new String[]{"DOCUMENT_TEMPLATE","*","READ","DOCUMENT_TEMPLATE"});
        scopes.add(new String[]{"DOCUMENT_DRAFT","*","PREPARE","DOCUMENT_DRAFT"});
        for(var profile:DocumentProfileCatalog.builtin(new ObjectMapper().findAndRegisterModules()).all())
            for(String action:List.of("CREATE","PUBLISH","READ","DOWNLOAD","PREVIEW"))scopes.add(new String[]{"DOCUMENT_TEMPLATE",profile.id(),action,"DOCUMENT_TEMPLATE"});
        for(var scope:scopes) {
            var epochRows=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND resource_type=? AND resource_id=? AND action_code=?",
                    (rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},issuer,actor,org,scope[0],scope[1],scope[2]);
            long epoch=epochRows.isEmpty()?0:(Long)epochRows.getFirst()[0];
            if(!epochRows.isEmpty()&&epochRows.getFirst()[1]!=null) {
                String grant=(String)epochRows.getFirst()[1];
                if(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND status='ACTIVE' AND expires_at>? AND purpose=?",Integer.class,grant,Timestamp.from(Instant.now().plusSeconds(600)),scope[3])==1)continue;
                Long version=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,grant);
                epoch=watermark.change(()->grants.revoke(grant,version,"codex-functional-validation",UUID.randomUUID().toString(),"b".repeat(64))).epoch();
            }
            long expected=epoch;
            watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,actor,org,"ENFORCEMENT","ENF14-ENGINEERING-VALIDATION","FUNCTIONAL_VALIDATION",scope[0],scope[1],1,scope[2],scope[3]),
                    Instant.now().plusSeconds(7200),expected,"codex-functional-validation",UUID.randomUUID().toString(),"c".repeat(64)));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_client_current WHERE client_id=? AND redirect_uri=? AND status='ACTIVE'",Integer.class,clientId,redirect));
    }
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
