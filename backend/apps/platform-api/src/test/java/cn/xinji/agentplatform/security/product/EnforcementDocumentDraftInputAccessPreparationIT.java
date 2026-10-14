package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.enforcement.documentrule.FileDocumentRuleInputs;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing controlled identity read-access preparation. Not domain I, a case, or business approval. */
class EnforcementDocumentDraftInputAccessPreparationIT {
    record Scope(String type,String id,long revision,String purpose) { }
    @Test void issueExactDiagnosticReadGrantsThroughExistingGrantAndIndependentWatermarkServices()throws Exception {
        assertEquals("FUNCTIONAL_VALIDATION",required("ENF_DRAFT_IDENTITY_NATURE"));
        var data=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public Connection getConnection()throws SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(SQLException e){c.close();throw e;}}
        };
        assertTrue(data.getUrl().startsWith("jdbc:dm://"));assertNotEquals(data.getUrl(),authority.getUrl());
        var db=new JdbcTemplate(data);var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        String issuer=required("ENF_ACCEPTANCE_ISSUER"),actor=required("ENF_ACCEPTANCE_SUBJECT"),org=required("ENF_ACCEPTANCE_ORGANIZATION");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,actor));
        var json=new ObjectMapper().findAndRegisterModules();var context=json.readTree(Files.readAllBytes(Path.of(required("ENF_DRAFT_DIAGNOSTIC_CASE_FILE"))));
        assertEquals(org,context.path("organization").asText());
        var scopes=new LinkedHashSet<Scope>();scopes.add(new Scope("CASE",context.path("caseRef").asText(),context.path("version").asLong(),"DOCUMENT_DRAFT"));
        for(var material:context.path("materials"))scopes.add(new Scope("MATERIAL",material.path("id").asText(),material.path("version").asLong(),"DOCUMENT_DRAFT"));
        for(var row:db.query("SELECT rule_id,revision FROM enf10_document_rule r WHERE organization_id=? AND revision=(SELECT MAX(revision) FROM enf10_document_rule x WHERE x.rule_id=r.rule_id)",(rs,n)->new Scope("DOCUMENT_RULE",rs.getString(1),rs.getLong(2),"DOCUMENT_MAPPING"),org))scopes.add(row);
        for(var row:db.query("SELECT reference_id,revision FROM enf9_legal_reference r WHERE organization_id=? AND revision=(SELECT MAX(revision) FROM enf9_legal_reference x WHERE x.reference_id=r.reference_id)",(rs,n)->new Scope("LEGAL_REFERENCE",rs.getString(1),rs.getLong(2),"LEGAL_REFERENCE"),org))scopes.add(row);
        var catalog=json.readValue(Files.readAllBytes(Path.of(required("ENF_DRAFT_DIAGNOSTIC_MAPPING_CATALOG"))),FileDocumentRuleInputs.Catalog.class);
        for(var source:catalog.schemas())if(org.equals(source.organization())) {
            scopes.add(new Scope("DOCUMENT_SCHEMA",source.schema().id(),source.authorizationRevision(),"DOCUMENT_MAPPING"));
            scopes.add(new Scope("DOCUMENT_MAPPING_SOURCE",source.sourceId(),source.sourceRevision(),"DOCUMENT_MAPPING"));
        }
        var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),issuer);var grants=new ProductGrants(db,tx,Clock.systemUTC());
        for(var scope:scopes) {
            var epochs=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type=? AND resource_id=? AND action_code='READ' AND purpose=?",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},issuer,actor,org,scope.type(),scope.id(),scope.purpose());
            long epoch=epochs.isEmpty()?0:(Long)epochs.getFirst()[0];
            if(!epochs.isEmpty()&&epochs.getFirst()[1]!=null) {
                String grant=(String)epochs.getFirst()[1];
                if(db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND status='ACTIVE' AND resource_version=? AND expires_at>? AND purpose=?",Integer.class,grant,scope.revision(),Timestamp.from(Instant.now().plusSeconds(600)),scope.purpose())==1)continue;
                Long version=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,grant);
                epoch=watermark.change(()->grants.revoke(grant,version,"codex-diagnostic-read-preparation",UUID.randomUUID().toString(),"b".repeat(64))).epoch();
            }
            long expected=epoch;
            watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,actor,org,"ENFORCEMENT","ENF14-INPUT-DIAGNOSTICS","FUNCTIONAL_VALIDATION",scope.type(),scope.id(),scope.revision(),"READ",scope.purpose()),Instant.now().plusSeconds(7200),expected,"codex-diagnostic-read-preparation",UUID.randomUUID().toString(),"c".repeat(64)));
        }
        assertFalse(scopes.isEmpty());
    }
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
